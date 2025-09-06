## 2025-09-06｜流媒体 Seek 防误跳、FLAC 原生优先与音频类型徽标、回退 320kbps、歌单离线合并与自动/手动同步、
(TechChangeSpec_2025-09-06-Seek-FLAC-AudioBadge-Playlists)

### 背景与目
- 背景：
  - 手表场景下，用户拖动进度条或歌词行跳转时，偶发“直接回到开头”且继续从头播放；日志显示 `dur=TIME_UNSET` 与不可寻址（not seekable）的情况频发，尤其在服务器端强制转码的流媒体上；
  - 服务器端 FLAC 曲目“能播/不能 Seek/偶发回退”混杂，用户希望尽量原生播放 FLAC，并在失败时稳定回退；
  - 需要增加“显示音频类型（FLAC/MP3）”的开关与 UI 徽标，且不与长标题冲突；全屏标题需要无限滚动（跑马灯）。
- 目标：
  - 从“是否可 Seek（isSeekable）”维度改造 UI 放行逻辑，彻底杜绝“误跳回 0”；
  - FLAC 原生优先播放，失败时自动回退为服务器转码 MP3，且将回退码率由 192kbps 提升到 320kbps；
  - 设置页新增“显示音频类型”开关；全屏标题右侧显示方框徽标（FLAC/MP3），并修复全屏标题的跑马灯。

---

### 问题回溯与根因分析
1) 拖动/歌词跳转“跳到开头”且停在 0
- 根因：当底层流“不可寻址”或“时长未就绪（`TIME_UNSET`）”时，UI 仍基于兜底时长放行 `seekTo(...)`；ExoPlayer 在不可 Seek 时会夹到 0，表现为“跳到开头并从头播”。
- 特征：本地文件基本不复现；流媒体（尤其强制转码）高发；等待 1–2 秒无效，因为服务端实时转码流可能自始至终不可 Seek。

2) FLAC 播放不稳/不播
- 根因：历史实现曾倾向“FLAC 一开始就改为 MP3 转码 URL”，或依赖服务器 `Content-Type`/URL 嗅探，导致原生 FLAC 识别与解码路径不稳定；
- 现象：本地 FLAC 在其他播放器能播，服务器端 FLAC 在本项目偶发失败并回退。

3) 音频类型徽标不显示
- 根因：仅依赖 `MediaItem.mimeType` 或自定义缓存键推断，广播时机偶发拿不到值；UI 没有在缺失时主动向服务查询，导致始终 `GONE`。

4) 全屏标题不滚动
- 根因：布局缺少 `scrollHorizontally`，或未在代码中执行 `setSelected(true)` 触发跑马灯。

- 5) 歌单详情：随机/列表循环异常
  - 根因A（列表循环）：歌单详情采用“小窗口注入（前5/后15）”，未实现越界时的窗口扩边/重建；当从最后一首想跳回第一首或跨窗口切换时，相对索引被夹在窗口边界，出现“跳不过去/回弹到某一首”。
  - 根因B（随机）：仅对“小窗口”启用 Exo 内建 shuffle，并以当前媒体索引为“锚点”保序；当序列随机回到锚点曲目后，内部顺序不重置，后续按同一序列重复；同时，因只注入小窗口，随机覆盖范围偏小，容易在少数曲目间循环。
  - 伴随问题：断网新建歌单与在线绑定/同步时机不一致，可能导致“歌单列表看不到而弹窗可见”“同名双生子残留”。

6) 全屏播放器页面指示器不同步
- 现象：在歌词页直接退出到列表后再次进入全屏播放器，会自动回到主控页，但页面指示器的小白点仍停留在歌词页位置（右侧）。
- 根因：打开全屏时仅重置了 `currentFullPage=0` 与视图显隐，未同步调用指示器更新方法，导致指示器状态滞后。

7) 冷启动首次自动展开导致卡顿
- 现象：应用冷启动后第一次点击歌曲播放，低端设备上若自动展开全屏播放器，存在明显卡顿。
- 根因：首帧进入即触发全屏覆盖层 `inflate+measure+layout` 以及背景模糊与歌词容器初始化，导致主线程短时阻塞。

8) 歌词拖动被强制回中
- 现象：用户手动拖动歌词列表时，若下一句被高亮放大，列表会立刻跳回当前高亮行，导致操作不连贯。
- 根因：播放心跳在索引变化时无条件执行 `centerTo(currentIndex)`，未判断用户是否处于手势浏览中。

---

### 方案总览与实现要点
A. Seek 放行与跨曲目挂起修正（短期彻底消除“跳到开头”）
- 服务端广播增强：
  - 在 `ACTION_PLAYBACK_STATE_CHANGED` 中新增/保证字段：
    - `isSeekable`：`player.isCurrentMediaItemSeekable()`
    - `isDurationUnset`：`duration<=0 || duration==C.TIME_UNSET`
    - `audioType`：音频类型（详见下文 FLAC 部分）
- 服务端底线保护：
  - 在服务端 `seekTo(long)` 路径上增加保护（不可寻址或时长未知时直接忽略请求，打印轻量日志），避免底层被动回到 0。
- UI 严格放行：
  - `MainActivity` 三处入口（小播放器 SeekBar、全屏 SeekBar、歌词行点击）统一判定：仅 `isSeekable==true` 且 `!isDurationUnset` 时才放行 `seekTo`；否则记录挂起或提示。
  - 歌词页点击在 `LyricsController` 内部也做最小保护，时长未知不再直接 `seekTo`（轻提示）。
- 跨曲目挂起清理：
  - 延后执行的 `pendingSeekMs` 绑定当前 `songId`，仅当广播 `songId` 一致时才执行；
  - 切歌（收到新 `songId`）立刻清空 `pendingSeekMs/pendingSeekSongId`，杜绝跨曲目误跳。

B. FLAC 原生优先播放 + 失败自动回退 MP3@320
- 播放 URL 策略：
  - `getOptimalPlayUrl(...)` 不再在检测到 FLAC 时“提前改为 MP3”；原始 FLAC 优先，保持对 `MediaItem` 的 `mimeType=AUDIO_FLAC` 提示。
- 失败回退（稳定性优先）：
  - `onPlayerError(...)` 中，仅对当前曲目第一次失败时，自动替换为服务器转码的 MP3 并原位重播；
  - 回退码率由 `192kbps` 提升至 `320kbps`。
- 类型识别（用于徽标与诊断）：
  - 新增基于 `Tracks` 的类型识别：读取当前已选音轨的 `Format.sampleMimeType`，优先判定 `FLAC/MP3`，并兼容 `AAC/OGG/OPUS`；
  - 广播优先使用该识别结果；若为空再回退 `MediaItem.mimeType` 或 `customCacheKey`。

C. 音频类型徽标与设置
- 设置页：
  - `PlayerSettingsActivity` 新增开关 `show_audio_type_badge`（卡片“显示音频类型”）与摘要文案；
  - 新增开关 `auto_open_full_player`（卡片“点击播放后自动打开全屏播放器”），并在偏好中保存；冷启动首次播放不展开，从第二次起按开关执行；
  - 切换开关后发送 `UI_SETTINGS_CHANGED { what=audio_badge }`，前台 UI 同步刷新。
- 全屏 UI：
  - `layout_full_player_overlay.xml` 在标题行右侧新增 `TextView@id/full_audio_badge`，使用 `@drawable/badge_audio_type`（白色 1dp 描边、4dp 圆角）；
  - `MainActivity` 在接收广播或 `trySyncFullPlayerUi()` 时，根据开关与类型值决定 `VISIBLE/GONE`；若广播未携带类型，主动向服务 `getCurrentAudioType()` 查询并缓存。

D. 全屏标题跑马灯
- 页面指示器同步修复：在 `openFullPlayer()` 中于 `currentFullPage=0` 与 `applyFullPageVisibility()` 之后，新增一次 `updatePageIndicator()`，确保小白点与主控页同步；避免停留在歌词页状态。
- 冷启动首播不自动展开：在“自动展开全屏播放器”开关基础上，引入 `hasPerformedFirstUserPlay` 标记，冷启动第一次手动播放仅记录标记不展开，从第二次起才按开关展开，降低低端设备卡顿风险。

- 歌词拖动期间不强制回中：在 `LyricsController` 中，当 `userBrowsing=true`（由 RecyclerView 的滚动监听标记）时，索引变化不执行 `centerTo`；空闲超时（默认4s）后自动恢复回中。
- 采用 `SeamlessMarqueeTextView` 实现首尾相接的无缝滚动，保持原字号/加粗/颜色；
- 仅当文本宽度超出可视宽度且视图可见时启动帧回调，低功耗；不可手动拖动；观感与迷你播放器一致，无跳变。

- E. 歌单详情：随机/列表循环修复与优化
  - 列表循环：
    - 在非全局列表（歌单详情）引入“本地窗口重建”：当目标索引不在当前窗口（前5/后15）内时，按“前5/后15”重建窗口并再 seek，保证首尾环绕与跨窗口切换生效；移除“相对索引夹住”的副作用。
  - 随机序列：
    - 锚点与自动重洗：记录首次开启随机时的锚点曲目，一旦“本轮随机”再次回到锚点且期间已离开过锚点，立即重置随机顺序；优先通过 Exo 的 `DefaultShuffleOrder`（反射调用 `setShuffleOrder`），失败则回退为“重设媒体项并重新启用 shuffle”，始终保持当前曲与进度不变。
    - 扩大覆盖范围：对小歌单（≤120 首）在随机模式下“全量注入媒体项”，再启用 shuffle，使随机覆盖整张歌单而非仅小窗口；大歌单保持窗口化以控内存。
  - 性能与稳定性：
    - 将窗口重建由 `stop()+clear` 改为 `setMediaItems(...)+prepare()`，避免中断渲染管线；
    - 移除切曲回调中的高频扩边调用、降低冗余日志，新增 `DEBUG_VERBOSE` 开关以控制调试输出；
    - 修复进入歌单详情误把底部播放进度条当“加载进度”隐藏的问题，确保进度条稳定可见。

---

### 代码变更清单（核心摘录）
- `app/src/main/java/com/watch/limusic/service/PlayerService.java`
  - 广播字段：`isSeekable`、`isDurationUnset`、`audioType`；
  - 新增与修正：
    - FLAC 回退：`getTranscodedStreamUrl(songId, "mp3", 320)`；
    - 类型识别：`detectCurrentAudioType()`；UI 主动查询：`getCurrentAudioType()`；
    - 非全局列表窗口重建：`expandLocalWindowIfNeeded(playerIndex)` + 在 `next()/previous()` 越界时按“前5/后15”即时重建；
    - 随机锚点与回到起点自动重洗：在 `onMediaItemTransition` 中检测并触发 `rerollLocalShuffleOrderKeepingCurrent()`；
    - 小列表全量注入阈值：`FULL_INJECT_THRESHOLD=120`，在随机模式下对 ≤阈值的歌单全量注入再启用 shuffle；
    - 降噪与性能：移除高频扩边；窗口重建改用 `setMediaItems(...)+prepare()`；新增 `DEBUG_VERBOSE`。

- `app/src/main/java/com/watch/limusic/MainActivity.java`
  - 新增字段：`lastIsSeekable`、`lastDurationUnset`、`lastSongIdFromBroadcast`、`pendingSeekSongId`、`lastAudioType`；
  - 广播接收：记录 `isSeekable/isDurationUnset/audioType`；切歌即清空挂起；
  - 放行条件：三处 `seek` 入口仅在可寻址与时长已就绪时放行；否则记录 `pendingSeekMs` 并绑定 `pendingSeekSongId`；
  - 延后执行：仅当广播 `songId` 与 `pendingSeekSongId` 一致时才执行；
  - 全屏徽标：根据设置与类型值（广播或 `safeGetAudioType()`）控制显隐；
  - 跑马灯：`full_song_title` 在同步时 `setSelected(true)`；
  - `trySyncFullPlayerUi()`：在返回前台或设置变化时同步按钮、进度、徽标与跑马灯。
  - 全屏自动展开设置：新增偏好 `auto_open_full_player` 及设置页卡片；在“所有歌曲/歌单点击播放”和“服务绑定后执行挂起播放”两处判断该偏好并应用冷启动首播跳过逻辑（`hasPerformedFirstUserPlay`）。
  - 全屏指示器修复：在 `openFullPlayer()` 中重置页后调用 `updatePageIndicator()`，避免小白点错位。

- `app/src/main/java/com/watch/limusic/LyricsController.java`
  - 歌词行点击跳转加入保护：时长未知或不可寻址时放弃 `seekTo`，提示“当前曲目暂不支持拖动”。
  - 拖动期间暂停自动回中：`RecyclerView.addOnScrollListener` 标记 `userBrowsing=true` 并刷新 `lastUserInteractAt`；播放心跳在 `userBrowsing` 为真时仅更新高亮，不执行 `centerTo`；空闲超时（默认4s）后自动回中。

- `app/src/main/res/layout/layout_full_player_overlay.xml`
  - 标题容器仍为“标题（权重 1）+ 徽标（wrap_content）”；
  - `full_song_title` 使用 `com.watch.limusic.view.SeamlessMarqueeTextView` 替换标准 TextView，实现首尾相接滚动；
  - `full_audio_badge`（默认 `visibility=gone`）。
- `app/src/main/java/com/watch/limusic/view/SeamlessMarqueeTextView.java`
  - 新增自定义无缝跑马灯视图，按像素线性滚动、循环取模，无跳变；仅在需要时启停，功耗可控。

- `app/src/main/java/com/watch/limusic/repository/PlaylistRepository.java`
  - 自动与手动同步：`autoBindAndSyncPending()` / `manualBindAndSync(localId, callback)`；
  - 创建并添加串行化：`createPlaylistAndAddSongs(name, isPublic, orderedSongIds, callback)`（优先远端创建与绑定，失败本地暂存）；
  - 对账增强：优先将远端同名歌单与本地-only 合并绑定，避免同名重复；
  - 差集补推：对本地-only 与 `syncDirty=1` 的已绑定歌单仅推送缺失曲目；
  - 广播刷新：关键节点发送歌单更新广播，保持 UI 一致。

- `app/src/main/java/com/watch/limusic/database/PlaylistDao.java`
  - 绑定/查询/清理：`bindServerId(...)`、`findLocalOnlyByName(...)`、`getLocalOnlyActive()`、`getBoundDirtyActive()`、`softDeleteLocalDuplicatesByName(...)`。

- `app/src/main/java/com/watch/limusic/api/NavidromeApi.java`
  - 新增 `getCurrentUsername()`，用于按 owner 精确匹配远端歌单。

---

### 受影响文件与关键点
- `app/src/main/java/com/watch/limusic/service/PlayerService.java`
- `app/src/main/java/com/watch/limusic/MainActivity.java`
- `app/src/main/java/com/watch/limusic/LyricsController.java`
- `app/src/main/res/layout/layout_full_player_overlay.xml`
- `app/src/main/res/drawable/badge_audio_type.xml`
- `app/src/main/res/layout/activity_player_settings.xml`
- `app/src/main/java/com/watch/limusic/PlayerSettingsActivity.java`
- `app/src/main/java/com/watch/limusic/repository/PlaylistRepository.java`
- `app/src/main/java/com/watch/limusic/database/PlaylistDao.java`
- `app/src/main/java/com/watch/limusic/api/NavidromeApi.java`

---

### 验收用例与验收标准（手表优先）
1) 拖动/歌词跳转不再“跳到开头”
- 流媒体不可 Seek（强制转码、`dur=TIME_UNSET`）时：拖动进度条或歌词点击不执行跳转，出现轻提示/记录挂起，播放继续当前进度；
- 流媒体可 Seek 或稍后就绪：若仍在同一曲目且 `isSeekable==true`，自动执行一次延后跳转到记录位置；
- 切歌后：不执行上一首的挂起跳转。

2) FLAC 原生优先 + 失败自动回退
- 能原生播 FLAC 的曲目：正常播放，日志包含 `AUDIO_FLAC` 识别；
- 个别设备或资源失败：自动回退为服务器 `mp3@320`，日志出现“已回退为转码MP3重试(原位替换)”且 URL 含 `maxBitRate=320`；

3) 音频类型徽标
- 设置-播放器设置 打开“显示音频类型”后：全屏标题右侧出现白描边方框，显示 `FLAC` 或 `MP3`；
- 关闭开关：徽标 `GONE`；
- 切歌后：徽标随曲目类型更新；
- 广播未带类型时：UI 仍可主动查询并显示，不需手动刷新。

4) 全屏标题跑马灯
- 标题超长时首尾相接无缝滚动；短标题不滚动；不可手动拖动；观感与迷你播放器一致。

5) 性能/功耗
- 无常驻轮询；广播/查询仅在必要时触发；
- 全屏 UI 更新批量合并，不引入频繁 `invalidate`；
- 手表场景下流畅度不下降、耗电无明显增加。

- 6) 歌单详情：随机/列表循环
  - 列表循环：从最后一首点“下一首”跳回第一首；从第一首起能完整循环一遍；跨窗口切换无“回弹”。
  - 随机：本轮“回到起点”后自动重洗，后续顺序改变；小歌单（≤120）随机覆盖整张歌单，长时间随机不应只在少数曲目间循环。
  - UI：进入歌单详情时，底部播放进度条稳定可见。

---

### 性能与功耗评估
- Seek 防误跳逻辑完全事件驱动：不增加常驻定时/轮询；
- 音频类型识别以“广播携带 + 必要时一次 `getCurrentTracks()` 查询”为主，成本低；
- 跑马灯仅影响单一 `TextView` 的布局属性，不增加额外绘制层级；
- 回退 `mp3@320` 仅在 FLAC 解码异常时触发，不增加正常播放成本；
- 自动展开优化：冷启动首次播放不展开，避免低端设备首帧卡顿；随后展开仅在用户开启开关时触发；
- 歌词滚动流畅性：拖动期间暂停自动回中，避免高亮切换抢焦点导致的跳动；
- 歌单详情优化：窗口重建采用 `setMediaItems`，避免停播重建；默认仅小窗口，随机模式对小歌单才全量注入；日志默认降噪，必要时开启 `DEBUG_VERBOSE` 排查。

---

### 风险与回滚
- 个别设备 `Tracks` 信息获取延迟：已用 `MediaItem.mimeType/customCacheKey` 兜底；
- 某些服务端流始终不可 Seek：UI 会稳态禁止拖动并支持挂起记忆（同曲目就绪后生效）；
- 随机序列重置：`DefaultShuffleOrder` 反射不可用时已回退“重设媒体项”，仍保持当前曲与进度；如仍不满足，可临时关闭“回到起点自动重洗”；
- 全量注入阈值：`FULL_INJECT_THRESHOLD` 可按设备内存调低（如 80）或关闭（设为 0），以换取更多余量；
- 回滚策略：
  - 若 FLAC 在特定设备上普遍失败，可临时将 `getOptimalPlayUrl(...)` 切回“优先转码 MP3”；
  - 若徽标显示引发兼容问题，可在设置中默认关闭；
  - Seek 逻辑具备服务端与 UI 双重保护，必要时可仅保留服务端拦截；
  - 歌单详情优化均在 `PlayerService` 层可开关/调整阈值，风险可控。

---

### 版本信息与变更摘要
- 版本：v3.2（2025-09-06，Seek 防误跳与 FLAC 原生优先、音频类型徽标、回退 320kbps）
- 变更摘要：
  - 新增：`isSeekable/isDurationUnset/audioType` 广播；
  - 修复：拖动/歌词跳转误回到 0；跨曲目挂起误跳；全屏页面指示器不同步；
  - 优化：FLAC 原生优先，失败自动回退 MP3@320；冷启动首次不自动展开全屏播放器，二次起按开关执行；
  - UI：全屏标题跑马灯与音频类型徽标；设置新增显示开关与“自动展开全屏播放器”开关；
  - 歌单详情：列表循环首尾环绕、随机“回到起点自动重洗”、小歌单全量注入扩大覆盖、日志降噪与切歌流畅度优化；
  - 歌单同步：离线创建合并绑定、自动/手动同步与同名去重，列表与弹窗一致性修复。
  - 歌词滚动：拖动期间暂停自动回中，空闲超时后再回中；提升手势连续性。

---

### 附录：接口与关键方法（摘要）
- 服务端
  - `sendPlaybackStateBroadcast()` → extras: `isSeekable`、`isDurationUnset`、`audioType`、`songId/title/artist/albumId`、`fallbackDurationMs`
  - `detectCurrentAudioType()` / `getCurrentAudioType()`
  - `onPlayerError(...)` 回退：`getTranscodedStreamUrl(songId, "mp3", 320)`
- UI
  - `MainActivity`：`safeGetAudioType()`、`trySyncFullPlayerUi()`、挂起跳转绑定 `pendingSeekSongId`
  - `LyricsController`：歌词点击保护（不可 Seek/时长未知时不跳转）
- 设置
  - 偏好键：`show_audio_type_badge`；广播：`com.watch.limusic.UI_SETTINGS_CHANGED { what=audio_badge }` 

---

### 歌单：离线创建合并、自动/手动同步与重复项治理（Navidrome）
- 背景：
  - 断网或弱网时，用户可能在“添加到歌单”入口新建歌单并添加歌曲；早期实现仅本地入库、不回填 serverId，联网后服务端拉回同名歌单，导致本地出现“同名双生子”（一个仅本地、一个已绑定远端）。
  - 同步时机不确定导致“列表页不显示而弹窗可见”的不一致。
  - 需要提供“手动同步”以便用户一键推动绑定与补传，提升可控性。
- 目标：
  - 本地-only 歌单在联网后自动尝试合并绑定到远端同名歌单；如无同名远端歌单可选，支持自动/手动创建后绑定。
  - 差集同步：仅补传服务端缺失的歌曲，避免重复推送和失败。
  - 消除UI不一致：歌单列表与“添加到歌单”弹窗对齐显示；同名项进行去重（serverId优先）。
- 关键实现：
  - 对账与绑定（PlaylistRepository.syncPlaylistsHeader）
    - 不再删除本地-only 歌单，作为待绑定对象保留。
    - 当远端拉回歌单时，优先尝试与同名本地-only 歌单合并绑定（回填 serverId），避免新增重复头部。
  - 自动补同步（PlaylistRepository.autoBindAndSyncPending）
    - 进入歌单列表时触发：
      1) 获取远端列表，按 名称+owner 匹配本地-only 歌单并绑定 serverId；
      2) 拉取远端明细，与本地明细做差集，仅对缺失歌曲执行 updatePlaylist(songIdToAdd=...);
      3) 对已绑定但 syncDirty=1 的歌单复用差集逻辑进行补推，成功后清除 syncDirty。
  - 手动同步（MainActivity 长按菜单项“手动同步” → PlaylistRepository.manualBindAndSync）
    - 流程：尝试匹配绑定 → 无匹配则创建远端 → 计算差集并补传 → Toast 提示结果（已一致/同步成功/失败原因）。
  - UI 一致性与去重：
    - 歌单列表（远端分支）：合并本地-only 后按名称去重，优先保留已绑定 serverId 的项；若均未绑定，保留 changedAt 最新者。
    - “添加到歌单”弹窗：读取本地库后按相同策略去重，避免出现两个同名项。
  - 删除整治：
    - 删除已绑定远端歌单时，顺带软删除同名本地-only 重复项，避免多选弹窗的残留；删除本地-only 占位时亦清理同名占位。
- 数据与接口：
  - PlaylistDao：
    - bindServerId(localId, serverId)；findLocalOnlyByName(name)；getLocalOnlyActive()；getBoundDirtyActive()；softDeleteLocalDuplicatesByName(name, changedAt)。
  - Repository：
    - manualBindAndSync(localId, callback)；autoBindAndSyncPending()；ensureLocalFromRemoteHeader(serverId, name, ...)。
  - API：
    - 复用 getPlaylists/getPlaylist/updatePlaylist/createPlaylist；新增 NavidromeApi.getCurrentUsername() 用于 owner 匹配。
- 验收要点：
  1) 断网新建并添加歌曲 → 联网后进入歌单列表，远端存在同名则自动绑定并补传；服务端可见歌曲一致；UI 无重复项。
  2) 若远端无同名 → 手动同步一键创建并绑定，随后差集补传；成功提示。
  3) 删除已绑定项后，“添加到歌单”弹窗不再残留同名本地-only 项。
- 性能与稳定性：
  - 自动同步在列表进入时一次触发，采用差集计算与单次 updatePlaylist 批量追加，避免高频网络调用；对手表功耗影响可忽略。
  - 去重与合并在内存列表上完成，排序与显示稳定；数据库操作均为轻量级查询/更新。 
  - - 播放服务
  - 本地窗口重建：`expandLocalWindowIfNeeded(playerIndex)`、`next()/previous()` 越界时的重建逻辑
  - 随机锚点与重洗：`rerollLocalShuffleOrderKeepingCurrent()`；`FULL_INJECT_THRESHOLD`
- 歌单同步
  - `PlaylistRepository`：`createPlaylistAndAddSongs(...)`、`autoBindAndSyncPending()`、`manualBindAndSync(...)`
  - `PlaylistDao`：`bindServerId(...)`、`findLocalOnlyByName(...)`、`getLocalOnlyActive()`、`getBoundDirtyActive()`、`softDeleteLocalDuplicatesByName(...)`
  - `NavidromeApi`：`getCurrentUsername()` 