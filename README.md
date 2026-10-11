# aod-bridge-apk · ColorOS 16 息屏用量显示（LSPosed API 102 模块）

把 **workbuddy2api-hub 的 token 用量**显示在 ColorOS 16 的「息屏显示（AOD）」上。
数据经 [aod-relay](https://github.com/fffeng99999/aod-relay) 中转取得——手机只持有中转专用令牌，
hub 的面板密码永远留在服务器。

```
[App 进程] PollService 每分钟 GET {relay}/aod/usage（Bearer 令牌）
    └─ 广播 io.aodbridge.USAGE（setPackage=com.android.systemui）
[SystemUI 进程] 模块 hook AodRecord#startShow，把 TextView 注入 AOD 根布局
```

## 功能

| 功能 | 说明 |
|---|---|
| 标题行 | AOD 最上方固定显示「Cyberfox AI API Usage」（字号更大） |
| 数据行 | 今日 / 本周 / 本月 tok、请求｜错误、输入｜输出、缓存命中——App 内**逐行勾选，默认全选** |
| 自动布局 | 以原生息屏内容与屏下指纹位置为界，自动选最合适的空隙落位（无需手动设位置） |
| 防烧屏 | 每分钟轻微移动显示位置（默认开） |
| 息屏保活 | 拦下系统「息屏到点熄灭」，按本模块的显示时长走（默认开） |
| 显示时长 | 锁屏后 30 秒 / 1 / 5 / 10 / 30 分钟 / **保持显示**（默认保持，与系统 AOD 时长相互独立） |
| 指纹图标 | 隐藏息屏常亮的屏下指纹图标防烧屏（默认开，不影响指纹解锁） |
| 未连接提示 | 未连上中转显示「⚠ 未连接中转」，从未取到数据显示「等待数据」，缓存兜底加「⚠缓存」 |
| 字段级失败 | 某项数据在 hub 侧读取失败时，AOD 对应位置显示 ❌，其余照常显示 |
| 重启系统界面 | App 内一键 `su` 重启 SystemUI，改完设置立即生效 |
| 网络 | 局域网 http 与域名 https（反向代理）都支持；裸地址自动补协议（IP→http，域名→https） |

界面为 Miuix 风格。

## 作用域（LSPosed 里勾选什么）

`META-INF/xposed/scope.list` 只有 **`com.android.systemui`**：ColorOS 的 AOD 视图
（`com.oplus.systemui.aod.*`）在 SystemUI 进程里渲染；`com.oplus.aod` 只是 AOD 设置项的宿主，不需要勾选。

`META-INF/xposed/module.prop`：`minApiVersion=102`、`targetApiVersion=102`、`staticScope=true`
（对应 LSPosed API 102 / libxposed）。

## 安装

1. 先部署中转（[aod-relay](https://github.com/fffeng99999/aod-relay)：服务器 Docker，或局域网 PC 都行）；
2. 安装 APK（本仓库 Releases 页下载，或 `adb install aodbridge-vX.Y.Z.apk`）；
3. LSPosed 管理器 → 启用模块「AOD Bridge」→ 作用域勾选「系统界面」→ 重启系统界面；
4. 打开 AOD Bridge：填中转地址（如 `http://192.168.1.100:8790` 或 `https://aod.example.com`）
   与中转令牌 → 「立即测试取数」→「保存并启动轮询」；
5. 锁屏息屏：AOD 上应出现标题与用量行。

## 构建

仓库主路径是**零 Gradle** 流水线（`build.sh` / `build.ps1`），CI 用的也是它；
另外保留 `settings.gradle.kts` / `app/build.gradle.kts` 以便用 Android Studio 打开（需联网拉 AGP）。

### GitHub Actions（推荐）

推送到 `main` 或手动触发 → 构建产物在本次 run 的 Artifacts；
打 `v*` 标签 → 自动创建 Release 并附上 APK 与 `SHA256SUMS.txt`。

- 版本号：标签 `vX.Y.Z` → versionName `X.Y.Z`；其他构建 → `0.0.0-ci.<run_number>`；
  versionCode 恒为 `100 + run_number`（单调递增，保证能覆盖安装本地开发版）。
- 手动触发时可在 Actions 页面填 `version_name` / `version_code` 覆盖。

签名 Secrets（Settings → Secrets and variables → Actions，可选但强烈建议）：

| Secret | 说明 |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | keystore 文件的 base64：`base64 -w0 aodbridge.keystore` |
| `ANDROID_KEYSTORE_PASSWORD` | keystore 口令 |
| `ANDROID_KEY_ALIAS` | 密钥别名（本工程默认 `aodbridge`） |
| `ANDROID_KEY_PASSWORD` | 别名口令（PKCS12 通常与 keystore 口令相同） |

> 未配置时 CI 会临时生成密钥签名，能装但不能覆盖正式版（签名不同，Android 会拒绝升级安装）。

### 本地（Windows，离线）

```powershell
# 需要 Android SDK（platforms;android-36 + build-tools;36.x）与 JDK 17+
# SDK 定位顺序：-AndroidSdk > %ANDROID_SDK_ROOT% > %ANDROID_HOME% > 同级/上级 android-sdk
powershell -ExecutionPolicy Bypass -File build.ps1 -VersionName 0.2.2 -VersionCode 13
# 产物：dist\aodbridge-v0.2.2.apk（keystore 缺失时自动生成 aodbridge.keystore）
```

### 本地 / 任意 Linux（与 CI 同一脚本）

```bash
export ANDROID_SDK_ROOT=/path/to/android-sdk
VERSION_NAME=0.2.2 VERSION_CODE=13 bash build.sh
```

## 目录

| 路径 | 说明 |
|---|---|
| `app/src/main/java/io/aodbridge/app/` | App 进程：设置界面、轮询服务、取数客户端、文案格式化 |
| `app/src/main/java/io/aodbridge/hook/` | SystemUI 进程：注入、自动布局、防烧屏、指纹图标、息屏保活、诊断 |
| `app/src/main/java/io/aodbridge/common/Bridge.java` | 两个进程间的广播契约（字段 / 状态 / 文案） |
| `app/src/main/resources/META-INF/xposed/` | `module.prop`、`java_init.list`、`scope.list` |
| `build.sh` / `build.ps1` | 零 Gradle 构建脚本（Linux/CI 与 Windows） |
| `third_party/libxposed-api/` | libxposed API 102 源码（Apache-2.0，编译期依赖，不进 APK） |
| `vendor/libxposed-stubs/` | `androidx.annotation` 等最小注解桩（离线编译用） |

## 排错

- LSPosed 日志过滤 tag `AODBridge`：`install done, hooks=N`（N=0 表示锚点类没找到）、
  `view injected: ...`（已注入）；
- 轮询停 / 通知消失：给应用「允许后台运行」；
- 局域网 http 连不上：清单已置 `android:usesCleartextTraffic="true"`，检查手机与中转同网段、8790 端口放行；
- 域名 https 连不上：确认反代指向中转 8790 且没有改路径前缀（只认 `/aod/usage`、`/aod/health`）。

## 许可

本项目 MIT，见 [LICENSE](LICENSE)。
`third_party/libxposed-api/` 来自 [libxposed/api](https://github.com/libxposed/api)（Apache-2.0，
全文见 [third_party/LICENSE-APACHE-2.0.txt](third_party/LICENSE-APACHE-2.0.txt)）。
