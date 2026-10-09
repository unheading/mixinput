package com.jinn.inputmethod

import android.content.Context
import com.kingzcheung.xime.rime.RimeEngine
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Rime 引擎的接入桥。
 *
 * 职责只有一件事：把随 APK 打包的 Rime 方案数据（assets/rime/）释放到应用私有目录，
 * 然后初始化 librime 并建立会话。初始化在后台线程执行，不阻塞输入法启动。
 *
 * 目录约定（与 Xime 保持一致）：
 *   - shared：方案与词库（来自 assets，可覆盖重写）
 *   - user  ：用户数据、编译产物、学习记录（只增不减）
 */
object RimeBridge {

    private const val ASSET_DIR = "rime"
    private const val PREFS = "rime_bridge"
    private const val KEY_ENABLED = "rime_mode_enabled"
    private const val KEY_T9 = "t9_mode_enabled"

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "jinn-rime-init").apply { isDaemon = true }
    }
    private val started = AtomicBoolean(false)

    @Volatile
    var isReady: Boolean = false
        private set

    @Volatile
    var lastError: String? = null
        private set

    val sharedDir: File?
        get() = appContext?.let { File(it.filesDir, "rime/shared") }

    val userDir: File?
        get() = appContext?.let { File(it.filesDir, "rime/user") }

    private var appContext: Context? = null

    /** 幂等：重复调用只会真正执行一次；失败后允许再次调用重试。 */
    fun start(context: Context) {
        appContext = context.applicationContext
        if (isReady) return
        if (!started.compareAndSet(false, true)) return
        executor.execute {
            try {
                val ctx = context.applicationContext
                val shared = File(ctx.filesDir, "rime/shared")
                val user = File(ctx.filesDir, "rime/user")
                extractAssets(ctx, shared)
                user.mkdirs()
                val engine = RimeEngine.getInstance()
                engine.initialize(user.absolutePath, shared.absolutePath)
                engine.ensureSession()
                isReady = true
                lastError = null
            } catch (t: Throwable) {
                lastError = t.message ?: t.javaClass.simpleName
                isReady = false
            } finally {
                started.set(false)
            }
        }
    }

    /** 是否启用 Rime 输入（关闭时输入走 Jinn 自己的拼音引擎，零开销） */
    fun isModeEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setModeEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** 是否处于九宫格（T9）输入态 */
    fun isT9Enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_T9, false)

    fun setT9Enabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_T9, enabled).apply()
    }

    // ── T9（九宫格）────────────────────────────────────────────
    // 九键不是「把数字当字母」：流程是 数字串 → 音节候选 → 选定拼音 → 交给 Rime 出词。
    // 这套机制在 librime-t9 里（已随 librime_jni.so 一起内置），这里只做转发。

    /** 数字串对应的首音节候选：拼音 to 需消耗的数字位数 */
    fun t9SyllableOptions(digits: String, max: Int = 8): List<Pair<String, Int>> {
        if (!isReady || digits.isEmpty()) return emptyList()
        return try {
            RimeEngine.getInstance().t9GetFirstSyllableOptions(digits, max)
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /** 选定一个拼音音节并消费 [digitLength] 位数字；之后由 Rime 给出候选 */
    fun t9SelectSyllable(pinyin: String, digitLength: Int): Boolean {
        if (!isReady) return false
        return try {
            RimeEngine.getInstance().t9SelectPinyinDirect(pinyin, digitLength)
        } catch (_: Throwable) {
            false
        }
    }

    /** 尚未被消费的数字（用于继续拼下一个音节） */
    fun t9RemainingDigits(): String {
        if (!isReady) return ""
        return try {
            RimeEngine.getInstance().t9GetRemainingDigits()
        } catch (_: Throwable) {
            ""
        }
    }

    /** 可切换的 Rime 方案，顺序即长按循环顺序（需与 assets/rime 中实际存在的方案一致） */
    val SCHEMAS = listOf("pinyin_simp", "wubi86", "wubi86_pinyin")

    /** 切换 Rime 方案；引擎未就绪或失败时返回 false */
    fun switchSchema(schemaId: String): Boolean {
        if (!isReady) return false
        return try {
            RimeEngine.getInstance().switchSchema(schemaId)
        } catch (_: Throwable) {
            false
        }
    }

    /** 当前 Rime 方案名（如 pinyin_simp / wubi86），未就绪时返回空串 */
    fun currentSchema(): String = try {
        if (isReady) RimeEngine.getInstance().getCurrentSchema() else ""
    } catch (_: Throwable) {
        ""
    }

    /**
     * 带注释的候选（如五笔方案的编码提示）。
     * 返回「候选词 to 注释」，注释为空时调用方不显示提示。
     */
    fun queryWithComments(input: String): List<Pair<String, String>> {
        if (input.isEmpty() || !isReady) return emptyList()
        return try {
            val engine = RimeEngine.getInstance()
            engine.setInput(input)
            engine.getCandidatesWithComments()
                .map { it.text to it.comment }
                .filter { it.first.isNotEmpty() }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /**
     * 选中候选并取回上屏文本。
     * 候选页里按索引选中；选中后由引擎给出该候选对应的完整上屏内容（含已确定的残码）。
     * 失败返回 null，调用方回退到「直接上屏候选字符串」。
     */
    fun selectAndCommit(index: Int): String? {
        if (!isReady) return null
        return try {
            val engine = RimeEngine.getInstance()
            if (!engine.selectCandidate(index)) return null
            engine.commit().takeIf { it.isNotEmpty() }
        } catch (_: Throwable) {
            null
        }
    }

    /** 清空 Rime 组合态（清空候选、切换输入框时调） */
    fun resetComposition() {
        if (!isReady) return
        try {
            RimeEngine.getInstance().clearComposition()
        } catch (_: Throwable) {
            // 引擎内部异常不影响输入法主流程
        }
    }

    /** 把 assets/rime/ 下的方案数据释放到 [target]。
     *
     * 每个文件按大小比对：大小一致就跳过，避免每次启动重写 2MB+ 的词库。
     * 用户目录里的编译产物与学习记录不受影响。
     */
    private fun extractAssets(context: Context, target: File) {
        if (!target.exists() && !target.mkdirs()) return
        val names = context.assets.list(ASSET_DIR) ?: return
        for (name in names) {
            val out = File(target, name)
            try {
                context.assets.open("$ASSET_DIR/$name").use { input ->
                    if (out.exists() && out.length() == context.assets.openFd("$ASSET_DIR/$name").use { it.length }) {
                        return@use
                    }
                    out.outputStream().use { output -> input.copyTo(output) }
                }
            } catch (_: Throwable) {
                // 单个文件失败不影响其他方案文件；下次启动会重试
            }
        }
    }
}
