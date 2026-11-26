# NFO for Android

NFO for Android 是一款给 Kodi 电影资料库使用的安卓刮削器。选择本地电影目录后，应用用 TMDB 匹配影片，把 NFO 和图片写到对应视频旁边。默认使用简体中文，并保留已有的 NFO 和图片。应用支持 Android 8.0（API 26）及以上版本。

[下载最新签名版 APK](https://github.com/AzurMe/NFO-for-Android/releases/latest)

## 安装和刮削

1. 从 Releases 的 **Assets** 下载 `KodiMovieScraper-1.1.0.apk` 或更新版本。Android 如果提示安装权限，可为打开 APK 的应用开启「允许安装未知应用」。
2. 打开应用，点 **设置搜索路径**，在系统文件夹选择器里选中 `内部存储 / Movies` 或其他可写目录，再授权访问。应用会记住该目录并扫描其子文件夹。
3. 填入自己的 TMDB **API Key** 或 **API Read Access Token**，选好资料语言。凭据由 Android Keystore 保护并保存在本机。[TMDB API 设置](https://www.themoviedb.org/settings/api) · [TMDB 认证说明](https://developer.themoviedb.org/docs/authentication-application)
4. 点 **一键刮削**。如果有同名影片或自动匹配不明确，点 **手动匹配影片**，用片名、年份、TMDB ID 或 IMDb ID 查找。手动确认写入时会替换所选影片的同名 NFO 和图片。
5. 如果需要批量替换旧资料，在设置中启用 **覆盖已有 NFO 和图片**。电影和字幕文件会保留在原目录。

### 连接检测

**测试 TMDB 连接**可以在凭据栏留空时使用。它分别检测 `api.themoviedb.org` 和 `image.tmdb.org`，显示 HTTP 状态、耗时，以及 DNS、超时或 TLS 等错误。未带凭据的 API 请求收到 HTTP 401 表示服务器有响应。填入凭据后，另有一栏显示凭据校验结果。

## 文件命名和输出

一部电影一个文件夹时，应用默认优先用文件夹名匹配。例如 `The.Odyssey.2026` 会解析为 **The Odyssey (2026)**，`Dune.Part.One.2021` 会解析为 **Dune Part One (2021)**，并可匹配 TMDB 中的 **Dune (2021)**。散放在根目录的电影按视频文件名匹配。

Kodi 旁置文件按实际视频文件名命名。以下示例里的字幕是原有文件。

```text
Movies/
└── Dune.Part.One.2021/
    ├── feature-cut.mp4
    ├── feature-cut.zh.srt
    ├── feature-cut.nfo
    ├── feature-cut-poster.jpg
    ├── feature-cut-fanart.jpg
    ├── feature-cut-clearlogo.png
    └── .actors/
        └── Actor_Name.jpg
```

- **NFO** 可包含片名和原名、剧情、上映日期、片长、评分与票数、类型、分级、制作公司、合集、导演、编剧、演员及角色、TMDB 和 IMDb 编号、预告片，以及设备能读取的本地音视频和字幕轨道信息。
- **影片图片**按 TMDB 的素材情况生成，包括海报、背景图、透明标志、横图和无字海报。开启本地演员头像后，每部电影最多下载 20 张头像。NFO 还会记录 TMDB 提供的其他演员资料和可用头像地址。

## 在 Kodi 中导入

1. 在 Kodi 添加同一电影目录为视频来源，将内容类型设为 **电影**，资料提供者选 **仅本地信息**。
2. 如果一部电影一个文件夹，启用 **电影位于与电影名匹配的独立文件夹**。
3. 更新资料库导入新影片。已有影片可在影片信息页点 **刷新**。如果 Kodi 询问是否忽略本地信息，选择 **否**。
4. 如需显示本地演员头像，开启 Kodi 的 **下载演员缩略图**。如需显示透明标志、横图等额外图片，可把影片图片级别设为 **最大**或自定义。

[Kodi NFO 文档](https://kodi.wiki/view/NFO_files/Movies) · [Kodi 影片图片文档](https://kodi.wiki/view/Movie_artwork) · [Kodi 图片类型](https://kodi.wiki/view/Artwork_types)

## 从源码构建

需要 JDK 17、Android SDK Platform 35 和 Android SDK Build-Tools 35.0.0。可用 Android Studio 打开仓库，或在配置好 `ANDROID_HOME` 后使用 Gradle Wrapper。

```bash
./gradlew :app:assembleDebug
```

Windows PowerShell 使用 `.\gradlew.bat :app:assembleDebug`。输出为 `app/build/outputs/apk/debug/app-debug.apk`。Android 覆盖安装要求新旧 APK 使用同一签名，升级 GitHub Release 版时请使用后续 Release 的签名 APK。

在 Linux 上，也可以使用 Android SDK 工具生成未签名 APK。

```bash
./build-apk.sh app-unsigned.apk
```

## TMDB 署名

This product uses the TMDB API but is not endorsed or certified by TMDB.

[The Movie Database](https://www.themoviedb.org/)
