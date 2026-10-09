package com.kingzcheung.xime.util

import android.util.Log

/**
 * 最小日志门面。
 *
 * 移植 Rime 引擎时用来替代 Xime 原版 FileLogger（原版依赖 Xime 的
 * BuildConfig 与 SettingsPreferences，与本项目无关）。
 * 仅保留 [RimeEngine] 实际调用的接口，直接转发到 logcat。
 */
object FileLogger {
    fun v(tag: String, message: String) {
        Log.v(tag, message)
    }

    fun d(tag: String, message: String) {
        Log.d(tag, message)
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        Log.w(tag, message, throwable)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
    }
}
