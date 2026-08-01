#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
i18n_merge.py —— 把分片译文安全合并进 i18n/i18n_table.json（第二步半）。

翻译时按语言族拆给多个 agent 并行做，各自写一份分片 JSON：
    {"de": {"<中文原串>": "<译文>", ...}, "nl": {...}}
本脚本 load→改→dump 落盘（不整文件重写，避免把没动的语言弄丢）。

用法：python tools/i18n_merge.py <分片1.json> [分片2.json ...]
      python tools/i18n_merge.py --check        只查空槽，不写

校验（不合格就拒绝写入，别把脏数据灌进译表）：
  - 语言码必须在译表 languages 里
  - 中文原串必须已是译表的 key（拼错/漏字会静默变成一条永不命中的死 key）
  - 译文不许空、不许原样等于中文原串（zh-TW 例外：繁简同形是正常的）
  - html.unescape 兜一道，防 agent 手滑把 & 写成 &amp;
"""
import os, sys, json, io, html

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TABLE = os.path.join(ROOT, "i18n", "i18n_table.json")


def load_table():
    return json.load(io.open(TABLE, encoding="utf-8"))


def report_gaps(data):
    langs, strings = data["languages"], data["strings"]
    gaps = {k: [l for l in langs if not v.get(l)] for k, v in strings.items()}
    gaps = {k: v for k, v in gaps.items() if v}
    total = sum(len(v) for v in gaps.values())
    print("key 总数 %d / 语言 %d / 空格子 %d（涉及 %d 个 key）" % (len(strings), len(langs), total, len(gaps)))
    for k, v in list(gaps.items())[:20]:
        print("   缺 %2d 种: %s" % (len(v), k[:40]))
    return total


def main():
    args = [a for a in sys.argv[1:]]
    data = load_table()
    if not args or args[0] == "--check":
        report_gaps(data)
        return 0

    langs, strings = data["languages"], data["strings"]
    errors, staged = [], []
    for path in args:
        shard = json.load(io.open(path, encoding="utf-8"))
        for lang, pairs in shard.items():
            if lang not in langs:
                errors.append("%s: 语言码 %r 不在译表 languages 里" % (path, lang))
                continue
            for src, dst in pairs.items():
                if src not in strings:
                    errors.append("%s[%s]: 原串不在译表里(拼写不一致?) %r" % (path, lang, src[:40]))
                    continue
                dst = html.unescape(dst).strip()
                if not dst:
                    errors.append("%s[%s]: 译文为空 %r" % (path, lang, src[:40]))
                    continue
                # zh-TW/ja 与中文共用汉字，「工具」「禁止」这类同形是正确译文而非偷懒照抄，豁免。
                # 其余语言（含用谚文的 ko）若与中文一字不差，几乎必是没翻，拦下。
                if dst == src and lang not in ("zh-TW", "ja"):
                    errors.append("%s[%s]: 译文原样等于中文 %r" % (path, lang, src[:40]))
                    continue
                staged.append((src, lang, dst))

    if errors:
        print("拒绝写入，先修这 %d 个问题：" % len(errors))
        for e in errors[:40]:
            print("  ! " + e)
        return 1

    for src, lang, dst in staged:
        strings[src][lang] = dst
    data["count"] = len(strings)
    io.open(TABLE, "w", encoding="utf-8").write(json.dumps(data, ensure_ascii=False, indent=1))
    print("已合并 %d 条译文，来自 %d 个分片。" % (len(staged), len(args)))
    report_gaps(data)
    return 0


if __name__ == "__main__":
    sys.exit(main())
