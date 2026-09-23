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
覆盖安装要先卸载），release 出未签名包。

## 发布

推 `v<versionName>` 形式的 tag 触发 `.github/workflows/release.yml`：构建 `assembleRelease`、
验签、发成 GitHub 正式 Release（非 prerelease）。tag 必须和 `app/build.gradle.kts` 里的
`versionName` 对齐，workflow 会先检查再构建 —— 正式包的 `versionCode` 发出去就不能往回退。

每次 push 仍走 `.github/workflows/nightly.yml`，出 debug 包，挂在 `nightly-<sha>` 的 prerelease 上。

两套签名密钥各占四个 repository secrets，互不通用（正式密钥绝不签 debug 包）：

| | secrets | 用途 |
| --- | --- | --- |
| 正式发布 | `KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD` | `assembleRelease` |
| Nightly | `NIGHTLY_KEYSTORE_BASE64` / `NIGHTLY_KEYSTORE_PASSWORD` / `NIGHTLY_KEY_ALIAS` / `NIGHTLY_KEY_PASSWORD` | `assembleDebug` |

私钥不进仓库。仓库外另存了一份本地备份（`release.p12` / `nightly.p12` 加各自的
`.properties`），配 secrets 和以后本地出正式包都用它 —— GitHub secrets 写进去就读不出来，
那份备份请自己另处再存一份。

## 许可

MIT，见 [LICENSE](LICENSE)。

## 致谢
https://github.com/neteasecloudmusicapienhanced/api-enhanced

Hydrogen-Music。
