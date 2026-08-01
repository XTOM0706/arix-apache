#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
i18n_wrap.py —— Arix 界面串 i18n 抽取 + 包裹（第一步）。

做两件事（只动 UI 包 app/src/main/kotlin/com/arix/app/，天然排除 tool/cloudapi 的提示词/工具描述/日志）：
  1) 把 UI 调用里的中文字符串**字面量**包成 tr("…")：
       Text("保存")            -> Text(tr("保存"))
       Toast.makeText(c,"已导入",..) -> Toast.makeText(c,tr("已导入"),..)
     只匹配 Text( / makeText( 后**紧跟的纯字面量**；跳过：含 $ 的模板串、含转义引号/换行的、已是 tr( 的。
  2) 扫描全 UI 包里所有 tr("…") 的中文串，产出完整翻译表 i18n/i18n_table.json，
     供 opencode 逐条翻成各语言（只填表、不碰代码），再由 i18n_embed.py 生成 I18nStrings.kt。

安全：包裹默认让中文原样显示（tr 中文=返回原串），编译前后行为不变；跑完必须 compileDebugKotlin 验证。
用法：python tools/i18n_wrap.py        （在仓库根 E:\\Arix 跑）
"""
import os, re, json, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
UI_DIR = os.path.join(ROOT, "app", "src", "main", "kotlin", "com", "arix", "app")
TABLE = os.path.join(ROOT, "i18n", "i18n_table.json")
SKIP_FILES = {"I18n.kt", "I18nStrings.kt"}            # 框架文件不动
# 全语言覆盖（与 I18n.kt 的 Lang 枚举一致，去掉基准 zh）
LANGS = ["zh-TW", "en", "es", "pt", "fr", "de", "it", "nl", "ru", "uk", "pl", "cs", "ro",
         "el", "hu", "sv", "da", "fi", "nb", "tr", "ar", "fa", "he", "hi", "bn", "ta",
         "ja", "ko", "th", "vi", "id", "ms", "tl"]

CJK = "一-鿿㐀-䶿豈-﫿"       # 常用汉字区
# 纯字面量：无换行、无未转义引号、无反斜杠、无 $ 模板；且含至少一个汉字
LIT = r'"([^"\\\n$]*[' + CJK + r'][^"\\\n$]*)"'
# 包裹目标：Text( 或 makeText(…,  后紧跟的字面量
RE_TEXT = re.compile(r'(\bText\(\s*)' + LIT)
RE_TOAST = re.compile(r'(\bmakeText\(\s*[^,\n]+,\s*)' + LIT)
RE_TR = re.compile(r'\btr\(\s*' + LIT + r'\s*\)')

RE_PKG = re.compile(r'^package\s+([\w.]+)', re.M)

def ensure_import(src):
    """tr() 是 com.arix.app 顶层函数；子包(com.arix.app.ui/.theme/…)需显式 import。"""
    m = RE_PKG.search(src)
    if not m:
        return src
    if m.group(1) == "com.arix.app":
        return src   # 同包直接可见
    if "import com.arix.app.tr" in src:
        return src
    # 在 package 行后插入 import
    i = m.end()
    nl = src.find("\n", i)
    if nl < 0:
        return src
    return src[:nl + 1] + "import com.arix.app.tr\n" + src[nl + 1:]

def wrap_file(path):
    with open(path, encoding="utf-8") as f:
        src = f.read()
    orig = src
    def repl(m):
        return m.group(1) + 'tr("' + m.group(2) + '")'
    src = RE_TEXT.sub(repl, src)
    src = RE_TOAST.sub(repl, src)
    # 只要文件里出现 tr(（本次包裹或之前已包裹），就确保子包有 import（幂等）
    if "tr(" in src:
        src = ensure_import(src)
    if src != orig:
        with open(path, "w", encoding="utf-8", newline="") as f:
            f.write(src)
        return True
    return False

def collect(path, bag):
    with open(path, encoding="utf-8") as f:
        for m in RE_TR.finditer(f.read()):
            bag.add(m.group(1))

def main():
    changed, files = 0, 0
    strings = set()
    for dp, _, fns in os.walk(UI_DIR):
        for fn in fns:
            if not fn.endswith(".kt") or fn in SKIP_FILES:
                continue
            p = os.path.join(dp, fn)
            files += 1
            if wrap_file(p):
                changed += 1
            collect(p, strings)
    # 合并已有译文（重跑不丢已翻内容）
    prev = {}
    if os.path.exists(TABLE):
        try:
            prev = {k: v for k, v in json.load(open(TABLE, encoding="utf-8")).get("strings", {}).items()}
        except Exception:
            prev = {}
    table = {}
    for s in sorted(strings):
        row = prev.get(s, {})
        table[s] = {lang: row.get(lang, "") for lang in LANGS}
    os.makedirs(os.path.dirname(TABLE), exist_ok=True)
    with open(TABLE, "w", encoding="utf-8", newline="") as f:
        json.dump({"languages": LANGS, "count": len(table), "strings": table},
                  f, ensure_ascii=False, indent=2)
    print(f"扫描 {files} 个 UI 文件，包裹修改 {changed} 个；唯一 UI 串 {len(table)} 条 → {os.path.relpath(TABLE, ROOT)}")

if __name__ == "__main__":
    main()
