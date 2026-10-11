# third_party —— 编译期依赖（不进 APK）

| 目录 / 文件 | 来源 | 版本 | 许可 |
|---|---|---|---|
| `libxposed-api/` | <https://github.com/libxposed/api>（Maven Central `io.github.libxposed:api:102.0.0` 的 sources jar 解包） | 102.0.0 | Apache License 2.0 |
| `LICENSE-APACHE-2.0.txt` | Apache-2.0 许可证全文 | — | — |

另见仓库根的 `vendor/libxposed-stubs/`：`androidx.annotation` 与 `io.github.libxposed.annotation`
的最小注解桩，仅用于**离线编译**（真正的实现由 LSPosed 框架在模块 classloader 中提供）。

上述类全部以 **compileOnly / classpath** 方式引用，**不打包进 APK**：
构建后 APK 内只有 `io.aodbridge.*`（可用 `unzip -l` 与 dex 工具复核）。
