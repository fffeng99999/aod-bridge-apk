# ============================================================================
# aod-bridge-apk / build.ps1 —— 零 Gradle 构建 LSPosed API 102 模块 APK（Windows）
#
# 流水线：javac(libxposed API 源码 + 注解桩) -> javac(模块源码) -> d8
#         -> aapt2 资源编译/链接 -> 组装(classes.dex + META-INF/xposed 三件套)
#         -> zipalign -> apksigner 签名（v1+v2+v3）
#
# 依赖：
#   * Android SDK —— platforms\android-36\android.jar 与 build-tools（aapt2/d8/zipalign/apksigner）
#     定位顺序：-AndroidSdk 参数 > %ANDROID_SDK_ROOT% > %ANDROID_HOME%
#               > 仓库同级/上级 android-sdk > %LOCALAPPDATA%\Android\Sdk
#   * JDK 17+（javac/jar/keytool；PATH > %JAVA_HOME%\bin）
#   * third_party\libxposed-api（libxposed API 102 源码，Apache-2.0，编译期类不进 APK）
#   * vendor\libxposed-stubs（注解桩）
#
# 用法：powershell -ExecutionPolicy Bypass -File build.ps1 -VersionName 0.2.2 -VersionCode 13
# ============================================================================
param(
    [string]$VersionName = "0.1.0",
    [int]$VersionCode = 1,
    [string]$OutFile = "",
    [string]$AndroidSdk = ""
)
$ErrorActionPreference = "Stop"
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

$root = $PSScriptRoot
$buildDir = Join-Path $root "build"
$distDir = Join-Path $root "dist"
if (-not $OutFile) {
    $OutFile = Join-Path $distDir ("aodbridge-v" + $VersionName + ".apk")
}

# ---- 定位 Android SDK ----
$sdk = $AndroidSdk
if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
if (-not $sdk) { $sdk = $env:ANDROID_HOME }
if (-not $sdk) {
    foreach ($cand in @((Join-Path $root "..\android-sdk"), (Join-Path $root "..\..\android-sdk"), ($env:LOCALAPPDATA + "\Android\Sdk"))) {
        if (Test-Path $cand) { $sdk = (Resolve-Path $cand).Path; break }
    }
}
if (-not $sdk -or -not (Test-Path $sdk)) { throw "找不到 Android SDK：请设置 ANDROID_SDK_ROOT，或传 -AndroidSdk <路径>" }
$sdk = (Resolve-Path $sdk).Path

$androidJar = Join-Path $sdk "platforms\android-36\android.jar"
$bt = ""
foreach ($v in @("36.1.0", "36.0.0")) {
    $cand = Join-Path $sdk "build-tools\$v"
    if (Test-Path (Join-Path $cand "aapt2.exe")) { $bt = $cand; break }
}
if (-not $bt) {
    $cand = Get-ChildItem (Join-Path $sdk "build-tools") -Directory -ErrorAction SilentlyContinue |
        Where-Object { Test-Path (Join-Path $_.FullName "aapt2.exe") } | Sort-Object Name | Select-Object -Last 1
    if ($cand) { $bt = $cand.FullName }
}
if (-not $bt) { throw "找不到 build-tools（需要 aapt2/d8/zipalign/apksigner）" }
$aapt2 = Join-Path $bt "aapt2.exe"
$zipalign = Join-Path $bt "zipalign.exe"
$apksigner = Join-Path $bt "apksigner.bat"
$d8 = Join-Path $bt "d8.bat"

$apiSrc = Join-Path $root "third_party\libxposed-api"
$stubsSrc = Join-Path $root "vendor\libxposed-stubs"

function Find-Tool([string]$name) {
    $cmd = Get-Command $name -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    if ($env:JAVA_HOME) {
        $cand = Join-Path $env:JAVA_HOME "bin\$name.exe"
        if (Test-Path $cand) { return $cand }
    }
    throw "找不到工具: $name（安装 JDK 17+ 并加入 PATH，或设置 JAVA_HOME）"
}
$javac = Find-Tool "javac"
$jarTool = Find-Tool "jar"
$keytool = Find-Tool "keytool"

foreach ($required in @($androidJar, $aapt2, $zipalign, $apksigner, $d8)) {
    if (-not (Test-Path $required)) { throw "缺少构建组件: $required" }
}

# 防止误删：build 目录必须位于本工程内
$fullBuild = [System.IO.Path]::GetFullPath($buildDir)
if (-not $fullBuild.StartsWith([System.IO.Path]::GetFullPath($root), [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "build 目录不在工程内，拒绝清理: $fullBuild"
}
if (Test-Path $buildDir) { Remove-Item -Recurse -Force $buildDir }
New-Item -ItemType Directory -Force -Path $buildDir,
    (Join-Path $buildDir "apiClasses"),
    (Join-Path $buildDir "appClasses"),
    (Join-Path $buildDir "dex"),
    $distDir | Out-Null

function Assert-Exit([string]$step) {
    if ($LASTEXITCODE -ne 0) { throw "步骤失败: $step (exit=$LASTEXITCODE)" }
}

Write-Host "==> [1/6] 编译 libxposed API 102（源码 + 注解桩，编译期使用）"
$apiSources = @()
$apiSources += (Get-ChildItem -Recurse -File (Join-Path $apiSrc "io")).FullName
$apiSources += (Get-ChildItem -Recurse -File $stubsSrc -Filter *.java).FullName
& $javac "-source" "17" "-target" "17" "-nowarn" "-classpath" $androidJar "-d" (Join-Path $buildDir "apiClasses") @apiSources
Assert-Exit "javac libxposed api"
& $jarTool cf (Join-Path $buildDir "libxposed-api.jar") -C (Join-Path $buildDir "apiClasses") .
Assert-Exit "jar libxposed api"

Write-Host "==> [2/6] 编译模块源码"
$appSources = (Get-ChildItem -Recurse -File (Join-Path $root "app\src\main\java") -Filter *.java).FullName
$appClasspath = $androidJar + ";" + (Join-Path $buildDir "libxposed-api.jar")
& $javac "-source" "17" "-target" "17" "-nowarn" "-classpath" $appClasspath "-d" (Join-Path $buildDir "appClasses") @appSources
Assert-Exit "javac app"
& $jarTool cf (Join-Path $buildDir "app-classes.jar") -C (Join-Path $buildDir "appClasses") .
Assert-Exit "jar app"

Write-Host "==> [3/6] d8 -> classes.dex"
& $d8 "--min-api" "29" "--lib" $androidJar "--classpath" (Join-Path $buildDir "libxposed-api.jar") "--output" (Join-Path $buildDir "dex") (Join-Path $buildDir "app-classes.jar")
Assert-Exit "d8"

Write-Host "==> [4/6] aapt2 资源编译 / 链接"
& $aapt2 compile "--dir" (Join-Path $root "app\src\main\res") "-o" (Join-Path $buildDir "res.zip")
Assert-Exit "aapt2 compile"
& $aapt2 link "-o" (Join-Path $buildDir "base.apk") "-I" $androidJar "--manifest" (Join-Path $root "app\src\main\AndroidManifest.xml") "--min-sdk-version" "29" "--target-sdk-version" "36" "--version-code" "$VersionCode" "--version-name" "$VersionName" (Join-Path $buildDir "res.zip")
Assert-Exit "aapt2 link"

Write-Host "==> [5/6] 组装 APK（classes.dex + META-INF/xposed 三件套）"
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open((Join-Path $buildDir "base.apk"), [System.IO.Compression.ZipArchiveMode]::Update)
function Add-ZipFile($zipArchive, [string]$file, [string]$entryName) {
    $entry = $zipArchive.CreateEntry($entryName, [System.IO.Compression.CompressionLevel]::Optimal)
    $stream = $entry.Open()
    try {
        $bytes = [System.IO.File]::ReadAllBytes($file)
        $stream.Write($bytes, 0, $bytes.Length)
    } finally {
        $stream.Dispose()
    }
}
Add-ZipFile $zip (Join-Path $buildDir "dex\classes.dex") "classes.dex"
$metaRoot = Join-Path $root "app\src\main\resources\META-INF\xposed"
Add-ZipFile $zip (Join-Path $metaRoot "java_init.list") "META-INF/xposed/java_init.list"
Add-ZipFile $zip (Join-Path $metaRoot "module.prop") "META-INF/xposed/module.prop"
Add-ZipFile $zip (Join-Path $metaRoot "scope.list") "META-INF/xposed/scope.list"
$zip.Dispose()

Write-Host "==> [6/6] zipalign + apksigner 签名"
& $zipalign "-f" "4" (Join-Path $buildDir "base.apk") (Join-Path $buildDir "aligned.apk")
Assert-Exit "zipalign"
$keystore = Join-Path $root "aodbridge.keystore"
if (-not (Test-Path $keystore)) {
    Write-Host "    生成签名用 keystore: $keystore"
    & $keytool -genkeypair -keystore $keystore -alias aodbridge -keyalg RSA -keysize 2048 -validity 10000 -storepass aodbridge123 -keypass aodbridge123 -dname "CN=AOD Bridge, OU=dev, O=aodbridge, C=CN"
    Assert-Exit "keytool"
}
& $apksigner sign --ks $keystore --ks-pass "pass:aodbridge123" --key-pass "pass:aodbridge123" --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true --out $OutFile (Join-Path $buildDir "aligned.apk")
Assert-Exit "apksigner sign"
& $apksigner verify --print-certs $OutFile
Assert-Exit "apksigner verify"
$hash = (Get-FileHash $OutFile -Algorithm SHA256).Hash.ToLower()
$sumFile = Join-Path $distDir "SHA256SUMS.txt"
("$hash  " + [System.IO.Path]::GetFileName($OutFile)) | Set-Content -Encoding ascii $sumFile
Write-Host ""
Write-Host "APK 构建完成: $OutFile"
Write-Host "$hash  $([System.IO.Path]::GetFileName($OutFile))"
