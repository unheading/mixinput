// SPDX-License-Identifier: GPL-3.0-only
// 插件契约：插件以独立 APK 提供能力，宿主（本输入法）绑定其 Service 后通过本接口调用。
//
// 设计目标（吸纳 fcitx5-android / Xime 的插件思路）：
//   1) 主程序不背体积 —— 日语、韩语、手写、OCR 等重能力都做成可选插件，装了就生效；
//   2) 能力用字符串声明（capabilities），宿主只认识前缀，不硬编码具体插件；
//   3) 引擎类插件（如日语罗马字→假名）通过 candidates()/commit() 参与候选流程，
//      与内置引擎、Rime 走同一套候选栏显示。
package com.jinn.inputmethod.plugin;

import java.util.List;

interface IMixPlugin {
    /** 插件唯一 id（如 "ja"、"ko"、"ocr"），宿主用它做启用状态与去重 */
    String pluginId();

    /** 设置页展示用的名称 */
    String pluginName();

    /** 插件版本号，仅用于展示与兼容判断 */
    int pluginVersion();

    /**
     * 能力声明。约定前缀：
     *   engine:xx  —— 输入引擎（xx 为语言/方案标识，如 engine:ja）
     *   tool:xx    —— 工具能力（如 tool:ocr、tool:handwriting）
     * 宿主按前缀分流，不认识的能力忽略（保证前向兼容）。
     */
    List<String> capabilities();

    /**
     * 引擎能力：给定输入串与方案标识，返回候选（按优先级排序）。
     * 非引擎插件返回空列表即可。
     */
    List<String> candidates(String input, String schemeId);

    /** 引擎能力：确认上屏某个候选，返回最终文本（插件可在此做转换/学习） */
    String commitCandidate(String candidate);

    /** 引擎能力：清空组合状态（切换输入框、清空输入时调用） */
    void resetComposition();
}
