#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
i18n_embed.py —— 把翻译好的 i18n/i18n_table.json 生成 Kotlin 译表 I18nStrings.kt（最后一步）。

opencode 把 i18n_table.json 里每个中文串的各语言译文填好后，跑这个脚本一键嵌入：
  python tools/i18n_embed.py
生成的 I18nStrings.kt 覆盖 app 里的样板；空译文自动跳过(运行时回退中文)。之后 compileDebugKotlin 验证即可。
"""
import os, json

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TABLE = os.path.join(ROOT, "i18n", "i18n_table.json")
OUT = os.path.join(ROOT, "app", "src", "main", "kotlin", "com", "arix", "app", "I18nStrings.kt")

def esc(s):
    return s.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$").replace("\r", "").replace("\n", "\\n")

def main():
    data = json.load(open(TABLE, encoding="utf-8"))
    langs = data["languages"]
    strings = data["strings"]
    lines = []
    lines.append("package com.arix.app")
    lines.append("")
    lines.append("// ============================================================")
    lines.append("// I18nStrings —— 多语言翻译表（中文原串 → 各语言）。")
    lines.append("// ⚠️ 本文件由 tools/i18n_embed.py 自动生成，请勿手改；改翻译改 i18n/i18n_table.json 后重跑脚本。")
    lines.append("// ============================================================")
    # 每语言一个独立的私有 object，各自编译成一个 class：
    #  - 单个方法不会撑爆 JVM 64KB 方法上限；
    #  - 全部译文也不会挤进同一个 class 的常量池（>64K 项会 ClassTooLargeException）。
    # 注意：译文不做 strip——不少串是靠拼接的句子片段，首尾空格有意义。
    total = 0
    used = []
    for lang in langs:
        pairs = [(zh, row.get(lang) or "") for zh, row in strings.items() if (row.get(lang) or "").strip()]
        if not pairs:
            continue
        total += len(pairs)
        obj = "L_" + lang.replace("-", "_")
        used.append((lang, obj))
        lines.append(f"private object {obj} {{")
        lines.append("    val M: Map<String, String> = mapOf(")
        for zh, t in pairs:
            lines.append(f'        "{esc(zh)}" to "{esc(t)}",')
        lines.append("    )")
        lines.append("}")
        lines.append("")
    lines.append("object I18nStrings {")
    lines.append("    fun get(langCode: String, zh: String): String? = MAP[langCode]?.get(zh)")
    lines.append("")
    lines.append("    val MAP: Map<String, Map<String, String>> = mapOf(")
    for lang, obj in used:
        lines.append(f'        "{lang}" to {obj}.M,')
    lines.append("    )")
    lines.append("}")
    with open(OUT, "w", encoding="utf-8", newline="") as f:
        f.write("\n".join(lines) + "\n")
    print(f"生成 {os.path.relpath(OUT, ROOT)}：{len(langs)} 语言、共 {total} 条译文")

if __name__ == "__main__":
    main()
