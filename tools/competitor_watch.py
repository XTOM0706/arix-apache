#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
competitor_watch.py —— Arix 竞品 & 云端包市场「更新监控」小工具。

做两件事：
  1) 盯 6 款竞品的 GitHub 仓库：新提交(默认分支) + 新 release。
  2) 盯 Operit 云端市场(script/package/skill/mcp 四类)：新上架的包 + 版本更新。

状态存本地 JSON（competitor_watch.state.json），每次跑只报「上次之后的新变化」。
纯标准库，无需 pip。可选 GITHUB_TOKEN 环境变量提高 API 限额。

用法：
  python competitor_watch.py                # 报增量并更新状态
  python competitor_watch.py --no-update    # 只看不记（不改状态）
  python competitor_watch.py --full         # 首次/想看全量近况（忽略状态里的 sha）
  python competitor_watch.py --json         # 机器可读输出（给 AI 消费）
"""
from __future__ import annotations
import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone

# 中文 Windows 控制台默认 GBK，会把 UTF-8 输出显示成乱码 —— 强制 stdout 走 UTF-8
try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

# ---- 竞品仓库（来自 COMPETITIVE.md 的版本快照表） ----
REPOS = [
    {"name": "Operit AI",   "repo": "AAswordman/Operit",     "note": "功能超集 / 云市场母体"},
    {"name": "RikkaHub",    "repo": "rikkahub/rikkahub",     "note": "聊天客户端标杆"},
    {"name": "Kelivo",      "repo": "Chevey339/kelivo",      "note": "Flutter 跨平台"},
    {"name": "Hermes",      "repo": "SelectXn00b/HermesApp", "note": "Operit 外壳 + Agent Loop"},
    {"name": "橘瓣 OrangeChat", "repo": "sue1231513/orangechat", "note": "RikkaHub fork + 陪伴层（最贴合我们）"},
    {"name": "Eta",         "repo": "Mangi-11/Eta",         "note": "Xposed 系统级 Agent（劫持小布壳+电源键接管转 Google/息屏续 Hey Google 热词）+ 终端/GUI 双驱动(全走 root shell,非无障碍)+ 离屏 agent 浏览器 + Anthropic式 Skills；要 root",
     "edge": {
         "adopted": ["工具参数执行前按 schema 预校验（ToolArgValidator，宽容标量）",
                     "finish_reason=length 截断保护（StreamResult.finishReason + ChatScreen 工具循环）"],
         "todo": ["ColorOS/HyperOS root 用户电源键唤起 Arix（Xposed 增强层）"],
         "skip": ["寄生小布/Google 生态——违独立性铁律；唤醒自研不抄"],
     }},
]

# ---- Operit 云端市场（真源，见 OperitCompat.kt CloudMarketplace） ----
MARKET_BASE = "https://static.operit.app/market-stats"
MARKET_TYPES = ["script", "package", "skill", "mcp"]

STATE_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "competitor_watch.state.json")
UA = "Arix-CompetitorWatch/1.0 (+https://github.com/)"


def http_get_json(url: str, token: str | None = None, timeout: int = 15):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "application/json"})
    if token and "api.github.com" in url:
        req.add_header("Authorization", f"Bearer {token}")
        req.add_header("X-GitHub-Api-Version", "2022-11-28")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return json.loads(r.read().decode("utf-8")), None
    except urllib.error.HTTPError as e:
        if e.code == 403 and "api.github.com" in url:
            reset = e.headers.get("X-RateLimit-Reset")
            hint = ""
            if reset:
                try:
                    hint = f"（限额重置于 {datetime.fromtimestamp(int(reset)).strftime('%H:%M:%S')}，设 GITHUB_TOKEN 可提额）"
                except Exception:
                    pass
            return None, f"GitHub 限流 403{hint}"
        if e.code == 404:
            return None, "404 未找到（仓库可能改名/私有）"
        return None, f"HTTP {e.code}"
    except Exception as e:
        return None, str(e)


def load_state() -> dict:
    try:
        with open(STATE_FILE, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return {}


def save_state(state: dict):
    state["_updated_at"] = datetime.now(timezone.utc).isoformat()
    with open(STATE_FILE, "w", encoding="utf-8") as f:
        json.dump(state, f, ensure_ascii=False, indent=2)


def check_repo(entry: dict, state: dict, token: str | None, full: bool) -> dict:
    """返回该仓库的增量：new_commits / new_release。"""
    repo = entry["repo"]
    key = f"repo:{repo}"
    prev = state.get(key, {})
    result = {"name": entry["name"], "repo": repo, "note": entry["note"],
              "edge": entry.get("edge"),   # 我方对策（加强点）：已吸收/待做/不抄，渲染成卡片里的独立区块
              "new_commits": [], "new_release": None, "error": None}

    commits, err = http_get_json(f"https://api.github.com/repos/{repo}/commits?per_page=15", token)
    if err:
        result["error"] = err
        return result
    if isinstance(commits, list) and commits:
        # 近期提交快照（仪表盘常显，与增量无关）
        result["recent_commits"] = [{
            "sha": (c.get("sha") or "")[:7],
            "msg": (c.get("commit", {}).get("message") or "").splitlines()[0].strip(),
            "date": c.get("commit", {}).get("author", {}).get("date", "")[:10],
        } for c in commits[:8]]
        last_sha = prev.get("last_sha")
        new = []
        for c in commits:
            sha = c.get("sha")
            if sha == last_sha:
                break
            commit = c.get("commit", {})
            msg = (commit.get("message") or "").splitlines()[0].strip()
            date = commit.get("author", {}).get("date", "")[:10]
            new.append({"sha": (sha or "")[:7], "msg": msg, "date": date})
        # full 模式或首见：不回溯整段历史，只展示最近几条作基线
        if last_sha is None or full:
            result["new_commits"] = new[:5] if not full else new
            result["_baseline"] = last_sha is None
        else:
            result["new_commits"] = new
        state.setdefault(key, {})["last_sha"] = commits[0].get("sha")

    rel, rerr = http_get_json(f"https://api.github.com/repos/{repo}/releases/latest", token)
    if rel and not rerr:
        tag = rel.get("tag_name")
        # 当前最新版本（仪表盘常显，与「是否新增」无关）
        result["latest_release"] = {
            "tag": tag, "name": rel.get("name") or tag,
            "date": (rel.get("published_at") or "")[:10], "url": rel.get("html_url", ""),
        }
        if tag and tag != prev.get("last_release"):
            body = (rel.get("body") or "").strip().replace("\r\n", "\n")
            result["new_release"] = {
                "tag": tag, "name": rel.get("name") or tag,
                "date": (rel.get("published_at") or "")[:10],
                "body": body[:400] + ("…" if len(body) > 400 else ""),
                "url": rel.get("html_url", ""),
                "baseline": prev.get("last_release") is None,
            }
        state.setdefault(key, {})["last_release"] = tag
    return result


def check_market(state: dict, full: bool) -> dict:
    """轮询 Operit 市场四类，报新上架 + 版本更新。"""
    key = "market:operit"
    prev = state.get(key, {})           # id -> version
    seen = {}                            # 本次抓到的 id -> {name,type,version,downloads,url}
    errors = []
    for t in MARKET_TYPES:
        # 只看下载榜第 1 页足够发现新包/热包；市场按 downloads 排序
        data, err = http_get_json(f"{MARKET_BASE}/rank/{t}-downloads-page-1.json")
        if err:
            errors.append(f"{t}: {err}")
            continue
        for it in (data or {}).get("items", []):
            meta = it.get("metadata") or {}
            iid = it.get("id") or meta.get("projectId") or ""
            if not iid:
                continue
            seen[iid] = {
                "name": it.get("displayTitle") or iid,
                "type": meta.get("type") or t,
                "version": meta.get("version") or "latest",
                "downloads": it.get("downloads", 0),
                "author": it.get("authorLogin", ""),
                "url": (it.get("issue") or {}).get("html_url", ""),
            }
    new_items, updated = [], []
    baseline = not prev
    for iid, info in seen.items():
        if iid not in prev:
            if not baseline:
                new_items.append(info)
        elif prev[iid] != info["version"]:
            updated.append({**info, "old_version": prev[iid]})
    # 更新状态：id -> version
    state[key] = {iid: info["version"] for iid, info in seen.items()}
    # 基线首见时也把状态存下，但不刷屏
    if full and baseline:
        new_items = sorted(seen.values(), key=lambda x: -x.get("downloads", 0))[:10]
    top = sorted(seen.values(), key=lambda x: -x.get("downloads", 0))[:12]   # 仪表盘：热门包榜
    return {"new_items": new_items, "updated": updated, "errors": errors,
            "baseline": baseline, "total_seen": len(seen), "top": top}


def fetch_all_packages(token: str | None = None, max_pages: int = 60) -> dict:
    """翻遍 Operit 市场四类的**所有分页**，抓全量包（含完整说明 summaryDescription）。
    返回 {type: [ {name,type,version,downloads,author,desc,url,featured}, ... ]}，各类按下载量降序。"""
    out = {t: [] for t in MARKET_TYPES}
    errors = []
    for t in MARKET_TYPES:
        page, total_pages = 1, 1
        seen_ids = set()
        while page <= min(max_pages, total_pages):
            data, err = http_get_json(f"{MARKET_BASE}/rank/{t}-downloads-page-{page}.json", token)
            if err:
                errors.append(f"{t} p{page}: {err}")
                break
            total_pages = (data or {}).get("totalPages", 1)
            items = (data or {}).get("items", [])
            if not items:
                break
            for it in items:
                meta = it.get("metadata") or {}
                iid = it.get("id") or meta.get("projectId") or ""
                if iid in seen_ids:
                    continue
                seen_ids.add(iid)
                out[t].append({
                    "name": it.get("displayTitle") or iid,
                    "type": meta.get("type") or t,
                    "version": meta.get("version") or "latest",
                    "downloads": it.get("downloads", 0),
                    "author": it.get("authorLogin", "") or meta.get("publisherLogin", ""),
                    "desc": (it.get("summaryDescription") or "").strip(),
                    "url": (it.get("issue") or {}).get("html_url", ""),
                    "featured": it.get("featured", False),
                })
            page += 1
            time.sleep(0.25)   # 轻限速
        out[t].sort(key=lambda x: -x.get("downloads", 0))
    out["_errors"] = errors
    out["_total"] = sum(len(v) for k, v in out.items() if k in MARKET_TYPES)
    return out


def render_text(repo_results: list, market: dict, baseline_run: bool) -> str:
    L = []
    ts = datetime.now().strftime("%Y-%m-%d %H:%M")
    L.append(f"# Arix 竞品 & 市场监控 · {ts}")
    if baseline_run:
        L.append("（首次运行：已建立基线，本次不回溯历史。之后每次只报新增。）")
    L.append("")
    L.append("## 竞品仓库")
    any_repo = False
    for r in repo_results:
        head = f"### {r['name']}  ({r['repo']})  —— {r['note']}"
        lines = []
        if r["error"]:
            lines.append(f"  ⚠️ {r['error']}")
        rel = r.get("new_release")
        if rel and not rel.get("baseline"):
            lines.append(f"  🚀 新版本 {rel['tag']} · {rel['date']} — {rel['name']}")
            if rel["body"]:
                for bl in rel["body"].splitlines()[:6]:
                    if bl.strip():
                        lines.append(f"       {bl.strip()}")
            if rel["url"]:
                lines.append(f"       {rel['url']}")
        commits = r.get("new_commits", [])
        if commits and not r.get("_baseline"):
            lines.append(f"  📝 {len(commits)} 条新提交：")
            for c in commits[:12]:
                lines.append(f"       [{c['date']}] {c['msg']}")
            if len(commits) > 12:
                lines.append(f"       …还有 {len(commits) - 12} 条")
        elif r.get("_baseline") and commits:
            lines.append(f"  · 基线：最近提交 [{commits[0]['date']}] {commits[0]['msg']}")
        if lines:
            any_repo = True
            L.append(head)
            L.extend(lines)
            L.append("")
    if not any_repo:
        L.append("（无新提交/新版本）\n")

    L.append("## Operit 云端市场")
    if market["errors"]:
        L.append("  ⚠️ " + "；".join(market["errors"]))
    if market["baseline"]:
        L.append(f"  · 基线已建立（当前收录 {market['total_seen']} 个包）。")
        for it in market.get("new_items", []):
            L.append(f"       [{it['type']}] {it['name']}  {it['version']}  ↓{it['downloads']}  @{it['author']}")
    else:
        if market["new_items"]:
            L.append(f"  ✨ 新上架 {len(market['new_items'])} 个：")
            for it in market["new_items"]:
                L.append(f"       [{it['type']}] {it['name']}  {it['version']}  ↓{it['downloads']}  @{it['author']}  {it['url']}")
        if market["updated"]:
            L.append(f"  ⬆️ 版本更新 {len(market['updated'])} 个：")
            for it in market["updated"]:
                L.append(f"       [{it['type']}] {it['name']}  {it['old_version']} → {it['version']}  {it['url']}")
        if not market["new_items"] and not market["updated"]:
            L.append("  （无新包/无版本变化）")
    return "\n".join(L)


def _esc(s) -> str:
    return (str(s or "")).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace('"', "&quot;")


REPORT_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "competitor_report.html")


def render_html(repo_results: list, market: dict, all_packages: dict | None = None) -> str:
    ts = datetime.now().strftime("%Y-%m-%d %H:%M")
    cards = []
    for r in repo_results:
        badges = []
        rel = r.get("new_release")
        if rel and not rel.get("baseline"):
            badges.append('<span class="badge new">🚀 新版本</span>')
        new_commits = r.get("new_commits", [])
        if new_commits and not r.get("_baseline"):
            badges.append(f'<span class="badge upd">📝 {len(new_commits)} 新提交</span>')
        commits = r.get("recent_commits", [])   # 仪表盘常显近期提交
        latest = r.get("latest_release")
        rel_html = ""
        if latest and latest.get("tag"):
            rel_html = (f'<div class="rel">最新版本 <a href="{_esc(latest["url"])}" target="_blank">'
                        f'{_esc(latest["tag"])}</a> · {_esc(latest["date"])}</div>')
        elif r.get("error"):
            rel_html = f'<div class="err">⚠️ {_esc(r["error"])}</div>'
        commit_rows = "".join(
            f'<li><span class="d">{_esc(c["date"])}</span> {_esc(c["msg"])}</li>'
            for c in commits[:12]
        )
        commit_html = f'<ul class="commits">{commit_rows}</ul>' if commit_rows else '<div class="muted">近期无新提交</div>'
        # 我方对策（加强点）：有 edge 才渲染，做成卡片里的独立彩色区块，不再挤在灰色 note 里
        edge_html = ""
        edge = r.get("edge")
        if edge:
            rows = []
            for label, cls, items in (("✅ 已吸收", "adopted", edge.get("adopted")),
                                      ("🔧 待做", "todo", edge.get("todo")),
                                      ("🚫 不抄", "skip", edge.get("skip"))):
                if not items:
                    continue
                lis = "".join(f"<li>{_esc(x)}</li>" for x in items)
                rows.append(f'<div class="er er-{cls}"><span class="el">{label}</span><ul>{lis}</ul></div>')
            if rows:
                edge_html = f'<div class="edge"><div class="etitle">🛡 我方对策</div>{"".join(rows)}</div>'
        cards.append(f'''
        <div class="card">
          <div class="chead">
            <a class="cname" href="https://github.com/{_esc(r["repo"])}" target="_blank">{_esc(r["name"])}</a>
            {"".join(badges)}
          </div>
          <div class="note">{_esc(r["note"])} · <span class="repo">{_esc(r["repo"])}</span></div>
          {edge_html}
          {rel_html}
          {commit_html}
        </div>''')

    def market_rows(items, kind):
        out = []
        for it in items:
            extra = ""
            if kind == "upd":
                extra = f' <span class="d">{_esc(it.get("old_version"))} → {_esc(it["version"])}</span>'
            else:
                extra = f' <span class="d">{_esc(it["version"])}</span>'
            dl = f'<span class="dl">↓{it.get("downloads", 0)}</span>'
            name = f'<a href="{_esc(it["url"])}" target="_blank">{_esc(it["name"])}</a>' if it.get("url") else _esc(it["name"])
            out.append(f'<li><span class="tag t-{_esc(it["type"])}">{_esc(it["type"])}</span> {name}{extra} {dl} <span class="muted">@{_esc(it.get("author"))}</span></li>')
        return "".join(out)

    m_sections = []
    if market.get("new_items"):
        m_sections.append(f'<h3>✨ 新上架 {len(market["new_items"])}</h3><ul class="mkt">{market_rows(market["new_items"], "new")}</ul>')
    if market.get("updated"):
        m_sections.append(f'<h3>⬆️ 版本更新 {len(market["updated"])}</h3><ul class="mkt">{market_rows(market["updated"], "upd")}</ul>')
    m_sections.append(f'<h3>🔥 热门包 Top {len(market.get("top", []))}（收录 {market.get("total_seen", 0)}）</h3><ul class="mkt">{market_rows(market.get("top", []), "top")}</ul>')
    if market.get("errors"):
        m_sections.append(f'<div class="err">⚠️ {_esc("；".join(market["errors"]))}</div>')

    # 全部云端包（可搜索、带完整说明）
    browser = ""
    if all_packages:
        groups = []
        for t in MARKET_TYPES:
            pkgs = all_packages.get(t, [])
            if not pkgs:
                continue
            rows = []
            for it in pkgs:
                s = f'{it["name"]} {it.get("desc","")} {it.get("author","")}'.lower()
                star = "⭐ " if it.get("featured") else ""
                name = f'<a href="{_esc(it["url"])}" target="_blank">{_esc(it["name"])}</a>' if it.get("url") else _esc(it["name"])
                desc = f'<div class="pk-d">{_esc(it["desc"])}</div>' if it.get("desc") else ""
                rows.append(f'''<div class="pkg" data-s="{_esc(s)}">
                  <div class="pk-h"><span class="tag t-{_esc(it["type"])}">{_esc(it["type"])}</span> {star}{name}
                    <span class="v">{_esc(it["version"])}</span> <span class="dl">↓{it.get("downloads",0)}</span>
                    <span class="muted">@{_esc(it.get("author"))}</span></div>{desc}</div>''')
            groups.append(f'<div class="pgroup"><h3>{t} · {len(pkgs)}</h3>{"".join(rows)}</div>')
        errline = f'<div class="err">⚠️ {_esc("；".join(all_packages.get("_errors", [])))}</div>' if all_packages.get("_errors") else ""
        browser = f'''<h2>全部云端包 · <span id="cnt">{all_packages.get("_total", 0)}</span>
          <input id="q" placeholder="搜索 包名 / 说明 / 作者…" oninput="filt()"></h2>{errline}
          <div id="pkgs">{"".join(groups)}</div>
          <script>function filt(){{var q=document.getElementById('q').value.toLowerCase();var n=0;
          document.querySelectorAll('.pkg').forEach(function(e){{var m=e.getAttribute('data-s').indexOf(q)>=0;
          e.style.display=m?'':'none';if(m)n++;}});document.getElementById('cnt').textContent=n;
          document.querySelectorAll('.pgroup').forEach(function(g){{var any=g.querySelectorAll('.pkg:not([style*="none"])').length;g.style.display=any?'':'none';}});}}</script>'''

    return f'''<!doctype html><html lang="zh"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Arix 竞品监控</title>
<style>
:root{{--bg:#f6f7f9;--fg:#1b1c1e;--mut:#6b7280;--card:#fff;--line:#e5e7eb;--acc:#2563eb;--new:#16a34a;--upd:#d97706}}
@media(prefers-color-scheme:dark){{:root{{--bg:#0f1113;--fg:#e6e7ea;--mut:#9aa0a6;--card:#191c1f;--line:#2a2e33;--acc:#7aa2ff;--new:#4ade80;--upd:#fbbf24}}}}
*{{box-sizing:border-box}}body{{margin:0;background:var(--bg);color:var(--fg);font:15px/1.5 -apple-system,Segoe UI,Roboto,"Microsoft YaHei",sans-serif;padding:20px}}
h1{{font-size:20px;margin:0 0 2px}}.sub{{color:var(--mut);font-size:13px;margin-bottom:18px}}
h2{{font-size:16px;margin:22px 0 10px;border-bottom:1px solid var(--line);padding-bottom:6px}}
h3{{font-size:14px;margin:14px 0 6px;color:var(--mut)}}
.grid{{display:grid;grid-template-columns:repeat(auto-fill,minmax(320px,1fr));gap:14px}}
.card{{background:var(--card);border:1px solid var(--line);border-radius:14px;padding:14px}}
.chead{{display:flex;align-items:center;gap:8px;flex-wrap:wrap}}
.cname{{font-weight:700;font-size:16px;color:var(--fg);text-decoration:none}}.cname:hover{{color:var(--acc)}}
.note{{color:var(--mut);font-size:12px;margin:3px 0 8px}}.repo{{font-family:ui-monospace,Consolas,monospace}}
.rel{{font-size:13px;margin-bottom:8px}}.rel a{{color:var(--acc);text-decoration:none}}
.edge{{border:1px solid var(--line);border-radius:10px;padding:8px 10px;margin:0 0 10px;background:color-mix(in srgb,var(--acc) 6%,transparent)}}
.edge .etitle{{font-size:12px;font-weight:700;margin-bottom:5px}}
.er{{display:flex;gap:6px;font-size:12px;margin:3px 0}}
.er .el{{flex:0 0 auto;font-weight:600;white-space:nowrap}}
.er ul{{margin:0;padding:0;list-style:none}}.er li{{margin:1px 0;color:var(--fg)}}
.er-adopted .el{{color:var(--new)}}.er-todo .el{{color:var(--upd)}}.er-skip .el{{color:#ef4444}}
.badge{{font-size:11px;padding:2px 8px;border-radius:20px;font-weight:600}}
.badge.new{{background:color-mix(in srgb,var(--new) 20%,transparent);color:var(--new)}}
.badge.upd{{background:color-mix(in srgb,var(--upd) 20%,transparent);color:var(--upd)}}
ul{{margin:0;padding:0;list-style:none}}
.commits li{{font-size:12.5px;padding:3px 0;border-top:1px solid var(--line)}}
.commits .d,.mkt .d{{color:var(--mut);font-family:ui-monospace,Consolas,monospace;font-size:11.5px;margin-right:6px}}
.mkt li{{padding:5px 0;border-top:1px solid var(--line);font-size:13px}}.mkt a{{color:var(--acc);text-decoration:none}}
.tag{{font-size:10.5px;padding:1px 7px;border-radius:6px;background:var(--line);color:var(--mut);margin-right:4px}}
.dl{{color:var(--mut);font-size:11.5px}}.muted{{color:var(--mut)}}.err{{color:#ef4444;font-size:12.5px}}
.mkt{{background:var(--card);border:1px solid var(--line);border-radius:14px;padding:6px 14px}}
h2 #q{{font:13px/1 inherit;margin-left:10px;padding:5px 10px;border:1px solid var(--line);border-radius:20px;background:var(--card);color:var(--fg);width:min(320px,50vw)}}
.pgroup{{margin-bottom:14px}}.pgroup h3{{position:sticky;top:0;background:var(--bg);padding:6px 0}}
.pkg{{background:var(--card);border:1px solid var(--line);border-radius:12px;padding:9px 12px;margin-bottom:7px}}
.pk-h{{font-size:13.5px}}.pk-h a{{color:var(--acc);text-decoration:none;font-weight:600}}
.pk-h .v{{font-family:ui-monospace,Consolas,monospace;font-size:11px;color:var(--mut);margin:0 4px}}
.pk-d{{color:var(--mut);font-size:12.5px;margin-top:4px;white-space:pre-wrap}}
.t-script{{background:color-mix(in srgb,var(--acc) 18%,transparent);color:var(--acc)}}
.t-package{{background:color-mix(in srgb,var(--new) 18%,transparent);color:var(--new)}}
.t-skill{{background:color-mix(in srgb,var(--upd) 18%,transparent);color:var(--upd)}}
.t-mcp{{background:color-mix(in srgb,#a855f7 20%,transparent);color:#a855f7}}
</style></head><body>
<h1>Arix · 竞品 &amp; 市场监控</h1>
<div class="sub">生成于 {ts} · 数据来自 GitHub API 与 Operit 市场 · 双击本文件随时刷新查看</div>
<h2>竞品仓库（{len(repo_results)}）</h2>
<div class="grid">{"".join(cards)}</div>
<h2>Operit 云端市场</h2>
{"".join(m_sections)}
{browser}
</body></html>'''


def main():
    ap = argparse.ArgumentParser(description="Arix 竞品 & 云端市场更新监控")
    ap.add_argument("--html", action="store_true", help="生成可视化 HTML 仪表盘并在浏览器打开（不改状态）")
    ap.add_argument("--no-update", action="store_true", help="只看不记（不写状态文件）")
    ap.add_argument("--full", action="store_true", help="展示最近全量近况（忽略状态回溯上限）")
    ap.add_argument("--json", action="store_true", help="输出 JSON（给 AI/脚本消费）")
    ap.add_argument("--token", default=os.environ.get("GITHUB_TOKEN"), help="GitHub token（默认取 GITHUB_TOKEN）")
    args = ap.parse_args()

    state = load_state()
    baseline_run = not state
    # 仪表盘要看「近期活动」全貌，强制 full；且不写状态(避免把增量基线吃掉)
    full = args.full or args.html
    repo_results = []
    for e in REPOS:
        repo_results.append(check_repo(e, state, args.token, full))
        time.sleep(0.3)  # 轻微限速，别触发 GitHub secondary rate limit
    market = check_market(state, full)

    if args.html:
        all_packages = fetch_all_packages(args.token)   # 翻遍全部分页，抓全量包+完整说明
        html = render_html(repo_results, market, all_packages)
        with open(REPORT_FILE, "w", encoding="utf-8", newline="") as f:
            f.write(html)
        print(f"已生成仪表盘：{REPORT_FILE}")
        try:
            import webbrowser
            webbrowser.open("file:///" + REPORT_FILE.replace("\\", "/"))
        except Exception:
            pass
        return   # 仪表盘为只读视图，不写状态

    if args.json:
        out = {"generated_at": datetime.now(timezone.utc).isoformat(),
               "baseline_run": baseline_run, "repos": repo_results, "market": market}
        print(json.dumps(out, ensure_ascii=False, indent=2))
    else:
        print(render_text(repo_results, market, baseline_run))

    if not args.no_update:
        save_state(state)


if __name__ == "__main__":
    main()
