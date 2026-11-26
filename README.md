# NFO for Android

NFO for Android 是一款给 Kodi 电影资料库使用的安卓刮削器。选择本地电影目录后，应用用 TMDB 匹配影片，把 NFO 和图片写到对应视频旁边，也可以查找并下载中文字幕。应用默认使用简体中文，支持 Android 8.0（API 26）及以上版本。

[下载最新签名版 APK](https://github.com/AzurMe/NFO-for-Android/releases/latest)

## 安装和刮削

1. 从 Releases 的 **Assets** 下载最新的 `KodiMovieScraper-<版本>.apk`。Android 如果提示安装权限，可为打开 APK 的应用开启「允许安装未知应用」。
2. 打开应用，点 **设置搜索路径**，在系统文件夹选择器里选中 `内部存储 / Movies` 或其他可写目录，再授权访问。应用会记住该目录并扫描其子文件夹。
3. 填入自己的 TMDB **API Key** 或 **API Read Access Token**，选好资料语言。凭据由 Android Keystore 保护并保存在本机。[TMDB API 设置](https://www.themoviedb.org/settings/api) · [TMDB 认证说明](https://developer.themoviedb.org/docs/authentication-application)
4. 点 **一键刮削**。如果有同名影片或自动匹配不明确，点 **手动匹配影片**，用片名、年份、TMDB ID 或 IMDb ID 查找。手动确认写入会更新该影片的资料和可用图片。
5. 如果需要批量更新旧资料，在设置中启用 **更新已有 NFO 和图片**。应用保留旧 NFO 里的观看记录、续播点、个人评分、排序标题、标签和自定义字段。遇到无法安全解析的旧 NFO 时，该影片会报错并保留原文件。

### 连接检测

**测试 TMDB 连接**可以在凭据栏留空时使用。它分别检测 `api.themoviedb.org` 和 `image.tmdb.org`，显示 HTTP 状态、耗时，以及 DNS、超时或 TLS 等错误。未带凭据的 API 请求收到 HTTP 401 表示服务器有响应。填入凭据后，另有一栏显示凭据校验结果。

## 下载中文字幕

在 **中文字幕** 区开启 **随一键刮削和手动匹配下载**，或点 **仅下载字幕** 给已有电影补字幕。仅下载字幕可独立运行，无需填写 TMDB 凭据。

应用按以下顺序查找，每部电影从第一个有可用结果的来源下载最多 3 条。片名匹配的结果可在 Kodi 播放时切换，方便选择与片源时间轴最合适的一条。

1. **射手影音**按本地视频文件指纹匹配，无需密钥。请求包含文件名和四段文件指纹。[射手影音](https://www.shooter.cn/) · [SPlayer 指纹源码](https://github.com/tomasen/splayer/blob/master/Thirdparty/pkg/trunk/sphash/sphash/sphash.cc#L215)
2. **伪射手**可在 **设置字幕来源** 中填写用户面板提供的 API Token。字幕服务由 ASSRT 提供。[ASSRT](https://assrt.net/) · [API 文档](https://secure.assrt.net/api/doc)
3. **SubDL**可填写自己的 API Key，按电影 ID 或片名及年份查找简体和繁体字幕。[SubDL](https://subdl.com/) · [API 文档](https://subdl.com/developers)

支持 SRT、ASS、SSA 和 VTT。应用在取得有效字幕后才创建 `Subs` 文件夹，按视频文件名（去掉扩展名）保存，例如 `feature-cut.CHN.srt`、`feature-cut.CHN.2.srt`。已有文件会保留，重复内容会跳过。Kodi 会扫描 `Subs`。`.CHN` 是本应用的命名标记，Kodi 按首选字幕语言自动选中文时使用的是 `.zh` 等语言代码，可在播放时手动选择 `.CHN` 字幕。[Kodi 外挂字幕扫描源码](https://github.com/xbmc/xbmc/blob/Omega/xbmc/Util.cpp#L2055) · [Kodi 语言代码](https://github.com/xbmc/xbmc/blob/Omega/xbmc/utils/LangCodeExpander.cpp#L890)

## 文件命名和输出

一部电影一个文件夹时，应用默认优先用文件夹名匹配。例如 `The.Odyssey.2026` 会解析为 **The Odyssey (2026)**，`Dune.Part.One.2021` 会解析为 **Dune Part One (2021)**，并可匹配 TMDB 中的 **Dune (2021)**。散放在根目录的电影按视频文件名匹配。

Kodi 旁置文件按实际视频文件名命名。

```text
Movies/
└── Dune.Part.One.2021/
    ├── feature-cut.mp4
    ├── feature-cut.zh.srt
    ├── feature-cut.nfo
    ├── feature-cut-poster.jpg
    ├── feature-cut-fanart.jpg
    ├── feature-cut-clearlogo.png
    ├── .actors/
    │   └── Actor_Name.jpg
    └── Subs/
        ├── feature-cut.CHN.srt
        └── feature-cut.CHN.2.srt
```

- **NFO** 可包含片名和原名、剧情、上映日期、片长、评分与票数、类型、分级、制作公司、合集、导演、编剧、演员及角色、TMDB 和 IMDb 编号以及预告片。发现本地预告片时优先使用本地文件。音视频轨道和 HDR 等媒体旗标由 Kodi 读取实际视频文件。
- **影片图片**按 TMDB 的素材情况生成，包括海报、背景图、透明标志、横图和无字海报。开启本地演员头像后，每部电影最多下载 20 张头像。NFO 还会记录 TMDB 提供的其他演员资料和可用头像地址。
- **旧版文件名**在单片文件夹内支持 `movie.nfo`。更新时以视频同名的 NFO 为主，已经存在的旧文件名也会同步。分段电影同样会同步已经存在的首段 NFO。
- **备用背景图**可在设置中启用。每部电影最多下载 3 张，命名为 `feature-cut-fanart1.jpg`、`feature-cut-fanart2.jpg` 等。显示多张背景图需要 Kodi 皮肤支持。[Kodi 图片类型](https://kodi.wiki/view/Artwork_types#fanart.23)
- **合集图片**可选择 Kodi 的合集资料目录。Kodi 和本应用需指向同一个目录。应用在目录内按 NFO 中的合集名称创建子目录，并补齐可用的 `poster.jpg` 和 `fanart.jpg`。[Kodi 合集资料目录](https://kodi.wiki/view/Movie_set_information_folder)

## 在 Kodi 中导入

1. 在 Kodi 添加同一电影目录为视频来源，将内容类型设为 **电影**，资料提供者选 **仅本地信息**。
2. 如果一部电影一个文件夹，启用 **电影位于与电影名匹配的独立文件夹**。
3. 更新资料库导入新影片。已有影片可在影片信息页点 **刷新**。如果 Kodi 询问是否忽略本地信息，选择 **否**。
4. 如需显示本地演员头像，开启 Kodi 的 **下载演员缩略图**。如需显示透明标志、横图等额外图片，可把影片图片级别设为 **最大**或自定义。
5. 如需 Kodi 读取分辨率、音轨和 HDR 等媒体信息，开启视频设置中的 **从文件中提取视频信息**。[Kodi 视频设置](https://kodi.wiki/view/Settings/Media/Videos)

Kodi 平时将资料编辑、观看记录和续播点写入自己的资料库。需要把这些状态写回电影目录时，在 Kodi 中将视频资料库设为 **导出到单独文件**，并按需选择覆盖旧文件。刮削器更新时可保留已写入 NFO 的字段。[Kodi 视频资料库导入与导出](https://kodi.wiki/view/Import-export_library/Video)

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
