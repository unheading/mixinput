package com.jinn.inputmethod.plugin

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 插件管理器：发现已安装的插件 APK、按需绑定、缓存代理。
 *
 * 插件形态（吸纳 fcitx5-android 的做法）：插件是**独立 APK**，内部声明一个
 * `Service` 并 `intent-filter` 声明 [ACTION_PLUGIN]；宿主扫描到后 bindService，
 * 通过 [IMixPlugin] 调用。这样日语/韩语/手写/OCR 等重能力都不占主程序体积，
 * 且插件崩溃不会拖垮输入法进程（不同进程）。
 *
 * 线程约定：绑定回调在 Binder 线程，[refresh] 的完成回调统一切回主线程。
 */
object PluginManager {

    /** 插件 Service 必须声明的 action */
    const val ACTION_PLUGIN = "com.jinn.inputmethod.plugin.BIND"

    /** 绑定等待上限：超时就用已拿到的结果回调，避免个别插件卡住整个列表 */
    private const val BIND_TIMEOUT_MS = 2500L

    data class PluginInfo(
        val packageName: String,
        val className: String,
        val id: String,
        val name: String,
        val version: Int,
        val capabilities: List<String>,
    ) {
        /** 形如 ["ja", "ko"]：该插件提供的输入引擎标识 */
        val engines: List<String>
            get() = capabilities.filter { it.startsWith(ENGINE_PREFIX) }
                .map { it.removePrefix(ENGINE_PREFIX) }

        /** 是否提供输入引擎能力 */
        val isEngine: Boolean get() = engines.isNotEmpty()
    }

    private const val ENGINE_PREFIX = "engine:"

    private val proxies = ConcurrentHashMap<String, IMixPlugin>()
    private val connections = ConcurrentHashMap<String, ServiceConnection>()
    private val infos = ConcurrentHashMap<String, PluginInfo>()

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun keyOf(pkg: String, cls: String) = "$pkg/$cls"

    /** 已绑定的插件信息（需先 [refresh]） */
    fun known(): List<PluginInfo> = infos.values.sortedBy { it.name }

    /** 提供某个引擎标识的插件代理（无则 null）。例：engineProxy("ja") */
    fun engineProxy(engineId: String): IMixPlugin? {
        val info = infos.values.firstOrNull { engineId in it.engines } ?: return null
        return proxies[keyOf(info.packageName, info.className)]
    }

    /**
     * 扫描并绑定所有候选插件，全部就绪（或超时）后在主线程回调已识别到的插件列表。
     * 重复调用会跳过已绑定的条目，可安全地反复调（例如每次进入插件页）。
     */
    fun refresh(context: Context, onDone: (List<PluginInfo>) -> Unit) {
        val app = context.applicationContext
        val candidates = queryCandidates(app)
        if (candidates.isEmpty()) {
            infos.clear()
            mainHandler.post { onDone(emptyList()) }
            return
        }
        val done = AtomicBoolean(false)
        fun finishOnce() {
            if (done.compareAndSet(false, true)) mainHandler.post { onDone(known()) }
        }
        val pending = AtomicInteger(0)
        for ((pkg, cls) in candidates) {
            val k = keyOf(pkg, cls)
            if (proxies.containsKey(k)) continue
            pending.incrementAndGet()
            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    val proxy = IMixPlugin.Stub.asInterface(service)
                    if (proxy != null) {
                        try {
                            val info = PluginInfo(
                                packageName = pkg,
                                className = cls,
                                id = proxy.pluginId() ?: "",
                                name = proxy.pluginName() ?: pkg,
                                version = proxy.pluginVersion(),
                                capabilities = proxy.capabilities() ?: emptyList(),
                            )
                            if (info.id.isNotEmpty()) {
                                infos[k] = info
                                proxies[k] = proxy
                            }
                        } catch (_: Throwable) {
                            // 插件实现异常：忽略该插件，不影响其他插件与输入法本身
                        }
                    }
                    if (pending.decrementAndGet() == 0) finishOnce()
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    proxies.remove(k)
                    infos.remove(k)
                    connections.remove(k)
                }
            }
            connections[k] = conn
            val boundOk = try {
                app.bindService(Intent(ACTION_PLUGIN).setComponent(ComponentName(pkg, cls)), conn, Context.BIND_AUTO_CREATE)
            } catch (_: Throwable) {
                false
            }
            if (!boundOk) {
                connections.remove(k)
                if (pending.decrementAndGet() == 0) finishOnce()
            }
        }
        if (pending.get() == 0) {
            finishOnce()
            return
        }
        // 兜底：个别插件迟迟不回调时，不阻塞列表展示
        mainHandler.postDelayed({ finishOnce() }, BIND_TIMEOUT_MS)
    }

    /** 扫描声明了 [ACTION_PLUGIN] 的 Service（自身的 Service 会被排除） */
    private fun queryCandidates(context: Context): List<Pair<String, String>> {
        val result = ArrayList<Pair<String, String>>()
        val services = try {
            context.packageManager.queryIntentServices(Intent(ACTION_PLUGIN), 0)
        } catch (_: Throwable) {
            null
        } ?: return result
        for (si in services) {
            val info = si.serviceInfo ?: continue
            val pkg = info.packageName ?: continue
            val cls = info.name ?: continue
            if (pkg == context.packageName) continue
            result.add(pkg to cls)
        }
        return result
    }
}
