<div align="center">
  <a href="README.md">中文</a> | <span>English</span>
</div>

<div align="center">
  <img src="https://img.shields.io/badge/license-AGPL--3.0--only-blue.svg" alt="License">
  <img src="https://img.shields.io/badge/Platform-Android_8.0%2B-brightgreen.svg" alt="Platform">
  <img src="https://img.shields.io/badge/tools-76_multi--purpose-orange.svg" alt="Tools">
  <img src="https://img.shields.io/badge/i18n-33_languages-9cf.svg" alt="Languages">
  <br>
  <a href="mailto:tomrz666@qq.com"><img src="https://img.shields.io/badge/📧-Email-red.svg" alt="Email"></a>
  <a href="#-community"><img src="https://img.shields.io/badge/💬-QQ_1063208484-blue.svg" alt="QQ Group"></a>
  <a href="https://t.me/onyxui_project"><img src="https://img.shields.io/badge/✈️-Telegram-2CA5E0.svg" alt="Telegram"></a>
  <a href="https://codeberg.org/OnyxUI"><img src="https://img.shields.io/badge/🌍-OnyxProject-6da55f.svg" alt="OnyxProject"></a>
  <a href="../../issues"><img src="https://img.shields.io/badge/🐛-Issues-orange.svg" alt="Issues"></a>
</div>

<div align="center">
  <h1>Arix — a voice AI assistant for your watch</h1>
  <p>⌚ <b>Raise your wrist, say one sentence. It understands, acts, gets it done. 76 multi-purpose tools — on a watch, and on a phone</b> ⌚</p>
  <p><b>From <a href="https://codeberg.org/OnyxUI">OnyxProject</a> — tied to no custom ROM</b></p>
  <p><sub>OnyxUI is a lightweight Android system for phones and watches, and Arix is its AI assistant<br>
  but installing Arix does <b>not</b> require OnyxUI — any Android 8.0+ device will do</sub></p>
</div>

<div align="center">
  <div style="padding: 10px 0; text-align: center;">
    <img src="docs/assets/screenshots/preview-1.jpg" width="19%" alt="Arix preview 1" style="display: inline-block; border-radius: 8px; box-shadow: 0 5px 15px rgba(0,0,0,0.15); margin: 0 3px; max-width: 200px;">
    <img src="docs/assets/screenshots/preview-2.jpg" width="19%" alt="Arix preview 2" style="display: inline-block; border-radius: 8px; box-shadow: 0 5px 15px rgba(0,0,0,0.15); margin: 0 3px; max-width: 200px;">
    <img src="docs/assets/screenshots/preview-3.jpg" width="19%" alt="Arix preview 3" style="display: inline-block; border-radius: 8px; box-shadow: 0 5px 15px rgba(0,0,0,0.15); margin: 0 3px; max-width: 200px;">
    <img src="docs/assets/screenshots/preview-4.jpg" width="19%" alt="Arix preview 4" style="display: inline-block; border-radius: 8px; box-shadow: 0 5px 15px rgba(0,0,0,0.15); margin: 0 3px; max-width: 200px;">
    <img src="docs/assets/screenshots/preview-5.jpg" width="19%" alt="Arix preview 5" style="display: inline-block; border-radius: 8px; box-shadow: 0 5px 15px rgba(0,0,0,0.15); margin: 0 3px; max-width: 200px;">
  </div>
</div>

---

## 🌟 What it is

**Arix** is not another chat box. It is an Android AI assistant built **voice-first**. It reads your
calendar and notifications, controls playback, runs shell commands, checks the weather, searches the
web and drives other apps. It ships with **long-term memory** and a **memory graph**,
**character cards** (SillyTavern-compatible), **workflows with 22 device-event triggers**, an
**MCP client and server**, and a **standalone full Linux terminal app**.

There are only **76 tool entry points**, but every one of them is **multi-purpose**. That is a
deliberate merge, not unfinished work: the longer and more fragmented the tool list, the more likely
the model picks the wrong tool — one of the most common sources of hallucination.
See [below](#️-features-at-a-glance).

### Where it comes from, and what it isn't tied to

**Arix comes out of [OnyxProject](https://codeberg.org/OnyxUI).**
[OnyxUI](https://codeberg.org/OnyxUI/OnyxUI) is a lightweight Android system for phones and watches,
and Arix is its AI assistant — this project grew up inside that ecosystem.

**But it is tied to no custom ROM.** No flashing, no OnyxUI required: **any Android 8.0+ device can
install it**, stock watches and ordinary phones alike. Born in OnyxUI, not bound to it.

Flashing OnyxUI does unlock one extra layer: as a system app it can hold signature-level permissions,
which is what virtual displays and background automation need (see "capability tiers" in the app).

### Where the name came from

It was going to be called **OnyxAI** — following OnyxUI's name, which was the obvious choice.

Then it turned out **Onyx is someone else's registered trademark**. And this project was about to
start shipping on real devices, where a name becomes permanent the moment it leaves the factory, so
it got changed before that happened: **Arix**.

That's why you'll still find traces of `onyx` in history, old backups, even a local directory name —
that's the same project before the rename, not a different thing. Compatibility was kept on purpose:
backups exported by older builds still restore, with database names and paths mapped automatically.

⚠️ One clarification: **OnyxUI and OnyxProject are not affected** — those are the upstream
ecosystem's own names, unrelated to the trademark issue, so they keep them unchanged.

> ⚠️ **Status: in development, not released.** The main features work, but **most changes have only
> been verified by compilation and unit tests — real-device coverage is low**, and interfaces and
> data formats will still change. Don't rely on it as a daily driver yet.

### Being honest about it

**This is the first project I have ever built.** No team behind me, no formal engineering training,
so a fair amount of it is "it works, leave it for now" — the main app is essentially a monolith,
real-device coverage is thin, and some of the trade-offs probably don't hold up. The shortcomings
are certainly not limited to the ones I can see myself.

**So if you know better, please say so directly.** Architecture, security, performance, Android
platform pitfalls, any code that reads like it was written by an amateur — all of it is welcome.
Open an [issue](../../issues), say it in the community, or email me.

Don't soften it. I'd much rather hear it than keep shipping the mistake. One pitfall pointed out by
someone who has been there is worth more to this project than a week of me guessing on my own.

---

## ⚡ Highlights

<table>
<tr>
<td width="50%">

### ⌚ Built for a watch
Wrist-raise gating plus cascaded wake-up, so it **is not listening to the mic most of the time**.
Custom wake words with multiple recordable templates. Voice is the primary entry point, not an
add-on, and the UI was designed for small square screens from day one — every mA counts.

### 🧠 Memory that accumulates experience
Long-term memory store, force-directed memory graph, vector retrieval, plus an "interaction state
layer" so it still feels like itself in a new conversation.
**Failures settle into lessons**: a denied tool, bad arguments, a truncated reply — all recorded.
Next time, that tool's own description carries "last time this didn't work", so the model sees it
**at the moment it decides whether to call it**.

### 🐧 Standalone terminal app
A full Linux runtime (bash / coreutils / python3 / apt / curl). The main app binds it over a
signature-level permission. Its working directory is the AI's file workspace, so
**you and the AI operate on the same files**.

</td>
<td width="50%">

### 🔐 Privacy treated as a requirement
A dedicated **"private" permission tier**: contacts, SMS bodies, calendar, notifications, clipboard,
app usage, full-screen capture, body sensors, and placing a call directly. All default to asking
every time, and are **never handed to model auto-approval**. Plus **incognito page fetching** —
a separate process with its own cookie jar, carrying none of your logins and leaving no trace.

### 🎭 Character cards & companionship
Card management, world books, SillyTavern import — including alternate greetings, jailbreak
instructions placed after history, and depth-inserted prompts. The companion layer has mood state
and a diary.

### 🔌 Three protocols, extensible
**OpenAI-compatible** (nearly every vendor and relay), **native Anthropic**, **native Gemini**.
MCP client and server, a third-party tool gateway, a JS plugin runtime (compatible with the Operit
package ecosystem). The AI can even **install** skills / sandbox packages / MCP servers itself —
every install goes through your approval.

</td>
</tr>
</table>

---

## 🛠️ Features at a glance

> ### 📌 About "only 76 tools"
>
> That number is **deliberately kept low — not unfinished work**.
>
> The tool table is sent to the model on every single turn, and the longer and more fragmented it is,
> the higher the chance the model **picks the wrong tool**. That is one of the most common sources of
> hallucination: twenty similarly-named tools in front of it, it grabs one that looks right, and
> calls the wrong thing.
>
> So the rule here is **"one tool, many uses"**: a new capability becomes a parameter on an existing
> tool rather than a new tool. Real examples —
>
> - `notification` covers reading notifications / replying directly / pressing their buttons /
>   opening / dismissing / posting one of its own
> - `open_page` covers article text / raw HTML / JSON parsing / images / video / **incognito fetch**
> - `web_search` folds plain search and multi-round deep research into one, not two
> - `bluetooth` covers 12 actions (GATT + SPP) instead of a dozen fine-grained Bluetooth tools
> - `file_op` covers copy / move / delete / archive / extract
>
> In other words: **the number of capabilities is far above 76** — they are just filed under 76
> entry points. Better to have the model read parameters in a short list than guess in a long one.

<details>
<summary><b>📦 Built-in tools (76 entry points — click to expand)</b></summary>

| Category | What it covers |
|---------|---------|
| 🖥️ **System** | File read/write/edit/archive, shell, device control (brightness/volume/torch/WiFi), install & force-stop apps, send intents |
| 🔔 **Notifications & sensing** | Read notifications, reply to them directly, press their buttons, usage stats, screen OCR |
| 🌐 **Network** | HTTP requests, page scraping (**incognito available**), search, multi-round deep research, GitHub, site cookies |
| 📅 **Daily life** | Calendar read/write, contacts, SMS, weather, maps, train tickets, alarms & reminders |
| ❤️ **Health** | **Live measurement** of heart rate / SpO₂ (on-device sensors first, watch as fallback), device status |
| 🎬 **Media** | Music control, TTS, voice cloning, image OCR/crop, document parsing, generate PDF/docx/images |
| 🤖 **Advanced automation** | UI automation, skill record & replay, Home Assistant, Bluetooth (GATT + SPP), sub-agents, workflows |
| 🧩 **Extensions** | MCP gateway (`search_tool` / `use_tool`), plugin creation & install, local search, memory read/write |

</details>

<details>
<summary><b>🗣️ Voice pipeline (click to expand)</b></summary>

- 🎙️ **Custom wake words**, multiple templates, individually enabled
- 🔋 **Wrist-raise gating + cascaded wake-up**: a very cheap first stage, confirmed stage by stage
- 💬 **Live voice conversation**: VAD segments speech automatically, you can interrupt any time
- 🔊 **Auto read-aloud**, optionally dialogue only (skipping action prose and narration)
- 🎧 **Headset-button trigger** — but **while music is playing the button controls music, not us**
- 📴 **Local first**: wake-up and recognition both run on-device, offline capable

</details>

<details>
<summary><b>🧠 Memory & context (click to expand)</b></summary>

- 📚 **Long-term memory**: scored retrieval, recency and conflict resolution, background tidy-up
  (it stops and asks you when unsure)
- 🕸️ **Force-directed memory graph**: backlinks, unlinked mentions, tags, local subgraphs
- 🔍 **Vector semantic index** plus **cross-conversation full-text search** (FTS)
- 🎭 **Interaction state layer**: carries "where we left off / the mood / open questions" into a new chat
- 📉 **Context-window awareness**: estimates what this turn costs and how close the limit is,
  compressing automatically as it approaches
- ✂️ **History length cap**: configurable, and truncation always repairs tool-call pairing afterwards
  (otherwise the endpoint simply rejects the request)

</details>

<details>
<summary><b>🔐 Privacy & permissions (click to expand)</b></summary>

- 🚦 **Three policies**: allow / ask / forbid, **recorded per caller** — allowing a plugin to run
  shell does not allow the AI to
- 🕵️ **Private tier**: tools touching your personal data get their own tier — ask by default,
  never eligible for model auto-approval
- 🗣️ **Approval dialogs speak plainly**: they say what this step is actually trying to do,
  rather than throwing a tool name at you
- 👻 **Incognito fetching**: separate process with its own cookie jar (needs Android 9+;
  when it can't, it **says so instead of pretending**)
- 📦 **Sandbox**: the AI's private workspace cannot be escaped; third-party packages cannot
  shadow built-in tool names
- 🔒 **Encrypted backups**: AES-GCM; results from sensitive tools are never persisted or backed up
- 🗑️ **Uninstall revokes**: removing a plugin clears every grant it accumulated, so a same-named
  package cannot silently inherit them on reinstall

</details>

<details>
<summary><b>🎨 Interface & personalization (click to expand)</b></summary>

- 🌈 **Themes & color**: dynamic color (Monet), color extracted from an image, presets, light/dark
- 💬 **Chat appearance**: bubble shape/color/opacity/tail, avatars, density, split replies by line
- ✨ **Chat effects**, one-tap skins, slash commands
- 🪟 **Floating ball / live capsule / dynamic island**: use it without opening the app
- 📐 **Customizable drawer**: full-screen editor, drag to arrange ~26 entries, three size steps
- 🌍 **33 languages**, 1,927 UI strings
- 📄 **Markdown rendering**: LaTeX, syntax highlighting, tables, Mermaid, embedded SVG/audio/video

</details>

<details>
<summary><b>💾 Data & backup (click to expand)</b></summary>

- 📦 **Full zip backup** plus three channels: **private GitHub repo** / WebDAV / S3
- 🔑 **AES-GCM encryption**, multiple versions kept in the cloud
- 🔄 **Restores data from pre-rename builds**, mapping old database and path names automatically
- 🔐 **Key pool**: rotate multiple keys, health records (cooldown/disable survive restarts), balance query

</details>

---

## 📲 Getting started

### Install

> ✅ **Released on GitHub Releases** ([view](../../releases)), latest stable is **v0.2.2**. New
> versions are also announced in the community links.

Requires **Android 8.0 (API 26) or newer**. Fill in one API key in settings and it works.

**Size**: the release APK is about **42MB**. That **excludes the terminal's Linux environment** —
the terminal is a separate app, and installing it expands roughly 130MB of bootstrap. If you only
use chat and tools, 42MB is all of it.

> ⚠️ **Get it from the official channel.** This project is AGPL-3.0 and anyone may fork it and build
> on it — we encourage that. But renaming it and publishing it as your own original work, or
> stripping attribution, both violates the license and cuts you off from our updates and support.

### Build it yourself

```bash
git clone https://github.com/XTOM0706/arix-app.git
cd arix-app
./gradlew :app:assembleDebug
# output: app/build/outputs/apk/debug/app-debug.apk
```

Requires **JDK 17** and the Android SDK (`compileSdk 36`). The repo ships a pre-commit hook that
compiles before every commit.

**The terminal's Linux environment needs one extra step**: it depends on `bootstrap-<abi>.zip`,
a build artifact of a few hundred MB that exceeds GitHub's single-file limit and is **not in the
repo**. Without it the app runs fine; the terminal just falls back to busybox.
See [TERMUX-BOOTSTRAP-BUILD.md](TERMUX-BOOTSTRAP-BUILD.md).

### Secondary development (skip recompiling libraries)

The 4 Android library modules `app` depends on (`wake` / `cloudapi` / `stt` / `data`) plus the pure-JVM
module `logic` are precompiled — grab **`arix-prebuild-libs-0.2.2.zip`** from the Releases. It contains
their AARs/JAR (including `stt`'s native library).

If you **only touch the `app` module**, use the prebuilt libraries to skip compiling those modules
entirely:

1. Download `arix-prebuild-libs-0.2.2.zip` and extract it into `app/libs/`;
2. In `app/build.gradle.kts`, replace the 5 `implementation(project(":wake"))`-style lines with
   `files()` dependencies on the AARs/JAR;
3. From then on, editing `app` code only triggers incremental compilation — `wake`/`cloudapi`/`stt`/
   `data`/`logic` are no longer rebuilt.

⚠️ If you plan to modify a library module itself (e.g. `stt`'s model-loading logic), don't use the
prebuilt libraries — keep the `project(":...")` dependencies. The prebuilds match the current `app`
version, but changes to a library only take effect when built from source.

---

## 💬 Community

Arix and OnyxUI **share the same channels** — it is a product of that ecosystem, so there is no
point starting a separate one.

| Channel | Where | Notes |
|---|---|---|
| 🐧 **QQ group** | **1063208484** | The main venue, Chinese; both Arix and OnyxUI are discussed here |
| ✈️ **Telegram** | [@onyxui_project](https://t.me/onyxui_project) | Official OnyxProject channel |
| 🌍 **OnyxProject** | [codeberg.org/OnyxUI](https://codeberg.org/OnyxUI) | Upstream ecosystem; the OnyxUI system itself lives here |
| 📧 **Email** | [tomrz666@qq.com](mailto:tomrz666@qq.com) | XTOM, the developer — private matters and collaboration |
| 🐛 **Issues** | [file one](../../issues) | Bugs and requests; please include device model and Android version |

> Release locations will be announced in the **QQ group** and the **Telegram channel**.
> Anything published anywhere else is not from us.

---

## 🤔 Compared to similar projects

Among comparable projects, **Operit AI is a feature superset** — no point being vague about it.
It started earlier, does more, and has far more stars than we do.

**Only one thing genuinely sets us apart: this one is made for a watch.** Wrist-raise gating,
cascaded wake-up, not listening to the mic most of the time, counting every mA — nobody else has to
carry those constraints. We do.

The license differs too: Arix is **pure AGPL-3.0 with no commercial-license gate**. Companies may
use it, teams larger than ten may use it, you may build a commercial product on it — as long as you
honor AGPL (publish your changes, keep attribution).

Want the most features, use Operit. Want a clean chat client, use RikkaHub. Want cross-platform,
use Kelivo. **Want to raise your wrist and talk — that's us.**

> No per-feature / star / license comparison table here. These projects iterate fast, such a table
> is stale within weeks, and getting someone else's license terms wrong is a rude thing to do.
> Go read their repos directly.

---

## 🙏 Credits

Arix did not appear out of nowhere.

<details>
<summary><b>Operit AI and its cloud package authors (click to expand)</b></summary>

**Operit AI (@AAswordman)** walked this path first. Arix borrowed many of its implementation ideas
and is also **compatible with its package ecosystem** — we convert Operit packages into Arix
packages so creators on both sides don't have to redo the work.

Even more thanks go to the authors of **Operit's cloud packages and scripts**. A number of Arix
features were inspired by reading yours:

| Package / script | Author |
|---|---|
| World Book Plus | @HateCandy |
| Memory system | @jbzmm |
| Soundprint | @yuyixuanfu |
| WebDAV backup sync | @Mariomoprc |
| AI voice output | @FrancisVael |
| Message relay | @RaineIris |
| Conversation lock | @ruojie108 |
| MusicFree · JsxposedX reverse-engineering toolkit | @178945123 |
| Linux bridge · Cognitive Crucible Pro | @Karzzzzz520 |
| Sandbox package SDK · WeChat iLink bot | @g1776933879 |
| Long-image OCR enhancement | @yoyowong138 |
| Thought cascade engine | @JIANGZHAOKUN1067517323 |
| Plugin scenario manager · review panel | @maylihaidong |
| Dodo voice | @do-do026 |
| Shizuku launcher | @purelife3 |
| Fast downloader | @Jianyin-Li |
| Smart routing assistant | @laobi465 |
| AgentsMail | @sweetcni9-ui |
| Agnes video generation | @YunXi-Aurora · @drzdtd |
| Fully automated Android dev agent | @camillanapoles |
| Moonshot search tool | @3316891527 |
| Rendering / inspection and other packages | @qtgf520 · @yanjun62 · @fuqun616-eng · @lyn2010526-stack |

> This list comes from public issues in the `OperitPackageMarket` / `OperitScriptMarket` repos, and
> **covers only a recent batch — it is certainly incomplete**. We are not active in the Operit
> community; this was compiled from public records. If we missed you, or you'd rather not be listed,
> open an issue or tell us in the community and we'll fix it immediately.

</details>

**Similar projects**: **RikkaHub** (its restrained, clean interface is our reference point) ·
**Kelivo** (cross-platform shape and interaction details) · **OrangeChat** (companion-layer ideas).

**Open-source foundations**: ONNX Runtime · sherpa-onnx · Silero VAD · microWakeWord ·
TensorFlow Lite · Termux · Jetpack Compose · Kotlin · OkHttp · Coil · JLaTeXMath — the full list
with licenses is in [NOTICE](NOTICE).

**Where borrowing stops**: we borrow **ideas**, not source. `wake/` is a clean-room rewrite and
contains no GPL/LGPL source (see [wake/NOTICE](wake/NOTICE)). Say it out loud when you borrow
someone's idea; honor the license when you take their code. We take both seriously.

---

## 🧩 Why no local LLM

The target hardware is a watch with 4GB RAM and a quad-core Cortex-A55 at 1.2GHz. A quantized small
model fits, but it isn't good enough to deserve the word "assistant", and it drains the battery.

So conversation goes to the cloud and only the voice pipeline stays local — that part is small, fast
and works offline. For offline conversation you can attach an external inference service over MCP.

We speak the **native protocols** (Anthropic / Gemini) rather than wrapping everything in an
OpenAI-compatible layer, because that layer cannot carry what we need: returning thought signatures,
prompt caching, and each vendor's own thinking-budget parameters.

---

## 🏗️ Code layout

431 Kotlin files, about 148k lines. Honestly: **the main app is essentially a monolith** —
`:app` alone is 82% of it.

```
:app               121.5k lines  main app: Compose UI, 76 tools, memory, cards, MCP, plugin runtime
:terminal           16.6k lines  standalone terminal app (proot + full Linux)
:cloudapi            3.3k lines  cloud AI client (three protocols + SSE streaming)
:wake                2.4k lines  wake word (SileroVAD + MFCC + DTW + KWS)
:data                1.7k lines  data layer (Room v22 + DataStore)
:logic               1.3k lines  pure JVM logic (no android.*, unit-testable)
:stt                 1.0k lines  speech recognition
:xposed              0.3k lines  Xposed hooks (currently disabled)
:marketwatch         0.2k lines  competitor watch
```

`:logic` was carved out on purpose, with exactly one admission rule: **import nothing from
`android.*` / `androidx.*`**. The payoff is that its tests run in seconds without a device.
**114 unit tests** currently run across `:logic` and `:app`.

Dependencies are flat: `:app` → `wake` / `cloudapi` / `stt` / `data` / `logic`, plus
`:stt` → `cloudapi`. See [ARCHITECTURE.md](ARCHITECTURE.md).

### One unusual choice: targetSdk pinned at 28

Not laziness. The terminal needs to `execve` binaries inside the app's own data directory, and only
the old SELinux domain (`untrusted_app_28`) permits that. `compileSdk` is still 36, so new APIs are
available as usual — only runtime behavior follows 28, and for a tool-shaped app the difference at
28 is mostly *fewer restrictions*.

---

## 📜 License and derivative work

**AGPL-3.0-only**, with two additional terms under GPL §7: keep attribution, mark modifications.
Full terms in [LICENSE](LICENSE) and [LICENSE.md](LICENSE.md).

| | |
|---|---|
| ✅ | **Use it, change it, build on it freely** |
| ✅ | **Sell character cards, theme packs, plugins** — those are your work, not covered by this license |
| ✅ | **Both the original author and derivative authors may accept donations** |
| ⚠️ | **Publish your changes**, and state "this is based on Arix" and "here is what I changed" |
| ❌ | **Do not rename it and pass it off as your own original work** |

AGPL rather than GPL because of section 13: if you turn a modified version into a network service
for other people, you owe them the source too. That closes exactly the "reskin it and sell it as my
own product" path.

The **Arix** name and icon are not licensed under it — you may distribute modified versions freely,
but **you must rename them**.

---

## 🤝 Contributing

**This is my first project, so feedback at any level is welcome** (see
"[Being honest about it](#being-honest-about-it)" above) — not just bugs, but bad architecture,
amateurish code, platform pitfalls I failed to avoid. Please say it plainly.

When filing an issue, please include **device model, Android version, and reproduction steps**.
For UI problems attach a screenshot — many only appear at particular screen sizes or shapes.

Read [DESIGN.md](DESIGN.md) (design baseline) and [ARCHITECTURE.md](ARCHITECTURE.md) before changing
code. A few hard rules:

- Icons are always Material vector — **no emoji**
- User-facing text always goes through `tr()`, never hardcoded; **prompts meant for the model are
  written in English**
- Font sizes always come from `MaterialTheme.typography`, never hardcoded `sp`
- `compileDebugKotlin` must pass before committing (a pre-commit hook enforces it)
- Adding a database column means **bumping the Room version and writing a migration**

Stack: Kotlin · Jetpack Compose · Material 3 · Room · DataStore · Coroutines · ONNX Runtime ·
TensorFlow Lite

---

<div align="center">

Developed by **XTOM** · [tomrz666@qq.com](mailto:tomrz666@qq.com)

<sub>Arix · from <a href="https://codeberg.org/OnyxUI">OnyxProject</a> · AGPL-3.0-only</sub>

</div>
