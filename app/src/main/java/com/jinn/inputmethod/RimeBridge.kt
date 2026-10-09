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

    /** 当前 Rime 方案名（如 pinyin_simp / wubi86），未就绪时返回空串 */
    fun currentSchema(): String = try {
        if (isReady) RimeEngine.getInstance().getCurrentSchema() else ""
    } catch (_: Throwable) {
        ""
    }

    /**
     * 把整串输入交给 Rime，返回候选（不含方案名注释）。
     * 引擎未就绪或调用异常时返回空列表，调用方按「无候选」处理即可。
     */
    fun query(input: String): List<String> {
        if (input.isEmpty()) return emptyList()
        if (!isReady) return emptyList()
        return try {
            val engine = RimeEngine.getInstance()
            engine.setInput(input)
            engine.getCandidates().toList()
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
