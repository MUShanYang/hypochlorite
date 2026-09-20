# hypochlorite

网易云 Android 播放器。Compose 界面，登录和取流走 weapi / eapi。

仓库根目录就是 Gradle 工程。

## 构建

JDK 17+，Android SDK。在仓库根目录：

```bat
.\gradlew.bat :app:assembleDebug
```

调试包在 `app/build/outputs/apk/debug/app-debug.apk`。

## 能做什么

- 收藏、自建歌单、日推
- 搜歌名 / 歌单 / `music.163.com` 链接
- 扫码或手机号登录，音质跟账号走
- 封面取色、歌词、一起听、播放队列

会话写在应用私有目录，不进仓库。
