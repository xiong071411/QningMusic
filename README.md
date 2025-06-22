# LiMusic
### 本项目的设计初衷是作者在学校只能用oppo watch2听歌，同时有些歌网易云，QQ上没版权，所以只能下载到本地用本地播放器播放，但是随着收集到的无版权歌曲越来越多，手表空间就不够用了，经过我一番查找，貌似没有人在做相关的可连接自定义音乐库进行音频串流的软件，所以我干脆用cursor手搓了这样一个支持自定义音乐库在线音频串流的手表端播放器软件，该软件目前基本可用，只是处于起步阶段还有诸多问题，且仅支持navidrome，平时能够进行开发工作的时间不多，谢谢大家的支持和理解。
> 轻量、现代、完全开源的 **安卓手表navidrome在线串流** 音乐播放器

---

## 📱 给用户的

### 1. 应用简介
LiMusic 可以连接你的 **Navidrome** 私有流媒体服务器，将海量本地音乐库随时随地带到手表上。它针对圆形/方形小屏幕进行了专门设计，操作简单、动画流畅，并支持中文歌曲拼音索引。

### 2. 主要特性
1. **Navidrome 串流**：配置一次服务器即可在线播放整库音乐，支持 SSL、自签名证书。
2. **离线缓存**：选中歌曲/专辑即可下载到本地，地铁、跑步时也能收听（缓存功能开发中）。
3. **播放模式**：列表循环、单曲循环、随机播放三种模式一键切换。
4. **字母索引快速跳转**：在“所有歌曲”界面呼出 A-Z + # 三列索引，滑动/点击即可定位。
5. **歌词 & 专辑封面**：自动抓取并展示（待实现）。
6. **主题色自定义**：深色/浅色/跟随系统（规划中）。

### 3. 安装方式
1. 前往 **Releases** 页面下载最新的 `app-release.apk`；
2. 使用 `adb install app-release.apk` 或者通过 **Wear Installer** 将 APK 侧载到手表；
3. 首次启动后，在 **设置 → Navidrome 服务器设置** 中填写：
   - 服务器地址（示例：https://music.example.com）
   - 用户名
   - 密码/Token
4. 返回主界面即可开始浏览与播放。

### 4. 常见问题
| 问题 | 解决方案 |
|------|----------|
| 无法连接服务器 | 确认服务器地址是否含有协议（http/https）；若使用自签名证书请在服务器上开启 `INSECURE_TLS=true` 并在应用中允许不安全连接（开发中）。 |
| 歌曲时长显示 0:00 | 该歌曲的元数据缺失，请在服务器端重新整理标签。 |
| 如何删除离线缓存 | 设置→"清理缓存"；或在 **设置-缓存详情设置** 页面统一清理或设置最大缓存清理限制 |

### 5. 隐私说明
LiMusic 不会收集、上传任何个人数据；所有凭据仅保存在设备本地的加密 SharedPreferences 中。

---

## 🛠️ 给开发者的

### 1. 开发环境
- **JDK 17** (兼容 11)
- **Android Studio Giraffe / Hedgehog**
- **Gradle 8+** （项目自带 Wrapper）
- **最低 API 28 (Android 9)**，目标 **API 33 (Wear OS 4)**

### 2. 快速开始
```bash
# 克隆仓库
$ git clone https://github.com/<your-org>/LiMusic.git
$ cd LiMusic

# 导入 Android Studio，或使用命令行：
$ ./gradlew :app:installDebug    # 安装到已连接设备
```
> 第一次同步可能由于 MavenCentral 连接缓慢而耗时较长，可在 `gradle.properties` 中配置国内镜像。

### 3. 项目结构概览
```
app/
 ├─ src/main/java/com/watch/limusic/
 │   ├─ MainActivity.java          # 主 UI、播放器控制
 │   ├─ adapter/                  # RecyclerView 适配器
 │   ├─ api/                      # Retrofit + Gson 封装的 Navidrome API
 │   ├─ service/PlayerService.java# MediaPlayer 与通知
 │   ├─ model/                    # POJO 实体
 │   ├─ util/                     # 工具方法（拼音、手势...）
 │   ├─ view/                     # 自定义视图/对话框
 │   └─ SettingsActivity.java     # 设置首页 (列表)
 │
 ├─ res/
 │   ├─ layout/                   # Activity / Dialog XML
 │   ├─ drawable/                 # 向量图标 & 形状
 │   └─ values/                   # 字符串、主题、尺寸、颜色
 └─ build.gradle                  # 模块级依赖
```
如需查看更多，可使用 `./gradlew app:dependencies` 列出完整依赖树。

### 4. 重要技术栈
- **Retrofit2 + OkHttp3** 请求 Navidrome/Subsonic API
- **AndroidX Media3 ExoPlayer** 播放音频流
- **Gson** 解析 JSON；
- **Room**（计划）缓存元数据至本地数据库

### 5. 编码规范 & 提交
1. 遵循 **Google Java Style**；代码格式化由 `spotless` 插件自动完成：
   ```bash
   ./gradlew spotlessApply
   ```
2. Commit 信息遵循 [Conventional Commits](https://www.conventionalcommits.org/zh-hans/)：
   - `feat:` 新功能  
   - `fix:` Bug 修复  
   - `refactor:` 重构/优化
3. PR 须通过 GitHub Actions 自动化检查（编译 + Lint + 单元测试）。

### 6. 单元测试 / UI 测试
- 测试位于 `app/src/test` 与 `app/src/androidTest`
- 运行所有测试：
  ```bash
  ./gradlew testDebugUnitTest connectedDebugAndroidTest
  ```

### 7. 发布流程
```bash
# 生成 release 版 APK (启用 R8 混淆 & 签名占位符)
./gradlew :app:assembleRelease
# 输出文件位于 app/release/app-release.apk
```
若要上传到 GitHub Releases，可运行脚本 `scripts/release.sh`（TODO）。

### 8. 未来路线图
- [ ] 离线缓存完整实现（下载队列、空间管理）
- [ ] 支持 LRC 歌词 & 歌词同步显示
- [ ] Room 本地数据库；增加搜索功能
- [ ] 可穿戴设备间远程控制（手机 ↔️ 手表）

---

欢迎 star ⭐、fork 🍴、提 Issue 💬！如果 LiMusic 对你有帮助，请不吝给项目一个小小的鼓励。 
