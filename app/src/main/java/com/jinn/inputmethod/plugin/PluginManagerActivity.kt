package com.jinn.inputmethod.plugin

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 插件管理页：列出已安装的插件及其能力。
 *
 * 独立 Activity（而不是塞进设置页那份大布局），改动面小、便于后续演进；
 * 入口由设置页的「插件」按钮进入。
 */
class PluginManagerActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "插件"
            textSize = 22f
            setPadding(0, 0, 0, pad / 2)
        })

        root.addView(TextView(this).apply {
            text = "插件以独立 APK 提供扩展能力（如日/韩输入引擎、手写、OCR）。" +
                "安装后在此列出，卸载即消失；插件不在主程序里，所以不占输入法体积。"
            textSize = 13f
            alpha = 0.7f
            setPadding(0, 0, 0, pad)
        })

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)

        list.addView(TextView(this).apply { text = "正在扫描…" })

        PluginManager.refresh(this) { plugins ->
            list.removeAllViews()
            if (plugins.isEmpty()) {
                list.addView(TextView(this).apply {
                    text = "未发现插件。\n\n" +
                        "插件需要满足：\n" +
                        "1) 是一个独立的 APK；\n" +
                        "2) 声明一个 Service，并在它的 manifest 里加 intent-filter：\n" +
                        "   <action android:name=\"${PluginManager.ACTION_PLUGIN}\" />\n\n" +
                        "宿主识别到后会自动列出其能力（capabilities）。"
                    textSize = 14f
                })
                return@refresh
            }
            for (p in plugins) {
                list.addView(TextView(this).apply {
                    text = buildString {
                        append("● ${p.name}（id: ${p.id}）\n")
                        append("   包名：${p.packageName}\n")
                        append("   版本：${p.version}\n")
                        append("   能力：${if (p.capabilities.isEmpty()) "无" else p.capabilities.joinToString("、")}")
                        if (p.isEngine) append("\n   输入引擎：${p.engines.joinToString("、")}")
                    }
                    textSize = 14f
                    setPadding(0, pad / 2, 0, pad / 2)
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                })
            }
        }

        setContentView(ScrollView(this).apply { addView(root) })
    }
}
