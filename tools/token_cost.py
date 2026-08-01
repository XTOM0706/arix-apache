#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
token_cost.py —— 量「工具定义的常驻 token 成本」。

每轮请求都要把工具 schema 发给模型，这是固定开销。工具越多、描述越长，每一轮都在付钱。
本脚本静态扫源码估算这笔钱，用来盯住"加工具"带来的隐性成本。

两个模式（默认两个都跑）：
  all      全部 Tool 实现的 schema 总量（上限，假设全部暴露）
  default  只算「默认启用的功能包」里的工具 —— 这才是新用户装上后每轮真实付的钱

用法：
  python tools/token_cost.py              # 两个都跑
  python tools/token_cost.py default      # 只看默认暴露
  python tools/token_cost.py --json       # 机器可读（给 CI / AI 消费）

折算口径（非真实 tokenize，仅用于横向对比与趋势监控）：
  中文 1.2 字符/token，英文 4 字符/token；JSON 结构开销粗估 每参数 22 字符 + 每工具 40 字符。

2026-07-27 基线（首次测量，见 RESEARCH-COMPETITIVE-2026-07-27.md ⑮ 区）：
  全量  85 个工具  ≈ 9,373 token
  默认  38 个工具  ≈ 4,563 token   （功能包 66 个：默认开 33 / 默认关 33）
横向对照（同一折算口径）：hermes-agent 默认 22,644 / Operit 默认 2,111 / rikkahub 默认 64 / kelivo 0。
"""
from __future__ import annotations
import re, os, sys, glob, json

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
ROOT = os.path.join(REPO, 'app', 'src', 'main', 'kotlin', 'com', 'arix')
PKG_FILE = os.path.join(ROOT, 'tool', 'PackageManager.kt')

try:
    sys.stdout.reconfigure(encoding='utf-8')
except Exception:
    pass

CLS_HEAD = re.compile(r'^(?:class|object)\s+(\w+)', re.M)
NAME_RE  = re.compile(r'override val name\s*=\s*"([^"]*)"')
DESC_RE  = re.compile(r'override val description\s*=\s*"((?:[^"\\]|\\.)*)"')
# 模型侧描述（英文那份）。有它就该按它算——每轮发出去的是它，不是给人看的中文 description。
LLMDESC_RE = re.compile(r'override val llmDescription\s*=\s*"((?:[^"\\]|\\.)*)"')
PDESC_RE = re.compile(r'put\("description",\s*"((?:[^"\\]|\\.)*)"')
IS_TOOL  = re.compile(r'(?:class|object)\s+\w+[^\n]*?:\s*[^\n]*\bTool\b')


def cjk(t: str) -> int:
    return sum(1 for c in t if '一' <= c <= '鿿')


# ── 真 tokenizer 优先 ────────────────────────────────────────────────────────
# 原来一律按「中文 1.2 字/token、英文 4 字符/token」折算，那只是个便于横向比较的口径，
# 从来没跟真实 tokenizer 对过账。装了 tiktoken 就用真的（o200k_base = GPT-4o/5 一系，
# 也是目前主流 OpenAI 兼容端点里最常见的一种切法），拿不到再退回旧折算。
#
# ⚠ 各家切法不同，尤其中文：DeepSeek/Qwen 这类中文语料训出来的词表，中文比 o200k 更省；
#   而 o200k 的英文比中文省得多。所以这里的绝对值只对「OpenAI 系」准，**趋势和相对大小通用**。
_ENC = None
_ENC_NAME = 'chars(中文1.2/英文4)'
try:
    import tiktoken
    _ENC = tiktoken.get_encoding('o200k_base')
    _ENC_NAME = 'tiktoken/o200k_base'
except Exception:
    pass


def tok(t: str) -> float:
    if _ENC is not None:
        return float(len(_ENC.encode(t, disallowed_special=())))
    c = cjk(t)
    return c / 1.2 + (len(t) - c) / 4.0


def collect_tools() -> dict:
    """类名 -> {tool, desc, params, file}。只收真正 implements Tool 的类。"""
    out = {}
    for f in glob.glob(os.path.join(ROOT, '**', '*.kt'), recursive=True):
        try:
            s = open(f, encoding='utf-8').read()
        except Exception:
            continue
        if 'override val name' not in s or not IS_TOOL.search(s):
            continue
        heads = [(m.start(), m.group(1)) for m in CLS_HEAD.finditer(s)]
        if not heads:
            continue
        heads.append((len(s), None))
        for i in range(len(heads) - 1):
            body = s[heads[i][0]:heads[i + 1][0]]
            nm = NAME_RE.search(body)
            if not nm:
                continue
            d = LLMDESC_RE.search(body) or DESC_RE.search(body)
            out[heads[i][1]] = {
                'tool': nm.group(1),
                'desc': d.group(1) if d else '',
                'params': PDESC_RE.findall(body),
                'file': os.path.basename(f),
            }
    return out


def cost(entry: dict) -> float:
    txt = entry['desc'] + ''.join(entry['params'])
    struct = len(entry['params']) * 22 + 40
    return tok(txt) + struct / 4.0


def parse_packages(tools: dict) -> list:
    src = open(PKG_FILE, encoding='utf-8').read()
    starts = [m.start() for m in re.finditer(r'PackageDef\("', src)] + [len(src)]
    pkgs = []
    for i in range(len(starts) - 1):
        chunk = src[starts[i]:starts[i + 1]]
        pid = re.search(r'PackageDef\("([^"]+)"', chunk).group(1)
        m = re.search(r'enabledByDefault\s*=\s*(true|false)', chunk)
        enabled = (m.group(1) == 'true') if m else False
        classes = []
        lo = chunk.find('listOf(')
        if lo >= 0:
            depth, j = 0, lo + len('listOf(') - 1
            while j < len(chunk):
                if chunk[j] == '(':
                    depth += 1
                elif chunk[j] == ')':
                    depth -= 1
                    if depth == 0:
                        break
                j += 1
            inner = chunk[lo + len('listOf('):j]
            classes = [c for c in re.findall(r'\b([A-Z]\w+)\s*\(', inner) if c in tools]
        pkgs.append({'id': pid, 'enabled': enabled, 'classes': classes})
    return pkgs


def main():
    args = [a for a in sys.argv[1:] if not a.startswith('-')]
    as_json = '--json' in sys.argv
    mode = args[0] if args else 'both'

    tools = collect_tools()
    pkgs = parse_packages(tools)
    registered = {c for p in pkgs for c in p['classes']}

    all_tok = sum(cost(e) for e in tools.values())
    on = [(tools[c]['tool'], p['id'], cost(tools[c])) for p in pkgs if p['enabled'] for c in p['classes']]
    off = [(tools[c]['tool'], p['id'], cost(tools[c])) for p in pkgs if not p['enabled'] for c in p['classes']]
    on_tok, off_tok = sum(x[2] for x in on), sum(x[2] for x in off)
    orphan = sorted(tools[c]['tool'] for c in tools if c not in registered)

    if as_json:
        print(json.dumps({
            'tools_total': len(tools), 'tokens_all': round(all_tok),
            'packages': len(pkgs),
            'packages_on': sum(1 for p in pkgs if p['enabled']),
            'tools_default': len(on), 'tokens_default': round(on_tok),
            'tools_off': len(off), 'tokens_off': round(off_tok),
            'orphan_tools': orphan,
        }, ensure_ascii=False, indent=2))
        return

    if mode in ('all', 'both'):
        print('=== 全量（假设所有工具都暴露）===')
        print('Tool 实现: %d 个  ->  约 %.0f token/轮' % (len(tools), all_tok))
        flat = sorted(((cost(e), e['tool'], e['file']) for e in tools.values()), reverse=True)
        print('最贵 top8:')
        for t, nm, f in flat[:8]:
            print('  %6.0f tok  %-22s (%s)' % (t, nm, f))
        print('')

    if mode in ('default', 'both'):
        print('=== 默认配置（新用户装上后每轮真实付的钱）===')
        print('功能包 %d 个：默认开 %d / 默认关 %d'
              % (len(pkgs), sum(1 for p in pkgs if p['enabled']), sum(1 for p in pkgs if not p['enabled'])))
        print('默认启用包内工具: %d 个  ->  约 %.0f token/轮' % (len(on), on_tok))
        print('默认关闭包内工具: %d 个  ->  约 %.0f token（不发，只在 disabledCapabilitiesNote 占一行名字）'
              % (len(off), off_tok))
        print('')
        print('默认发送里最贵 top10:')
        for nm, pid, t in sorted(on, key=lambda x: -x[2])[:10]:
            print('  %6.0f tok  %-20s (包 %s)' % (t, nm, pid))
        print('')
        # 按「功能包」聚合：真正能一键省下来的单位是包（用户在设置里开关的就是包），
        # 单个工具再贵也只能改描述，而关掉一个包是整块不发。要拿这份表决定「默认开哪些」。
        by_pkg = {}
        for nm, pid, t in on:
            e = by_pkg.setdefault(pid, [0.0, []])
            e[0] += t; e[1].append(nm)
        print('默认开着的包，按每轮花费排序（关掉一个就整块省下）:')
        for pid, (t, names) in sorted(by_pkg.items(), key=lambda x: -x[1][0]):
            print('  %6.0f tok  %-16s %s' % (t, pid, '、'.join(sorted(names))))
        print('')
        print('未被任何包登记的 Tool 实现（不占 token）: %d 个' % len(orphan))
        if orphan:
            print('  ' + '、'.join(orphan))
            print('  ⚠ 别照着这份名单删代码：2026-07-27 核过，file_* / make_directory 是 file_op 的内部委派，')
            print('    deep_search(XSearchTool) 是 web_search depth=deep 的实现——都是活代码，只是按「工具少而多用」没单独注册。')


if __name__ == '__main__':
    main()
