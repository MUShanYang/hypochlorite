# hypochlorite

目前为早期开发阶段 部分功能可能无法正常使用

A music player

## 构建

JDK 17+

```bat
.\gradlew.bat :app:assembleDebug
```

不带签名密钥。要让产物用固定签名（覆盖安装不掉），配这四个环境变量，缺任何一个都按没配处理：

| 变量 | 含义 |
| --- | --- |
| `HYPO_KEYSTORE_FILE` | keystore 路径，相对仓库根或绝对路径 |
| `HYPO_KEYSTORE_PASSWORD` | store 密码 |
| `HYPO_KEY_ALIAS` | key 别名 |
| `HYPO_KEY_PASSWORD` | key 密码 |

不配也能构建：debug 用 AGP 自动生成的 debug keystore（换机器 / 换 CI runner 后签名不同，
覆盖安装要先卸载），release 出未签名包。CI 侧对应四个 repository secrets
`KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`，见
`.github/workflows/nightly.yml`。

## 许可

MIT，见 [LICENSE](LICENSE)。

## 致谢
https://github.com/neteasecloudmusicapienhanced/api-enhanced

Hydrogen-Music。
