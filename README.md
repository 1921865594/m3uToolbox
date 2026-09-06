# m3u工具 / m3uToolbox

[中文](#中文) | [English](#english)

---

## 中文

### 项目介绍

**m3u工具（m3uToolbox）** 是一款轻量级 Android 应用，用于**可视化自定义制作与管理本地 M3U 直播源文件**。

它面向需要整理、编辑 IPTV / 网络直播源列表的用户，提供直观的界面来导入、编辑、排序、去重并导出标准 M3U 播放列表，同时内置播放器可直接预览频道效果。

典型使用场景包括：
- 个人整理与维护本地 IPTV 直播源（.m3u / .m3u8）
- 批量编辑频道名称、分组（group-title）、台标（tvg-logo）、tvg-id 等属性
- 为特定频道设置 User-Agent、Referer 等 HTTP 请求头
- 从本地文件或在线 URL 导入源，导出后用于其他播放器（VLC、Kodi、IPTV 客户端等）
- 快速检查源是否可用（内置播放预览）

### 使用环境

- **平台**：Android
- **最低系统版本**：Android 9.0（API 28）
- **目标系统版本**：Android 14（API 34）
- **权限**：网络访问（在线导入与播放）、文件读写（导入/导出）
- **网络**：支持 HTTP / HTTPS 直播流；允许明文流量（usesCleartextTraffic）

### 主要功能

- **导入**：支持本地文件导入与在线 URL 导入
- **导出**：将当前编辑结果保存为标准 M3U 文件
- **头部参数管理**：编辑 `#EXTM3U` 行上的全局参数
- **分类管理**：新增 / 重命名 / 删除分类（对应 `group-title`）
- **频道管理**：
  - 新增、编辑、删除频道
  - 支持手动填写或粘贴原始 M3U 片段自动解析
  - 编辑 `tvg-id`、`tvg-name`、`tvg-logo`、`group-title`、播放地址
  - 单独设置频道级 User-Agent 与 Referer
- **拖拽排序**：通过拖拽调整频道顺序
- **多选与去重**：勾选模式 + 一键去重
- **内置播放器**：基于 Media3 ExoPlayer，集成 FFmpeg 扩展解码器，支持 HLS、RTSP 等更多格式

### 开发需求

本项目旨在提供一个**简单、可靠、对 URL 参数高度兼容**的 M3U 编辑工具，重点解决以下痛点：

1. 普通文本编辑器修改 M3U 容易破坏 URL 查询参数（顺序、空参数、特殊编码等）
2. 缺少可视化分类与拖拽排序
3. 播放时需要携带特定 User-Agent / Referer 才能正常出流
4. 需要快速预览源是否可用

### 开发环境

| 项目 | 版本 / 说明 |
|------|-------------|
| 语言 | Kotlin |
| 构建工具 | Gradle（AGP 8.13.0） |
| Kotlin | 2.1.0 |
| JDK | 17 |
| minSdk | 28 |
| targetSdk | 34 |
| compileSdk | 36 |
| UI | 传统 View + ViewBinding |
| 播放器 | AndroidX Media3 ExoPlayer 1.3.1 + Jellyfin media3-ffmpeg-decoder 1.3.1+2 |
| 其他 | AppCompat、Material、ConstraintLayout |

本地 FFmpeg AAR 优先使用 `app/libs/media3-ffmpeg-decoder-1.3.1+2.aar`，若损坏则自动回退到 Maven Central 同版本。

### 技术原理

1. **数据模型**  
   - `M3UFile`：包含头部参数列表与分类列表  
   - `Category`：分类名 + 频道列表  
   - `Channel`：显示名、原始 URL 行（`originalUrl`）、EXTINF 属性、User-Agent、Referer 等

2. **解析与导出（核心设计）**  
   - 解析时**完整保留 URL 行原始内容**（仅去除首尾空白），避免重编码导致参数丢失或顺序变化  
   - 播放与导出一律优先使用 `Channel.originalUrl`（通过 `getFullUrl()`）  
   - 支持 `#EXTVLCOPT:http-user-agent` 与 `#EXTVLCOPT:http-referer` 解析并绑定到频道  
   - 正确处理 BOM、空行、各种 EXTINF 属性

3. **UI 与交互**  
   - RecyclerView + 自定义 Adapter 展示「头部参数 → 分类 → 频道」扁平列表  
   - ItemTouchHelper 实现拖拽排序  
   - 勾选模式支持批量操作与去重

4. **播放**  
   - 使用 Media3 ExoPlayer，配合 FFmpeg 扩展以获得更广的编码支持  
   - 播放时传入频道级 User-Agent 与 Referer

### 免责声明

1. 本工具仅供个人学习、研究与合法用途使用。  
2. 用户应自行确保所导入、编辑、播放的直播源内容符合当地法律法规，不得用于传播违法、侵权内容。  
3. 开发者不对用户使用本工具产生的任何直接或间接后果承担责任，包括但不限于源失效、播放异常、法律风险等。  
4. 本项目不提供任何直播源，亦不鼓励或协助破解、盗版等行为。  
5. 使用本软件即表示您已阅读并同意本免责声明。

---

## English

### Project Introduction

**m3uToolbox** is a lightweight Android application for **visually creating and customizing local M3U live stream playlist files**.

It is designed for users who need to organize and edit IPTV / online live stream lists. The app provides an intuitive interface to import, edit, reorder, deduplicate, and export standard M3U playlists, with a built-in player for quick preview.

Typical use cases:
- Personal management of local IPTV sources (.m3u / .m3u8)
- Batch editing of channel names, groups (`group-title`), logos (`tvg-logo`), `tvg-id`, etc.
- Setting per-channel User-Agent and Referer HTTP headers
- Importing from local files or online URLs, then exporting for use in other players (VLC, Kodi, IPTV clients, etc.)
- Quickly checking whether a source works (built-in playback preview)

### Runtime Environment

- **Platform**: Android
- **Minimum OS**: Android 9.0 (API 28)
- **Target OS**: Android 14 (API 34)
- **Permissions**: Internet (online import & playback), file access (import/export)
- **Network**: Supports HTTP/HTTPS streams; cleartext traffic is allowed

### Main Features

- **Import**: Local file or online URL
- **Export**: Save the current playlist as a standard M3U file
- **Header parameter management**: Edit global parameters on the `#EXTM3U` line
- **Category management**: Add / rename / delete categories (mapped to `group-title`)
- **Channel management**:
  - Add, edit, delete channels
  - Manual entry or paste raw M3U snippets for auto-parsing
  - Edit `tvg-id`, `tvg-name`, `tvg-logo`, `group-title`, and stream URL
  - Per-channel User-Agent and Referer
- **Drag-and-drop reordering**
- **Multi-select & deduplication**
- **Built-in player**: Media3 ExoPlayer with FFmpeg extension decoder, supporting HLS, RTSP, and more

### Development Goals

The project aims to provide a **simple, reliable, and highly URL-compatible** M3U editor that addresses common pain points:

1. Plain-text editors often break URL query parameters (order, empty parameters, special encodings)
2. Lack of visual category management and drag-and-drop sorting
3. Some streams require specific User-Agent / Referer headers
4. Need for quick source availability checks

### Development Environment

| Item | Version / Notes |
|------|-----------------|
| Language | Kotlin |
| Build | Gradle (AGP 8.13.0) |
| Kotlin | 2.1.0 |
| JDK | 17 |
| minSdk | 28 |
| targetSdk | 34 |
| compileSdk | 36 |
| UI | Classic Views + ViewBinding |
| Player | AndroidX Media3 ExoPlayer 1.3.1 + Jellyfin media3-ffmpeg-decoder 1.3.1+2 |
| Others | AppCompat, Material, ConstraintLayout |

The local FFmpeg AAR (`app/libs/media3-ffmpeg-decoder-1.3.1+2.aar`) is preferred; if invalid, the build falls back to the same version from Maven Central.

### Technical Principles

1. **Data Model**  
   - `M3UFile`: header parameters + list of categories  
   - `Category`: name + list of channels  
   - `Channel`: display name, original URL line (`originalUrl`), EXTINF attributes, User-Agent, Referer, etc.

2. **Parsing & Export (Core Design)**  
   - The URL line is preserved **byte-for-byte** (except surrounding whitespace) to avoid re-encoding issues  
   - Playback and export always prefer `Channel.originalUrl` (via `getFullUrl()`)  
   - Supports parsing `#EXTVLCOPT:http-user-agent` and `#EXTVLCOPT:http-referer` and binding them to channels  
   - Correctly handles BOM, empty lines, and various EXTINF attributes

3. **UI & Interaction**  
   - RecyclerView + custom Adapter for a flat list of “headers → categories → channels”  
   - ItemTouchHelper for drag-and-drop reordering  
   - Selection mode for batch operations and deduplication

4. **Playback**  
   - Media3 ExoPlayer with FFmpeg extension for broader codec support  
   - Per-channel User-Agent and Referer are applied at playback time

### Disclaimer

1. This tool is intended solely for personal learning, research, and lawful use.  
2. Users are solely responsible for ensuring that any imported, edited, or played content complies with applicable laws and regulations. Do not use it to distribute illegal or infringing material.  
3. The developer accepts no liability for any direct or indirect consequences arising from the use of this software, including but not limited to source failures, playback issues, or legal risks.  
4. This project does not provide any live stream sources and does not encourage or assist piracy or circumvention.  
5. By using this software, you acknowledge that you have read and agree to this disclaimer.

---

### License

This project is provided as-is for educational and personal use. Please respect copyright and local laws when handling playlist content.
