# Android 前端对接指南

本文档说明如何在 Android 端（手表/手机）上报与查询听歌记录。

## 认证
- 使用 HTTP Basic 认证
- 用户名/密码与网页端一致
- Base64("username:password") 放在请求头 `Authorization: Basic ...`

## 上报接口
- 方法：POST `/api/listens`
- 请求头：`Content-Type: application/json` + `Authorization: Basic ...`
- 请求体：
```json
{
  "title": "歌曲名",              
  "artist": "作者名",
  "album": "专辑名",
  "source": "watch",
  "started_at": 1719830400,      
  "duration_sec": 210,
  "external_id": "可选外部ID"
}
```
说明：
- `title` 与 `started_at` 为关键字段；`started_at` 支持秒级时间戳或 ISO 字符串
- 去重键：`user + title + artist + album + started_at`
- 失败重试建议：仅在网络/5xx 重试 1 次；400/401 不要重试

## 分页查询接口
- 方法：GET `/api/listens?page=1&limit=50`
- 说明：
  - `page` 从 1 开始
  - `limit` 1..200，默认 50
- 响应：
```json
{
  "ok": true,
  "page": 1,
  "limit": 50,
  "total": 1234,
  "items": [
    {
      "id": 1,
      "title": "...",
      "artist": "...",
      "album": "...",
      "source": "watch",
      "started_at": 1719830400,
      "duration_sec": 210,
      "external_id": null,
      "created_at": 1719830410
    }
  ]
}
```

## Kotlin 示例
```kotlin
val client = OkHttpClient()
val creds = Credentials.basic(username, password)
val body = """
  {"title":"Song","started_at":${System.currentTimeMillis()/1000},"source":"watch"}
""".trimIndent().toRequestBody("application/json; charset=utf-8".toMediaType())
val req = Request.Builder()
  .url("$base/api/listens")
  .addHeader("Authorization", creds)
  .post(body)
  .build()
client.newCall(req).execute().use { resp ->
  if (!resp.isSuccessful) { /* handle */ }
}
```

## 常见错误
- 401 Unauthorized：用户名/密码错误或未提供 Basic 头
- 400 Bad Request：参数校验失败（检查 `title/started_at`）
- 200 ok + duplicate=true：说明已存在相同记录，无需再次上报
