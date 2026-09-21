# 发布说明

## 正式签名

正式签名已在本机生成，不能提交到 Git：

- 密钥：`~/.android/anxin-note-release.jks`
- Gradle 配置：项目根目录 `keystore.properties`
- 密码副本：macOS 钥匙串服务 `anxin-note-release-keystore`
- 别名：`anxin-note`
- 证书 SHA-256：
  `5E:F7:69:45:7A:94:22:3E:29:A3:DF:AE:2D:C8:30:95:33:7A:8A:FF:72:89:70:02:1C:8D:CD:95:8E:31:0C:DC`

密钥一旦丢失，已经安装正式版的用户将无法覆盖升级。发布前必须把 JKS 和密码
分别备份到可靠的位置，例如加密移动硬盘与密码管理器。不要只留在当前电脑。

## 构建

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew clean assembleRelease
```

产物：

```text
app/build/outputs/apk/release/app-release.apk
```

验证签名：

```bash
$ANDROID_HOME/build-tools/34.0.0/apksigner verify --print-certs \
  app/build/outputs/apk/release/app-release.apk
```

输出中的证书 SHA-256 必须与上面的正式签名一致，且不能出现 `Android Debug`。

## 发布步骤

1. 更新 `versionCode`、`versionName` 和应用内版本号。
2. 更新 `CHANGELOG.md` 和 Release Notes。
3. 运行 `./gradlew lintRelease testReleaseUnitTest assembleRelease`。
4. 在真机覆盖安装上一正式版本，验证数据保留。
5. 验证通知、通知上的“吃了”、立即备份和从备份恢复。
6. 计算 APK 的 SHA-256。
7. 给源码提交打与版本一致的 tag，例如 `v0.5.3`。
8. 创建 GitHub Release，上传 APK、校验值和升级提示。

## 0.5.3 的一次性迁移提示

此前分发的是 Debug 签名包，不能被正式签名包覆盖。测试用户必须：

1. 在旧版中立即备份。
2. 确认 `下载/安心手记/安心手记-备份.json` 存在。
3. 卸载旧版并安装 0.5.3 正式版。
4. 在首次页面选择“已有备份？从备份恢复”。

从正式签名的 0.5.3 开始，后续版本不要再更换密钥。
