# NFO for Android

NFO for Android 是一款给 Kodi 电影资料库使用的安卓刮削器。选择本地电影目录后，可用 TMDB 或 TheTVDB 匹配影片，把 NFO 和图片写到对应视频旁边，也可以查找并下载中文字幕。应用默认使用简体中文，支持 Android 8.0（API 26）及以上版本。

[下载最新签名版 APK](https://github.com/AzurMe/NFO-for-Android/releases/latest) · [版本更新记录](#版本更新记录)

在侧边栏选择 **外观**，可跟随系统或手动切换浅色与深色。浅色使用浅蓝和白色，深色使用黑色和灰色。

## 电影海报墙

首页从授权目录读取本地影片资料，按每行两张海报竖向排列。它显示已有有效电影 NFO、海报和背景图的影片。顶部按 Kodi NFO 的 `<genre>` 字段筛选，显示常用电影类型，其余可在 **更多类型** 中选择。应用打开、刮削或重命名后会自动刷新。[Kodi 电影 NFO 字段](https://kodi.wiki/view/NFO_files/Movies)

点海报即可查看本地 NFO 中的剧情、上映日期、片长、评分、类型、标签和演职员。详情在本地读取，无需联网。

详情中的 **播放预告片** 优先打开同目录的 `<视频名>-trailer.mp4` 等本地预告片，也能播放 NFO 中指向视频或 HLS 的 HTTPS 链接。它们使用 Android 开源系统的 VideoView，格式支持取决于设备解码器。TMDB 的影片视频接口提供预告片的站点和视频编号，应用把匹配到的 YouTube 编号写为 Kodi 支持的 `<trailer>` 链接。应用内通过官方 YouTube 播放器播放，受网络、地区和视频嵌入权限影响，可点 **在 YouTube 打开**。[TMDB 视频接口](https://developer.themoviedb.org/reference/movie-videos) · [Kodi 预告片规则](https://kodi.wiki/view/Trailers) · [Android VideoView](https://developer.android.com/reference/android/widget/VideoView) · [YouTube 嵌入播放器](https://developers.google.com/youtube/iframe_api_reference)

从侧边栏切换 **电影刮削器**、**字幕下载器** 和 **电影推送**。刮削和字幕下载共用同一个已授权目录。

## 电影推送

1. 点顶部菜单按钮，打开侧边栏，切换到 **电影推送**。近 30 天的影片、评分、简介和海报直接来自 IMDb，推荐无需 TMDB 凭据。点 **影片详情** 可以展开 IMDb 提供的简介和类型。IMDb 网页无法访问时，应用会显示该来源的错误。[IMDb 高级搜索](https://www.imdb.com/search/)
2. **热门**按 IMDb 投票数排序，**高分**显示评分至少 7.0 的影片并按分数排序。
3. 点 **查找下载链接**，合并海盗湾按 IMDb ID 和片名、年份返回的相关记录，并按做种人数从高到低排序。部分查询超时后，仍会显示已取得的结果，并可点 **重试**。查询达到来源的单次返回上限时会显示提示。[APiBay](https://apibay.org/)
4. 点 **筛选**，可组合分辨率、HDR 格式、容量上限、最少做种数和标题关键字，也可一键使用 **2160p · HDR10 · 小于 40 GB** 预设。容量按十进制 GB 比较，规格以发布标题标注为准。选中候选后可复制磁力链接或在已安装的应用中打开。

## 安装和刮削

1. 从 Releases 的 **Assets** 下载最新的 `KodiMovieScraper-<版本>.apk`。Android 如果提示安装权限，可为打开 APK 的应用开启「允许安装未知应用」。
2. 打开应用，点 **设置搜索路径**，在系统文件夹选择器里选中 `内部存储 / Movies` 或其他可写目录，再授权访问。应用会记住该目录并扫描其子文件夹。
3. 在 **影片资料来源** 选择 TMDB 或 TheTVDB，填入对应凭据并选好资料语言。凭据由 Android Keystore 保护并保存在本机。
4. 点 **一键刮削**。应用先遍历选中的目录，检查全部影片的本地电影 NFO、海报和背景图，再刮削没有资料或资料不完整的影片。资料全部齐全时可直接完成影片资料检查，无需联系资料服务器。
5. 可以在 **刮削设置** 启用 **更新待刮削影片的已有资料**。更新时保留旧 NFO 里的观看记录、续播点、个人评分、排序标题、标签和自定义字段。遇到无法安全解析的旧 NFO 时，会保留原文件并提示处理结果。
6. 同名或自动匹配不明确的影片可以点 **手动匹配影片**，用片名、年份、所选来源的电影 ID 或 IMDb ID 查找。手动确认写入可刷新资料齐全的影片，也可补演员头像和备用图。

使用 TMDB 时，同一凭据可同时处理最多 3 部待刮削影片。应用进程给 TMDB API 请求统一限速为约每秒 8 次，遇到 HTTP 429 会退避。TMDB 官方的限流会变化。TheTVDB 批量刮削按顺序执行。[TMDB 限流说明](https://developer.themoviedb.org/docs/rate-limiting)

**刮削记录**显示本次任务的进展，开始新任务时会清空这一栏。点旁边的 **历史日志** 可查看保存在设备上的最近 200 条记录，重启应用后仍可查看。

### 连接检测

**测试 TMDB 连接**可以在凭据栏留空时使用。它分别检测 `api.themoviedb.org` 和 `image.tmdb.org`，显示 HTTP 状态、耗时，以及 DNS、超时或 TLS 等错误。未带凭据的 API 请求收到 HTTP 401 表示服务器有响应。填入凭据后，另有一栏显示凭据校验结果。

### 资料来源

- **TMDB** 是默认来源。填写自己的 **API Key** 或 **API Read Access Token**。[TMDB API 设置](https://www.themoviedb.org/settings/api) · [TMDB 认证说明](https://developer.themoviedb.org/docs/authentication-application)
- **TheTVDB** 可独立使用。选择后，点 **配置 TheTVDB**，填写自己的 **v4 API Key**，订阅密钥还需填写对应 **PIN**。用同卡片里的 **测试连接** 验证凭据。[TheTVDB 密钥设置](https://www.thetvdb.com/dashboard/account/apikey) · [TheTVDB API 文档](https://thetvdb.github.io/v4-api/)
- 两个来源使用相同的 Kodi NFO 格式、图片命名和字幕流程。TheTVDB 的翻译、演职员和图片取决于该片在来源中的覆盖情况。缺少本地海报或背景图会提示待补。TheTVDB 的热度值不写入影片评分。需要评分和合集时，可在 **配置 TheTVDB** 启用 **使用已配置的 TMDB 补齐评分和合集**。应用会匹配影片编号后再补充这些字段，已有 NFO 评分仍会保留。
- 切换资料来源后，可以使用 **手动匹配影片** 重新生成资料齐全的旧 NFO。

Metadata and artwork provided by TheTVDB.com. [TheTVDB](https://www.thetvdb.com/)

## 下载中文字幕

在侧边栏打开 **字幕下载器**。开启 **随一键刮削和手动匹配下载**，或点 **仅下载字幕** 给已有电影补字幕。仅下载字幕可独立运行，无需填写 TMDB 凭据。

默认开启 **跳过已有外挂字幕的电影**。应用先扫描电影目录及 `Subs`、`Subtitles` 等字幕子目录，已有任一语言的非空外挂字幕就跳过。多部影片共用一个目录时按视频文件名识别对应的字幕。视频内嵌字幕不参与检查。关闭开关后可继续为已有其他字幕的影片补下载中文字幕。

开启随任务下载后，影片资料和字幕可并行处理。每部影片的记录先显示资料结果，再显示字幕结果。被跳过资料刮削的电影仍可下载字幕。

应用按以下顺序查找，每部电影从第一个有可用结果的来源下载最多 3 条。片名匹配的结果可在 Kodi 播放时切换，方便选择与片源时间轴最合适的一条。

1. **射手影音**按本地视频文件指纹匹配，无需密钥。请求包含文件名和四段文件指纹。[射手影音](https://www.shooter.cn/) · [SPlayer 指纹源码](https://github.com/tomasen/splayer/blob/master/Thirdparty/pkg/trunk/sphash/sphash/sphash.cc#L215)
2. **伪射手**可在 **设置字幕来源** 中填写用户面板提供的 API Token。字幕服务由 ASSRT 提供。[ASSRT](https://assrt.net/) · [API 文档](https://secure.assrt.net/api/doc)
3. **SubDL**可填写自己的 API Key，按电影 ID 或片名及年份查找简体和繁体字幕。[SubDL](https://subdl.com/) · [API 文档](https://subdl.com/developers)

出现网络报错时，可以在 **字幕下载器** 点 **测试伪射手连接**。日志分别显示主域名和官方备用域名的状态。保存 Token 后再测试，还可确认凭据是否有效。搜索、获取详情或下载时遇到连接重置，应用会重新建立 HTTPS 连接并重试。主域名持续出错时，查询会尝试官方备用线路。[ASSRT API 文档](https://secure.assrt.net/api/doc)

主域名在带 Token 请求时反复被重置，连接检测会再发一次无 Token 的对照请求，并在报告和日志中显示结果。

## 导出调试日志

在侧边栏打开 **调试日志**，开启 **生成调试日志**，再重现问题。点 **导出 .log 文件**，在系统文件选择器里选保存位置。开关默认关闭。本机轮换保留最近约 2 MB，关闭生成后仍可导出。调试记录包括时间、运行阶段、HTTP 状态、耗时和错误位置。日志只收集结构化诊断字段，凭据、完整 URL 和影片路径留在记录范围之外。

支持 SRT、ASS、SSA 和 VTT。应用在取得有效字幕后才创建 `Subs` 文件夹，按视频文件名（去掉扩展名）保存，例如 `feature-cut.CHN.srt`、`feature-cut.CHN.2.srt`。刮削记录会列出新保存字幕的来源和文件名。已有文件会保留，重复内容会跳过并标注本次匹配到的来源。Kodi 会扫描 `Subs`。`.CHN` 是本应用的命名标记，Kodi 按首选字幕语言自动选中文时使用的是 `.zh` 等语言代码，可在播放时手动选择 `.CHN` 字幕。[Kodi 外挂字幕扫描源码](https://github.com/xbmc/xbmc/blob/Omega/xbmc/Util.cpp#L2055) · [Kodi 语言代码](https://github.com/xbmc/xbmc/blob/Omega/xbmc/utils/LangCodeExpander.cpp#L890)

## 文件整理与重命名

电影刚下载好即可在首页点 **整理**，也可从 **电影搜索路径** 区打开 **整理与重命名**。应用从本地文件夹或视频名称识别片名和年份，资料不足时可读现有 NFO 辅助判断。弹窗逐部列出电影文件夹和主视频的原名称、新名称，点 **确认重命名** 后才执行。以 `Dune.Part.One.2021.2160p.WEB-DL.HDR.HEVC` 为例，文件夹和主视频会统一为 `Dune.Part.One.2021`，视频保留原 `.mp4` 或 `.mkv` 扩展名。

整理会保留文件内容，并随视频改名同步其可识别的 Kodi 旁置文件和字幕。片名或年份无法确定、多个主视频、分段影片和同名冲突都会列入跳过项。已经加入 Kodi 资料库的影片改名后，可以更新资料库使其读取新路径。

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

- **NFO** 可包含片名和原名、剧情、上映日期、片长、评分与票数、类型、分级、制作公司、合集、导演、编剧、演员及角色、TMDB 和 IMDb 编号以及预告片。更新时保留已有标签，从此版本起，来源关键词不自动加入 Kodi 标签。发现本地预告片时优先使用本地文件。音视频轨道和 HDR 等媒体旗标由 Kodi 读取实际视频文件。
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

## 版本更新记录

### [v1.13.0](https://github.com/AzurMe/NFO-for-Android/releases/tag/v1.13.0)

- 海报墙按 Kodi NFO 的电影类型筛选。
- 字幕任务先检查本地外挂字幕，默认跳过已有外挂字幕的电影，可在字幕下载器关闭。
- 伪射手主线路反复重置时，连接检测增加无 Token 对照。

### [v1.12.0](https://github.com/AzurMe/NFO-for-Android/releases/tag/v1.12.0)

- 海报墙卡片只显示海报。点按后从本地 NFO 读取影片详情。
- 从影片详情播放本地预告片、HTTPS 视频或 NFO 里的 YouTube 预告片。
- 电影推送直接读取 IMDb 的近 30 天热门与高分影片，海报从 IMDb 的图片服务加载。

### [v1.11.0](https://github.com/AzurMe/NFO-for-Android/releases/tag/v1.11.0)

- TMDB 最多并行刮削 3 部影片，共用 API 速率限制和 HTTP 429 退避。
- 海报墙按 Kodi 的 NFO 标签筛选，首页新增整理入口，可在首次刮削前使用。
- 伪射手诊断区分 HTTP 响应、连接重置和 TLS 对端身份校验失败。

### [v1.10.0](https://github.com/AzurMe/NFO-for-Android/releases/tag/v1.10.0)

- 新增首页海报墙，每行两张海报，可按本地标签和类型筛选。
- 电影刮削器和字幕下载器拥有独立的侧边栏入口。
- 新增可开关的本机调试日志和 `.log` 导出。

### [v1.9.1](https://github.com/AzurMe/NFO-for-Android/releases/tag/v1.9.1)

- 重命名确认弹窗分别显示电影文件夹和主视频改名前后的名称。

### [v1.9.0](https://github.com/AzurMe/NFO-for-Android/releases/tag/v1.9.0)

- 一键刮削先完成本地资料检查和筛选，再处理未刮削或资料不完整的影片。
- 新增电影文件夹和视频的批量重命名预览，按片名和年份整理并同步匹配的旁置资料。
- 改进伪射手连接重置后的 HTTPS 恢复和网络诊断。

### [v1.8.0](https://github.com/AzurMe/NFO-for-Android/releases/tag/v1.8.0)

- 影片资料可以选择 TMDB 或 TheTVDB，按同一套规则生成 Kodi NFO 和本地图片。
- TheTVDB 支持独立凭据和连接测试，可选用已配置的 TMDB 补齐评分和合集。

### [v1.7.0](https://github.com/AzurMe/NFO-for-Android/releases/tag/v1.7.0)

- 新增 **跳过已刮削完成的电影** 开关，通过本地 NFO、海报和背景图判断资料是否齐全。
- 影片资料和字幕可并行处理，按每部影片先记录资料结果，再记录字幕来源和结果。

### [v1.6.2](https://github.com/AzurMe/NFO-for-Android/releases/tag/v1.6.2)

- 开始新任务时清空界面上的刮削记录，本地日志继续追加。点 **历史日志** 可查看最近 200 条。

### [v1.6.1](https://github.com/AzurMe/NFO-for-Android/releases/tag/v1.6.1)

- 刮削记录追加保存，保留最近 200 条，重新运行任务或重启应用后仍可查看。
- 新保存的字幕记录会标明实际来源和文件名，便于区分射手影音、伪射手和 SubDL。
- 修复 apibay.org 的单次超时提前结束查询的问题，保留已收到的结果并可直接重试。

### [v1.6.0](https://github.com/AzurMe/NFO-for-Android/releases/tag/v1.6.0)

- 更新刮削页和电影推送页的标题栏、卡片与操作按钮，简化信息层级。
- 加入浅蓝白色和黑灰深色主题，侧边栏可跟随系统或手动切换，弹窗和输入控件也会随外观变化。

### [v1.5.0](https://github.com/AzurMe/NFO-for-Android/releases/tag/v1.5.0)

- 海盗湾合并 IMDb ID、片名和年份查询，默认显示来源返回的全部相关结果，去重后按做种人数排序。
- 增加分辨率、HDR 格式、容量、做种数和关键字筛选，并保留 2160p、HDR10、小于 40 GB 预设。
- 电影推送增加海报、简介、类型标签和影片详情。

### [v1.4.1](https://github.com/AzurMe/NFO-for-Android/releases/tag/v1.4.1)

- 伪射手搜索或详情请求遇到瞬时网络错误时，先重试当前线路一次，再尝试官方备用线路。备用线路 HTTPS 失败后，本轮刮削会暂停使用它。
- 海盗湾查询超时或连接中断时最多自动重试一次。查询窗口可以手动重试，并显示连接、域名解析、HTTPS 或 HTTP 错误的具体类型。

### [v1.4.0](https://github.com/AzurMe/NFO-for-Android/releases/tag/v1.4.0)

- 新增侧边栏和「电影推送」，可在刮削器与最近上映的电影列表之间切换。
- 根据 TMDB 上映日期和 IMDb 官方评分、票数展示近 30 天影片，可切换热门和高分排序。
- 按需查询海盗湾，筛选明确标注 2160p、HDR10 且小于 40 GB 的磁力链接，供选择、复制或打开。

### [v1.3.2](https://github.com/AzurMe/NFO-for-Android/releases/tag/v1.3.2)

- 伪射手 API 连接中断时自动尝试官方 HTTPS 备用域名，并在日志中区分搜索、获取详情和文件下载的失败。
- 新增「测试伪射手连接」，可分别检查两条 API 线路和 Token。
- 修复「仅下载字幕」对 `Dune.Part.One.2021` 一类别名的匹配，按已知年份补查 `Dune`。

### [v1.3.1](https://github.com/AzurMe/NFO-for-Android/releases/tag/v1.3.1)

- 在 README 补齐首版 APK 至今的更新记录，并为各 GitHub 发行版添加下载入口。

### [v1.3.0](https://github.com/AzurMe/NFO-for-Android/releases/tag/v1.3.0)

- 新增中文字幕下载，可随刮削运行，也可单独给已有影片补字幕。
- 优先用射手影音按视频指纹匹配，可选填伪射手或 SubDL 凭据扩展搜索。
- 支持 SRT、ASS、SSA 和 VTT。字幕写入 `Subs`，按 `电影名.CHN.后缀` 命名，同名时加编号，重复内容会跳过。

### [v1.2.0](https://github.com/AzurMe/NFO-for-Android/releases/tag/v1.2.0)

- 更新 NFO 时保留观看记录、续播点、个人评分、排序标题、标签和自定义字段，并同步已有的 `movie.nfo` 和分段影片的首段 NFO。
- 由 Kodi 读取影片的音视频轨道和媒体信息。优先采用本地预告片，并改进 TMDB 在线预告片的语言回退。
- 增加可选的备用背景图和 Kodi 合集图片下载，修复 Android 9 的存储重命名兼容问题。

### [v1.1.1](https://github.com/AzurMe/NFO-for-Android/releases/tag/v1.1.1)

- 更新桌面和应用内图标，以场记板和折角 NFO 文件为主要图形。
- 支持 Android 自适应图标和 Android 13 起的主题图标。

### [v1.1.0](https://github.com/AzurMe/NFO-for-Android/releases/tag/v1.1.0)

- 首次在 GitHub 发布签名 APK，支持 Android 8.0 及以上版本。
- 支持从 `Movies` 选择电影目录，按每部电影的文件夹名和年份匹配，并可手动选片。
- 输出 Kodi 电影 NFO、海报、背景图、可用的额外图片和演员头像。可分别检测 TMDB API、图片服务器和凭据。
- 补充安装、Kodi 导入和源码编译说明。

### v1.0.0（初始 APK）

- 实现 Android 电影刮削，可选择本地目录，通过 TMDB 获取资料，保存 Kodi NFO、海报、背景图和演员资料。

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
