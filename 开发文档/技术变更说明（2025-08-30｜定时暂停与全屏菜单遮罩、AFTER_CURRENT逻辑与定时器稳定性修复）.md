## 2025-08-30｜定时暂停与全屏菜单遮罩、AFTER_CURRENT 逻辑与定时器稳定性修复
(TechChangeSpec_2025-08-30-Sleep-Timer)

### 背景与目标
- 背景：
  - 手表设备上增加“定时暂停”能力，并支持“播完当前单曲再暂停”（AFTER_CURRENT），入口集成到全屏播放器的多功能菜单（底部三键最右）内；
  - 要求低功耗、低开销，不引入常驻轮询；
  - 之前尝试中存在“到点不暂停”的问题，需要修复定时器调度基准不一致导致的定时任务不触发。
- 目标：
  - UI：新增全屏遮罩样式的“定时暂停”设置界面（半透明背景+卡片+拖动条+选项），与现有音量遮罩风格统一；
  - 交互：拖动条可设置 0–60 分钟；0 表示关闭定时；提供“播完当前单曲再暂停”选项；
  - 行为：
    - 仅设置分钟（未勾选）：到点立即暂停；
    - 设置分钟并勾选：到点不立即暂停，转换为 AFTER_CURRENT，等待当前曲目自然结束或单曲循环重复时立即暂停；
    - 仅勾选（分钟=0）：纯 AFTER_CURRENT，当前曲目结束处暂停；
    - 手动切歌不取消 AFTER_CURRENT（符合预期）；
  - 稳定性：修复定时器调用基准错误（postAtTime + elapsedRealtime → 不触发），统一改为 postDelayed 相对延时，确保“普通定时”到点即停生效；
  - 低功耗：不引入周期性心跳，仅在 READY 后补发一次 250ms 的心跳广播，AFTER_CURRENT 时才进行一次剩余重算与覆盖定时。

---

### 问题回溯与根因分析
1) 到点不暂停（普通定时）
- 根因：使用了 `Handler.postAtTime(runnable, absoluteElapsedTime)`，而 `absoluteElapsedTime=SystemClock.elapsedRealtime()` 与 Handler 的默认基准 `uptimeMillis` 不一致，部分设备/场景下导致定时任务不触发。
- 解决：统一改为 `Handler.postDelayed(runnable, delayMs)`，`delayMs = max(0, deadlineElapsed - SystemClock.elapsedRealtime())`。

2) AFTER_CURRENT 在一些曲目 `duration==TIME_UNSET` 时首次挂起不准确
- 根因：初始 READY 前无法拿到有效时长，剩余时长计算不稳，可能导致定时挂起时间偏差。
- 解决：`onPlaybackStateChanged(READY)` 后 250ms 再次补发心跳；若处于 AFTER_CURRENT，重算一次“剩余时长”并覆盖已有挂起（使用 postDelayed）；保证时长就绪后定时正确。

3) 手动切歌时是否取消 AFTER_CURRENT
- 预期：手动切歌不取消 AFTER_CURRENT；仅自动切曲（AUTO）或单曲循环重复（REPEAT）到达“本首结束”时暂停。
- 调整：`onMediaItemTransition` 中先判断 `sleepType==AFTER_CURRENT` 且 `reason in [AUTO, REPEAT]` 即 `pauseAndClearSleep()` 并 `return`，确保不会被后续逻辑覆盖。

---

### 方案总览与实现要点
A. UI：全屏“定时暂停”遮罩
- 布局：`app/src/main/res/layout/layout_full_player_overlay.xml`
  - 新增：
    - `FrameLayout@id/sleep_overlay`（全屏半透明背景，点击空白关闭）；
    - `LinearLayout@id/sleep_card`（卡片容器，阻止点击冒泡）；
    - `TextView@id/sleep_title`（“定时暂停”标题）；
    - `TextView@id/sleep_value`（状态文案：已关闭/不到1分钟/xx分钟/播完当前后暂停）；
    - `SeekBar@id/sleep_seek`（0–60 分钟，0=关闭）；
    - `CheckBox@id/sleep_after_current`（“播完当前单曲再暂停”）。
- 字符串资源：`app/src/main/res/values/strings.xml`
  - 新增：
    - `sleep_timer`、`sleep_timer_value_off`、`sleep_timer_value_minutes`、`sleep_timer_value_less_minute`
    - `sleep_wait_finish_current`
    - `sleep_toast_set_minutes`、`sleep_toast_set_after_current`、`sleep_toast_cleared`（如需）
- 入口与交互：`app/src/main/java/com/watch/limusic/MainActivity.java`
  - 入口按钮：底部三键最右侧 `btn_more`，点击 `toggleSleepOverlay()`；
  - 遮罩打开：
    - 调用服务端 `getSleepTimerState()` 回显：
      - `sleep_seek.setProgress(剩余分钟)`；
      - `sleep_after_current.setChecked(state.waitFinishOnExpire || state.type==AFTER_CURRENT)`；
      - 文案：AFTER_CURRENT 且分钟=0 → “播完当前单曲再暂停”；分钟=1 且 `remainMs<60s` → “不到 1 分钟后暂停”；分钟>0 → “%d 分钟后暂停”；否则“已关闭”。
  - 拖动条变更：
    - progress==0 → `cancelSleepTimer()`；
    - progress>0 → `setSleepTimerMinutes(progress, sleep_after_current.isChecked())`；
  - 复选框变更：
    - 分钟==0 且勾选：`setSleepStopAfterCurrent()`；
    - 分钟>0 且勾选：`setSleepTimerMinutes(minutes, true)`；
    - 取消勾选：分钟>0 则只按分钟定时，分钟==0 则取消。

B. 服务端：定时暂停与 AFTER_CURRENT 实现
- 文件：`app/src/main/java/com/watch/limusic/service/PlayerService.java`
- 新增状态与常量：
  - `enum SleepType { NONE, AFTER_MS, AFTER_CURRENT }`
  - `SleepType sleepType`，`Runnable sleepRunnable`，`long sleepDeadlineElapsedMs`，`boolean sleepWaitFinishOnExpire`
  - 广播：`ACTION_SLEEP_TIMER_CHANGED="com.watch.limusic.SLEEP_TIMER_CHANGED"`
- API：
  - `setSleepTimerMinutes(int minutes, boolean waitFinishCurrent)`：
    - minutes<=0 → `cancelSleepTimer()`；
    - 未勾选：`postDelayed(delayMs)` 到点 `pauseAndClearSleep()`；
    - 勾选：到点切 `sleepType=AFTER_CURRENT`，计算 `remain=computeRemainMsSafe()` 并 `postDelayed(remain)`，到点 `pauseAndClearSleep()`；
  - `setSleepStopAfterCurrent()`：
    - `sleepType=AFTER_CURRENT`，计算 `remain` 并 `postDelayed(remain)` 到点暂停；
  - `cancelSleepTimer()`：移除回调、清空状态并广播；
  - `getSleepTimerState()`/`getSleepRemainMs()`：供 UI 回显。
- 关键实现：
  - 定时器调度全部统一 `postDelayed`（相对延时），避免 `postAtTime` + `elapsedRealtime` 与 Handler 基准不一致导致不触发；
  - READY 后 250ms 补发心跳：若 `sleepType==AFTER_CURRENT`，重算 `remain` 并覆盖挂起（`postDelayed`）；
  - `onMediaItemTransition`：当 `sleepType==AFTER_CURRENT` 且 `reason in [AUTO, REPEAT]` 时，立即 `pauseAndClearSleep()` 并 `return`；手动切歌（SEEK）不取消；
  - `computeRemainMsSafe()`：优先 ExoPlayer `duration/position`；若 `duration<=0` 尝试用 DB 缓存时长补齐；返回 `max(0, dur-pos)+500ms` 余量。

C. 事件与广播
- UI 打开遮罩读取 `getSleepTimerState()` 进行一次回显；
- 若需要 UI 实时更新勾选/文案，可监听 `ACTION_SLEEP_TIMER_CHANGED`（本次方案默认不常驻订阅，避免开销）。

D. 边界与约定
- “播完当前单曲再暂停”
  - 不因手动切歌取消；
  - 自动切曲或单曲循环重复时，立即暂停；
  - 若此前单曲循环导致无法自然结束，在转入 AFTER_CURRENT 时会临时关闭 `REPEAT_ONE` 以便自然结束（不自动恢复，符合“本首结束后暂停”的直觉）。
- “不到 1 分钟”显示：`remainMs<60s` 时显示“不到 1 分钟后暂停”。
- 定时未设置时无任何挂起回调；设置后只有单一 `Runnable`；不引入常驻心跳。

---

### 受影响文件与关键点
- 布局：
  - `app/src/main/res/layout/layout_full_player_overlay.xml`
    - 新增 `sleep_overlay/sleep_card/sleep_title/sleep_value/sleep_seek/sleep_after_current`
- 资源：
  - `app/src/main/res/values/strings.xml`
    - 新增定时暂停相关字符串键（详见上文）
- 代码：
  - `app/src/main/java/com/watch/limusic/MainActivity.java`
    - 绑定 `btn_more` → `toggleSleepOverlay()`；
    - 遮罩打开从服务端回显；拖动条/复选框变更即时调用服务接口；
  - `app/src/main/java/com/watch/limusic/service/PlayerService.java`
    - 新增睡眠定时状态、API、READY 后重算、切曲触发暂停、`postDelayed` 调度。

---

### 验收用例与验收标准（手表设备优先）
1) 普通定时（未勾选）
- 设置 1 分钟 → 到点立即暂停；
- 设置 5 分钟 → 到点立即暂停；
- 关闭定时（拖动条=0）→ 不会暂停。

2) AFTER_CURRENT（仅勾选，分钟=0）
- 当前曲目自然结束时立即暂停；
- 手动“下一首”不取消 AFTER_CURRENT；下一首自然结束时暂停（如果未设置分钟，这条仅当用户持续不操作时验证）。

3) 定时+AFTER_CURRENT（分钟>0 且勾选）
- 到点后不立即暂停，转换为 AFTER_CURRENT，当前曲目自然结束或单曲循环重复时立即暂停。

4) 文案与回显
- 打开遮罩时正确显示当前状态：分钟、勾选、文案（含“不到 1 分钟后暂停”）；
- 关闭定时后文案显示“已关闭”。

5) 低功耗与性能
- 未设置定时时不产生挂起任务；设置后仅一个 `Runnable`；
- READY 后仅一次 250ms 的轻量心跳；
- 无主线程长阻塞，无 WakeLock 增量。

---

### 性能与功耗评估
- 不常驻轮询：仅一次性定时挂起；
- 文案回显仅在打开遮罩时请求服务端状态；
- READY 后 250ms 仅一次性补发，且仅在 AFTER_CURRENT 下重算一次剩余；
- 适合手表设备：UI 轻量、业务逻辑事件驱动，CPU/GPU 开销可忽略。

---

### 风险与回滚
- 风险：
  - 个别设备对 Handler 回调执行有严格后台限制（极少数系统策略）；
  - 曲目初始时长持续为未知时，AFTER_CURRENT 的剩余可能依赖 DB 兜底，精度略有误差；
- 回滚策略：
  - 若出现误差，可临时仅依赖切曲事件触发（移除 READY 后重算）；
  - 将“到点立即暂停”路径作为兜底（不启用 AFTER_CURRENT 转换）。

---

### 版本信息与变更摘要
- 版本：v3.1（2025-08-30，定时暂停、AFTER_CURRENT 与定时器稳定性修复）
- 变更摘要：
  - 新增：全屏“定时暂停”遮罩（拖动条 0–60 分钟 + “播完当前单曲再暂停”选项）；
  - 服务端：统一使用 postDelayed 调度；增加 READY 后重算剩余；自动切曲/重复时立即暂停；
  - UI：打开遮罩实时回显服务端状态；文案支持“不到 1 分钟后暂停”。

---

### 附录：接口与关键方法（摘要）
- `PlayerService.setSleepTimerMinutes(int minutes, boolean waitFinishCurrent)`
- `PlayerService.setSleepStopAfterCurrent()`
- `PlayerService.cancelSleepTimer()`
- `PlayerService.getSleepTimerState()` → `{ type, remainMs, waitFinishOnExpire }`
- 广播：`com.watch.limusic.SLEEP_TIMER_CHANGED { type, remainMs }` 