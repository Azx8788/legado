I created this project for this application, so that when it's opened, there are book sources available for use. However, I am not capable of any programming myself, so I can only rely on AI to help me complete it. Please forgive me
我希望这个应用可以打开，就可以使用，原本的想法是为其内置书源，但我只是一个没有任何编程能力的普通人，所以我的操作都是由AI告诉我，我来完成的，有错误的地方请见谅

# 常见问题 FAQ

### Q1：为什么这个版本一打开就有书源，官方版没有？
本版本是基于开源项目 legado 的**个人二次修改版**，为了方便使用，
在应用首次启动时自动导入了几个第三方书源，官方原版不会预置任何书源。

### Q2：内置的书源安全吗？会不会有病毒或恶意代码？
书源本质上只是一套"网页抓取规则"（JSON文本），并非可执行程序，
不会在你的设备上运行代码或安装任何东西。但书源指向的网站内容
由第三方网站提供，其安全性、稳定性不由本项目保证，请自行判断。

### Q3：这个APP会收集我的个人信息吗？
不会。本项目仅在原版基础上增加书源导入逻辑，**没有新增任何数据收集、
上传或联网追踪行为**。具体隐私政策请参考原版 `privacyPolicy.md`。

### Q4：能不能通过应用商店自动更新？
不能。由于签名与官方版本不同，无法通过官方更新渠道升级，
需要手动下载新版APK安装覆盖。

### Q5：能否和官方版共存安装？
如果使用的是"共存版(releaseA)"可以同时安装；如果是普通版，
由于包名/签名可能一致，需要先卸载旧版本再安装。

### Q6：书源突然失效了怎么办？
书源依赖的网站可能随时改版或关闭，导致规则失效属于正常现象，
可以自行在"书源管理"里导入新的书源替换。

### Q7：我可以自己添加/删除书源吗？
完全可以，进入「书源管理」页面，可以自由导入、编辑、禁用、删除任何书源，
内置书源不是强制锁定的。

### Q8：使用这个APP看小说合法吗？
本项目不提供、不存储任何小说正文内容，仅提供抓取规则工具。
具体访问的内容合法性取决于书源指向的第三方网站，请自行判断风险
### Q9：这是官方发布的版本吗？
不是。本仓库为个人基于开源协议的非商业修改版，与原作者及官方项目无关，
请勿将此版本的问题反馈到官方仓库。
### Q10：你们会经常维护吗？
学习目标已达成，不再维护
---

# 免责声明

1. 本项目基于 [gedoor/legado](https://github.com/gedoor/legado)
   （GPL-3.0 协议开源）修改，**版权及原始著作权归原作者 gedoor 及其贡献者所有**。
2. 本修改版**仅供个人学习交流使用，不用于任何商业用途，不进行公开推广**。
3. 内置书源为互联网公开链接聚合规则，**版权归原始内容网站/作者所有**，
   本项目不提供、不存储、不传播任何小说正文内容。
4. 因使用本修改版及内置书源产生的任何法律责任，均由使用者自行承担，
   与原作者及本仓库维护者无关。
5. 若权利人认为某书源侵犯其合法权益，请提交 Issue 说明，
   本仓库将在收到通知后及时处理或移除相关内容。
6. 本项目遵循 GPL-3.0 协议，代码保持开源，不提供闭源商用版本。

---

*本版本由个人基于开源协议修改，与原作者 gedoor 无关，如有疑问请勿反馈至官方仓库。*

[![icon_android](https://github.com/gedoor/gedoor.github.io/blob/master/static/img/legado/icon_android.png)](https://play.google.com/store/apps/details?id=io.legado.play.release)
<a href="https://jb.gg/OpenSourceSupport" target="_blank">
<img width="24" height="24" src="https://resources.jetbrains.com/storage/products/company/brand/logos/jb_beam.svg?_gl=1*135yekd*_ga*OTY4Mjg4NDYzLjE2Mzk0NTE3MzQ.*_ga_9J976DJZ68*MTY2OTE2MzM5Ny4xMy4wLjE2NjkxNjMzOTcuNjAuMC4w&_ga=2.257292110.451256242.1669085120-968288463.1639451734" alt="idea"/>
</a>

<div align="center">
<img width="125" height="125" src="https://github.com/gedoor/legado/raw/master/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" alt="legado"/>  
  
Legado / 开源阅读
<br>
<a href="https://gedoor.github.io" target="_blank">gedoor.github.io</a> / <a href="https://www.legado.top/" target="_blank">legado.top</a>
<br>
Legado is a free and open source novel reader for Android.
</div>

[![](https://img.shields.io/badge/-Contents:-696969.svg)](#contents) [![](https://img.shields.io/badge/-Function-F5F5F5.svg)](#Function-主要功能-) [![](https://img.shields.io/badge/-Community-F5F5F5.svg)](#Community-交流社区-) [![](https://img.shields.io/badge/-API-F5F5F5.svg)](#API-) [![](https://img.shields.io/badge/-Other-F5F5F5.svg)](#Other-其他-) [![](https://img.shields.io/badge/-Grateful-F5F5F5.svg)](#Grateful-感谢-) [![](https://img.shields.io/badge/-Interface-F5F5F5.svg)](#Interface-界面-)

>新用户？
>
>软件不提供内容，需要您自己手动添加，例如导入书源等。
>看看 [官方帮助文档](https://www.yuque.com/legado/wiki)，也许里面就有你要的答案。

# Function-主要功能 [![](https://img.shields.io/badge/-Function-F5F5F5.svg)](#Function-主要功能-)
[English](English.md)

<details><summary>中文</summary>
1.自定义书源，自己设置规则，抓取网页数据，规则简单易懂，软件内有规则说明。<br>
2.列表书架，网格书架自由切换。<br>
3.书源规则支持搜索及发现，所有找书看书功能全部自定义，找书更方便。<br>
4.订阅内容,可以订阅想看的任何内容,看你想看<br>
5.支持替换净化，去除广告替换内容很方便。<br>
6.支持本地TXT、EPUB阅读，手动浏览，智能扫描。<br>
7.支持高度自定义阅读界面，切换字体、颜色、背景、行距、段距、加粗、简繁转换等。<br>
8.支持多种翻页模式，覆盖、仿真、滑动、滚动等。<br>
9.软件开源，持续优化，无广告。
</details>

<a href="#readme">
    <img src="https://img.shields.io/badge/-返回顶部-orange.svg" alt="#" align="right">
</a>

# Community-交流社区 [![](https://img.shields.io/badge/-Community-F5F5F5.svg)](#Community-交流社区-)

#### Telegram
[![Telegram-group](https://img.shields.io/badge/Telegram-%E7%BE%A4%E7%BB%84-blue)](https://t.me/yueduguanfang) [![Telegram-channel](https://img.shields.io/badge/Telegram-%E9%A2%91%E9%81%93-blue)](https://t.me/legado_channels)

#### Discord
[![Discord](https://img.shields.io/discord/560731361414086666?color=%235865f2&label=Discord)](https://discord.gg/VtUfRyzRXn)

#### Other
https://www.yuque.com/legado/wiki/community

<a href="#readme">
    <img src="https://img.shields.io/badge/-返回顶部-orange.svg" alt="#" align="right">
</a>

# API [![](https://img.shields.io/badge/-API-F5F5F5.svg)](#API-)
* 阅读3.0 提供了2种方式的API：`Web方式`和`Content Provider方式`。您可以在[这里](api.md)根据需要自行调用。 
* 可通过url唤起阅读进行一键导入,url格式: legado://import/{path}?src={url}
* path类型: bookSource,rssSource,replaceRule,textTocRule,httpTTS,theme,readConfig,dictRule,[addToBookshelf](/app/src/main/java/io/legado/app/ui/association/AddToBookshelfDialog.kt)
* path类型解释: 书源,订阅源,替换规则,本地txt小说目录规则,在线朗读引擎,主题,阅读排版,添加到书架

<a href="#readme">
    <img src="https://img.shields.io/badge/-返回顶部-orange.svg" alt="#" align="right">
</a>

# Other-其他 [![](https://img.shields.io/badge/-Other-F5F5F5.svg)](#Other-其他-)
##### 免责声明
https://gedoor.github.io/Disclaimer

##### 阅读3.0
* [书源规则](https://mgz0227.github.io/The-tutorial-of-Legado/)
* [更新日志](/app/src/main/assets/updateLog.md)
* [帮助文档](/app/src/main/assets/web/help/md/appHelp.md)
* [web端书架](https://github.com/gedoor/legado_web_bookshelf)
* [web端源编辑](https://github.com/gedoor/legado_web_source_editor)

<a href="#readme">
    <img src="https://img.shields.io/badge/-返回顶部-orange.svg" alt="#" align="right">
</a>

# Grateful-感谢 [![](https://img.shields.io/badge/-Grateful-F5F5F5.svg)](#Grateful-感谢-)
> * org.jsoup:jsoup
> * cn.wanghaomiao:JsoupXpath
> * com.jayway.jsonpath:json-path
> * com.github.gedoor:rhino-android
> * com.squareup.okhttp3:okhttp
> * com.github.bumptech.glide:glide
> * org.nanohttpd:nanohttpd
> * org.nanohttpd:nanohttpd-websocket
> * cn.bingoogolapple:bga-qrcode-zxing
> * com.jaredrummler:colorpicker
> * org.apache.commons:commons-text
> * io.noties.markwon:core
> * io.noties.markwon:image-glide
> * com.hankcs:hanlp
> * com.positiondev.epublib:epublib-core
<a href="#readme">
    <img src="https://img.shields.io/badge/-返回顶部-orange.svg" alt="#" align="right">
</a>

# Interface-界面 [![](https://img.shields.io/badge/-Interface-F5F5F5.svg)](#Interface-界面-)
<img src="https://github.com/gedoor/gedoor.github.io/blob/master/static/img/legado/%E9%98%85%E8%AF%BB%E7%AE%80%E4%BB%8B1.jpg" width="270"><img src="https://github.com/gedoor/gedoor.github.io/blob/master/static/img/legado/%E9%98%85%E8%AF%BB%E7%AE%80%E4%BB%8B2.jpg" width="270"><img src="https://github.com/gedoor/gedoor.github.io/blob/master/static/img/legado/%E9%98%85%E8%AF%BB%E7%AE%80%E4%BB%8B3.jpg" width="270">
<img src="https://github.com/gedoor/gedoor.github.io/blob/master/static/img/legado/%E9%98%85%E8%AF%BB%E7%AE%80%E4%BB%8B4.jpg" width="270"><img src="https://github.com/gedoor/gedoor.github.io/blob/master/static/img/legado/%E9%98%85%E8%AF%BB%E7%AE%80%E4%BB%8B5.jpg" width="270"><img src="https://github.com/gedoor/gedoor.github.io/blob/master/static/img/legado/%E9%98%85%E8%AF%BB%E7%AE%80%E4%BB%8B6.jpg" width="270">

<a href="#readme">
    <img src="https://img.shields.io/badge/-返回顶部-orange.svg" alt="#" align="right">
</a>
