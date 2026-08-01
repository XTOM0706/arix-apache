# 实时胶囊：HyperOS 超级岛 + ColorOS 流体云（免 Xposed 落地规格）

> 研究快照 2026-07-21（来自 HyperIsland-ToolKit 反编译 + 阿里云 EMAS 官方 + Android 16 官方 + FluidCloudExtension）。
> **这是「阶段 A（免 Xposed）」的构建规格**，真机验证前别对用户打包票。按键唤醒/CO14-15 流体云属阶段 B（需 Xposed），见 TODO-TERMINAL-APP.md 之外的 Xposed 规划。

## 结论先行
- **HyperOS 2/3 超级岛：可免 Xposed**（普通 ongoing 通知 + `miui.focus.*` extras）。**最大风险=非白名单 App 在锁 bl 正式版大概率被系统拦，出不了岛**（HyperBridge issue #161）。真机验证第一位。
- **ColorOS 16 / Android 16 流体云：可免 Xposed**（直接吃 Google Live Updates：`Notification.ProgressStyle` + `setRequestPromotedOngoing(true)`）。风险=OPPO 可能二次放行。
- **ColorOS 14/15 流体云：无免 Xposed 路**（Pantanal 服务卡，第三方进不去）→ 阶段 A 降级普通通知，留阶段 B。
- **别用自定义 RemoteViews**：`miui.focus.rv`/`param.custom` 在 HyperOS 3.1 已失效；只用 `miui.focus.param` 标准模板。

## HyperOS 超级岛（miui.focus）
投递：普通通知 `setOngoing(true)` → `addExtras(picsBundle)` → `notification.extras.putString("miui.focus.param", json)` → `notify(id, n)`。更新=同 id 重 notify；结束=`param_v2.cancel=true` 或 cancel。仅需 `POST_NOTIFICATIONS`（能否出岛取决于系统白名单，非 App 权限）。

extras key：`miui.focus.param`(String JSON,主)、`miui.focus.pics`(Bundle，内 `miui.focus.pic_<name>`=`Icon` Parcelable)、`miui.focus.ticker`、`miui.focus.actions`(`miui.focus.action_<name>`)、`android.template`。图片：本地 `Icon.createWithBitmap/Resource`；云端 `pic` 填 HTTPS(≤100KB/1:1~16:9/≤10张)。

`miui.focus.param` 根=`param_v2`，protocol=**3**（HyperOS 2/3；EMAS 老文档是 protocol 1 蛇形字段，别混）。**建议直接引 `io.github.d4viddf:hyperisland_kit`（Kotlin DSL）序列化，别手拼 protocol-3 字段（`paramIsland` 驼峰 vs `param_island` 蛇形是唯一需真机确认的歧义点）**。
关键子结构：`baseInfo`(展开卡 title/content/配色)、`progressInfo`(progress + colorProgress)、`paramIsland`{`smallIslandArea`(picInfo 小岛)、`bigIslandArea`(imageTextInfoLeft/textInfo/progressTextInfo)}、`TimerInfo`(系统自走秒)、`PicInfo`(loop/autoplay/number 帧动画 + effectColor 流光)。

可用 JSON 示例（小岛图标+短文本、大岛标题+进度）：
```json
{"param_v2":{"protocol":3,"business":"xtom_assistant","ticker":"Arix 正在听…",
"enableFloat":true,"updatable":true,
"baseInfo":{"type":1,"title":"Arix","content":"答案来了","colorTitle":"#FFFFFF","colorContent":"#B0FFFFFF"},
"progressInfo":{"progress":60,"colorProgress":"#7C5CFF","colorProgressEnd":"#00E0FF"},
"paramIsland":{"islandProperty":1,
 "smallIslandArea":{"picInfo":{"type":1,"pic":"icon","contentDescription":"listening"}},
 "bigIslandArea":{"imageTextInfoLeft":{"picInfo":{"type":1,"pic":"icon"}},"textInfo":{"title":"Arix","content":"答案来了"},"progressTextInfo":{"progress":60}}}}}
```
（`pic:"icon"` 对应 `miui.focus.pics` 里的 `miui.focus.pic_icon`=Icon）

## ColorOS 16 流体云（Google Live Updates）
确切 API（Android 16/API36，已核实非编造）：`Notification.ProgressStyle`（+`Segment(duration).setColor` / `Point(position)`）、`NotificationCompat.Builder.setRequestPromotedOngoing(true)`（等价 extra `Notification.EXTRA_REQUEST_PROMOTED_ONGOING`）、权限 `POST_PROMOTED_NOTIFICATIONS`（+`POST_NOTIFICATIONS`）、gate `notificationManager.canPostPromotedNotifications()` / `notification.hasPromotableCharacteristics()`。硬约束：`setOngoing(true)`+有 contentTitle+style∈{标准/BigText/Call/ProgressStyle/Metric}+无自定义 RV+渠道非 IMPORTANCE_MIN。
```kotlin
val progress = Notification.ProgressStyle().setStyledByProgress(false).setProgress(60)
  .setProgressTrackerIcon(Icon.createWithResource(ctx, R.drawable.ic_xtom))
  .setProgressSegments(listOf(
     Notification.ProgressStyle.Segment(60).setColor(Color.parseColor("#7C5CFF")),
     Notification.ProgressStyle.Segment(40).setColor(Color.parseColor("#22FFFFFF"))))
val n = NotificationCompat.Builder(ctx, CH).setSmallIcon(R.drawable.ic_xtom)
  .setContentTitle("Arix").setContentText("答案来了").setOngoing(true)
  .setRequestPromotedOngoing(true).setStyle(progress).build()
```
Manifest：`<uses-permission android:name="android.permission.POST_PROMOTED_NOTIFICATIONS"/>`。

## ColorOS 14/15（阶段 B · 需 Xposed）
无 miui.focus 类通道。hook 目标（confirmed 类名，来自 FluidCloudExtension，进程 `com.android.systemui`）：`com.oplus.systemui.statusbar.seeding.SeedlingPluginManager`（+ `$holeRectListener$1.onRectChanged`）。内容注入的渲染类名未确认，需 dump SystemUI。底座=Pantanal(`com.oplus.pantanal.ums`/`com.coloros.sceneservice`)；`com.oplus.permission.safe.ASSISTANT` 是签名级第三方拿不到。官方 SDK `com.oplus.pantanal.card:seedling-support-external:3.0.7` 存在但要平台准入（未确认能否个人免审直用）。

## 阶段 A 落地清单（:app，免 Xposed）
- 抽象 `LiveCapsuleController`：统一入参(title/状态文本/progress 0-100/icon bitmap/business)，内部按 ROM 分支；更新=同 id 重 notify，结束=cancel。
- HyperOS 分支 `HyperFocusBuilder`：出 `miui.focus.param` JSON（引 hyperisland_kit）+ `miui.focus.pics`(本地 Icon)。只用标准模板。
- ColorOS16 分支 `ProgressStyleBuilder`：`ProgressStyle`+`setRequestPromotedOngoing`，运行时 `canPostPromotedNotifications()` gate；CO14/15 降级普通 ongoing 通知。
- ROM 探测：`Build.MANUFACTURER` + `ro.mi.os.version.*`/`ro.build.version.oplusrom`（属性名需真机确认）。
- **真机验证四点（别打包票）**：①HyperOS 非白名单能否出岛 ②HyperOS2 vs 3 的 paramIsland 字段命名 ③CO16 promoted 是否被 OPPO 二次拦 ④CO16 ProgressStyle 实际呈现。

参考：`D4vidDf/HyperIsland-ToolKit`、`D4vidDf/HyperBridge`#161、`mouzuan/FluidCloudExtension`、Android16 progress-centric-notifications 官方文档、阿里云 EMAS Xiaomi Super Island 文档、`com.oplus.pantanal.card:seedling-support-external`。
