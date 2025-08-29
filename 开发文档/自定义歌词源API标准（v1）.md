## 自定义歌词源 API 标准（v1）
(Spec_CustomLyricsSource_v1)

本标准用于指导第三方/自建服务为 LiMusic 提供歌词。遵循本规范即可被 App 识别与使用。

### 1. 总览
- 传输协议：HTTP/HTTPS（推荐 HTTPS）
- 方法：GET
- 编码：UTF-8（URL 与响应）
- 超时建议：≤ 3s（服务器侧可启用 gzip 压缩）
- 速率建议：限流/缓存，避免频繁抓取上游资源
- 权限：若需鉴权，可在 URL 中加入固定 `token`/`key` 参数（客户端仅透传）

### 2. URL 模板（App 侧配置）
- 模板中必须包含占位符：`{artist}` 与 `{title}`，App 会进行 URL 编码后替换。
- 示例：
  - 动态接口：`http://your.domain/lyrics/index.php?artist={artist}&title={title}`
  - 纯静态：`http://your.domain/lyrics/{artist}/{title}.lrc`
  - 横杠命名：`http://your.domain/lyrics/{artist}%20-%20{title}.lrc`
- 注意：
  - 当艺术家未知时，`{artist}` 可能为空字符串；服务端应容忍为空并做容错匹配。
  - 建议对大小写、全/半角、括号与噪声标签做清洗后匹配（参考“部署建议”）。

### 3. 返回格式（两种其一）
1) 文本（推荐 LRC）
- `Content-Type: text/plain; charset=utf-8`
- 响应体：
  - LRC 同步歌词（优先）：包含时间标签，如 `[00:15.23]`；支持 `[offset:±ms]`
  - 或纯文本非同步歌词（每行一句，无时间标签）

2) JSON（键取其一，优先级从上往下）
- `Content-Type: application/json; charset=utf-8`
- 可识别的字符串字段（任一出现即可）：
  - `syncedLyrics`（推荐）
  - `lrc`
  - `lyrics`
- 仅字符串内容会被读取作为歌词文本；其他字段将被忽略。

示例 JSON：
```json
{
  "syncedLyrics": "[00:10.00] 第一行\n[00:22.50] 第二行",
  "source": "your-service",
  "matched": true
}
```

### 4. 状态码与错误
- 命中：`200 OK`
- 未命中：`404 Not Found`（推荐）
- 服务错误：`5xx`
- 重定向：避免从 `/lyrics` 301 到 `/lyrics/` 导致参数丢失；若确需重定向，确保 `?artist=...&title=...` 被完整透传到目标脚本

### 5. LRC 支持范围（App 端）
- 时间标签：`[mm:ss]`、`[mm:ss.S]`、`[mm:ss.SS]`、`[mm:ss.SSS]`、`[hh:mm:ss]`
- 小数分隔：支持 `.` 与 `,`
- 多时间戳同一行：兼容（同一文本可被多个时间点引用）
- 偏移：支持 `[offset:±ms]`
- 无时间戳：当作“纯文本模式”显示，点击不跳转

### 6. 客户端获取优先级（仅说明，便于服务端预期）
1) 本地缓存 `downloads/lyrics/{songId}.lrc`
2) 本地下载目录同名 `.lrc`
3) 自定义歌词源（本规范）
4) 第三方歌词源（实验性，默认关闭）
5) Navidrome `getLyrics(artist,title)`
- 命中任一即写入缓存，后续优先走缓存
- 低电模式下，App 会短路歌词获取与渲染

### 7. 选择“正确的歌词源 API”的建议
- 优先选择“能直接返回 LRC 文本”的接口（`text/plain`），最简单、最稳
- 若返回 JSON，务必提供 `syncedLyrics` 字段；否则至少提供 `lrc`/`lyrics` 字段之一
- 响应时长 ≤ 3s，失败返回 404；不要返回 200 + 空内容
- 建议对 `artist`/`title` 做以下正规化后再匹配：
  - 小写化、全角转半角、去括号内容（中/英文括号）
  - 去噪声标签：LIVE/HQ/MV/伴奏/Remix/Version 等
  - 允许“标题子串匹配/分词重合”以提升命中率（例：文件名含长编号时）

### 8. 部署与重写建议（Nginx + PHP 可选）
- 纯静态：
  - 路径：`/lyrics/{artist}/{title}.lrc` 或 `/lyrics/{artist} - {title}.lrc`
  - 优点：零后端开销；缺点：需要严格命名
- 动态（PHP）：
  - 路径：`/lyrics/index.php?artist=...&title=...`
  - 重写：
    ```nginx
    location ^~ /lyrics/ {
        index index.php;
        try_files $uri $uri/ /lyrics/index.php?$args;
    }
    ```
  - 返回 LRC 或 JSON（见第 3 节）
  - 可实现：精确命中 → 模糊匹配（子串/分词/Dice 系数）

### 9. 安全与合规
- 仅返回有授权的歌词内容；必要时标注来源与提供下架通道
- 不要把上游平台防护绕过逻辑暴露在公网；对接口做限流/Referer 校验
- 若需要日志，建议去敏记录（hash），避免存储完整查询隐私

### 10. 兼容性清单（App 端目前的行为）
- URL：将 `{artist}`、`{title}` 使用 `URLEncoder`(UTF-8) 编码后替换
- 请求头：无特殊要求；`User-Agent` 为 OkHttp 默认
- CORS：非浏览器环境，无跨域限制
- 错误处理：`200` 且非空才视为命中；其他情况继续按优先级回退

### 11. 快速自检（Checklist）
- [ ] 接口能用 GET 直接访问
- [ ] 响应为 `text/plain`（LRC/纯文本）或 `application/json; charset=utf-8`
- [ ] JSON 中至少有 `syncedLyrics`/`lrc`/`lyrics` 字段之一，并为字符串
- [ ] 响应编码 UTF-8；命中返回 `200`；未命中返回 `404`
- [ ] 支持 `{artist}` 为空字符串的情况
- [ ] 平均响应 ≤ 3s，并支持 gzip

### 12. 示例
1) 纯文本 LRC：
```
[00:10.00] 第一行
[00:22.50] 第二行
[offset:-120]
```

2) JSON：
```json
{ "syncedLyrics": "[00:10.00] 第一行\n[00:22.50] 第二行" }
```

3) URL 模板：
- `http://lyrics.example.com/lyrics/index.php?artist={artist}&title={title}`
- `https://cdn.example.com/lrc/{artist}/{title}.lrc`

---
最后更新：2025-08-29（v1） 