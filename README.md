# AI Translate for Aliucord

An intelligent message translation plugin for Aliucord powered by Large Language Models (OpenAI, DeepSeek, and any OpenAI-compatible API).

专为 Aliucord 打造的 AI 智能消息翻译插件，支持 OpenAI、DeepSeek 及任意兼容 OpenAI 规范的自定义 API 接口。

---

## Key Features / 核心功能

* Universal API Support: Compatible with OpenAI, DeepSeek, Moonshot, Ollama, and any custom OpenAI-compatible endpoint.
* Natural Language Targets: Define target languages freely (e.g., English, Simplified Chinese, Colloquial Japanese, Classical Chinese).
* In-Settings Utilities: Built-in "Fetch Models" and "Test Connection" buttons for seamless setup.
* In-Place Translation: Long-press any message to translate instantly with a one-tap toggle between original and translated text.
* Robust & Crash-Free: Resolved Kotlin iterator concurrency issues, state restoration crashes, and lazy-view rendering artifacts.

---

* 全协议兼容：支持 OpenAI、DeepSeek、Moonshot 及任意兼容 OpenAI 规范的自定义中转接口。
* 自然语言目标语：支持任意自然语言描述目标语言（如：地道英语、简体中文、二次元口语、文言文等）。
* 设置页便捷工具：内置「一键拉取模型列表」与「连通性测试」功能。
* 无缝嵌入体验：长按消息菜单一键替换翻译，支持快速切换/显示原文。
* 底层稳定性优化：解决 Kotlin 迭代器冲突、状态恢复闪退（State Restoration Crash）以及视图惰性加载异常。

---

## Installation / 安装方式

### Method 1: Direct Link (直接链接安装)
Copy the link below and send or click it in any Discord chat channel inside Aliucord:
https://raw.githubusercontent.com/lishangaaa/aliucord-plugins/builds/AITranslate.zip

### Method 2: Manual Download (手动安装)
1. Download AITranslate.zip from the builds branch.
2. Place it into your device's Aliucord/plugins/ directory.
3. Restart Aliucord.

---

## Configuration / 配置指南

1. Open Aliucord Settings -> Plugins -> AI Translate.
2. Enter your API Base URL (e.g., https://api.deepseek.com/v1 or https://api.openai.com/v1).
3. Enter your API Key.
4. Click Fetch Models to pick a model or enter one manually (e.g., deepseek-chat, gpt-4o-mini).
5. Set your preferred Target Language.
6. Click Test Connection to verify settings.

---

## License & Credits

* License: Licensed under CC BY-NC-SA 4.0 (Creative Commons Attribution-NonCommercial-ShareAlike 4.0 International).
* Credits: Forked and heavily rewritten based on Translate by Tyman (https://github.com/TymanWasTaken/aliucord-plugins).