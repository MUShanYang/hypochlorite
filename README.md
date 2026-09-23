# hypochlorite

目前为早期开发阶段 部分功能可能无法正常使用

A music player

## 构建

JDK 17+

```bat
.\gradlew.bat :app:assembleDebug
```

仓库里不再带签名密钥。`app/build.gradle.kts` 的 `nightly` signingConfig 因此暂时指向不存在的
文件，配置阶段就会失败 —— 换回可读的密钥（本地 keystore 或 CI secret）之前 build 不出来。

## 许可

MIT，见 [LICENSE](LICENSE)。

## 致谢
https://github.com/neteasecloudmusicapienhanced/api-enhanced

Hydrogen-Music。
