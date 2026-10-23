# TTSNover Android Windows PowerShell 构建脚本
# 流程：aapt2 -> javac -> d8 -> zipalign -> apksigner
# 纯 SDK 工具链离线构建，无需 Gradle / AndroidX。

$ErrorActionPreference = "Stop"
$APP = $PSScriptRoot
Set-Location $APP

$SDK = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "D:\Android\Sdk" }
$BT = "$SDK\build-tools\34.0.0"
$PLATFORM = "$SDK\platforms\android-34\android.jar"
$JDK_DIR = if (Test-Path "D:\software\jdk-11.0.2") { "D:\software\jdk-11.0.2" } else { "" }
if ($JDK_DIR) {
    $env:JAVA_HOME = $JDK_DIR
    $env:PATH = "$JDK_DIR\bin;" + $env:PATH
}
$JAVAC = if ($JDK_DIR) { "$JDK_DIR\bin\javac.exe" } else { "javac" }
$JAR = if ($JDK_DIR) { "$JDK_DIR\bin\jar.exe" } else { "jar" }
$KEYTOOL = if ($JDK_DIR) { "$JDK_DIR\bin\keytool.exe" } else { "keytool" }
$KS = "$APP\keystore\ttsnover.jks"
$KS_PASS = "ttsnover2026"
$VERSION_NAME = "1.1.0"
$OUT = "$APP\dist\TTSNover-$VERSION_NAME.apk"

function Assert-LastExit([string]$stepName) {
    if ($LASTEXITCODE -ne 0) {
        throw "[$stepName] 执行失败，退出码: $LASTEXITCODE"
    }
}

Write-Host "== 1/6 清理目录 ==" -ForegroundColor Cyan
if (Test-Path "$APP\build") { Remove-Item -Recurse -Force "$APP\build" }
New-Item -ItemType Directory -Path "$APP\build\gen", "$APP\build\classes", "$APP\build\dex", "$APP\dist" -Force | Out-Null

Write-Host "== 2/6 aapt2 compile ==" -ForegroundColor Cyan
& "$BT\aapt2.exe" compile --dir res -o build\res.zip
Assert-LastExit "aapt2 compile"

Write-Host "== 3/6 aapt2 link ==" -ForegroundColor Cyan
& "$BT\aapt2.exe" link -o build\base.apk -I "$PLATFORM" `
  --manifest AndroidManifest.xml -R build\res.zip `
  --java build\gen --auto-add-overlay `
  --min-sdk-version 21 --target-sdk-version 34 `
  --version-code 2 --version-name $VERSION_NAME
Assert-LastExit "aapt2 link"

Write-Host "== 4/6 javac 编译 ==" -ForegroundColor Cyan
$srcs = (Get-ChildItem -Path "$APP\java", "$APP\build\gen" -Filter "*.java" -Recurse).FullName
$jars = (Get-ChildItem -Path "$APP\libs\*.jar").FullName -join ";"
$cp = "$PLATFORM;$jars"
& $JAVAC -source 8 -target 8 -encoding UTF-8 -cp $cp -d "$APP\build\classes" $srcs
Assert-LastExit "javac"

Write-Host "== 5/6 d8 转 Dex ==" -ForegroundColor Cyan
$classes = (Get-ChildItem -Path "$APP\build\classes" -Filter "*.class" -Recurse).FullName
$classes | Out-File -Encoding ASCII "$APP\build\classes.txt"
$jarList = (Get-ChildItem -Path "$APP\libs\*.jar").FullName
& "$BT\d8.bat" --lib "$PLATFORM" --min-api 21 --release --output "$APP\build\dex" "@$APP\build\classes.txt" $jarList
Assert-LastExit "d8"

Write-Host "== 6/6 打包、对齐与签名 ==" -ForegroundColor Cyan
Copy-Item "$APP\build\base.apk" "$APP\build\unsigned.apk" -Force

# 将 classes.dex 添加进 unsigned.apk (使用 JDK 自带的 jar 命令)
& $JAR -uf "$APP\build\unsigned.apk" -C "$APP\build\dex" classes.dex
Assert-LastExit "jar add dex"

# zipalign 对齐
& "$BT\zipalign.exe" -f 4 "$APP\build\unsigned.apk" "$APP\build\aligned.apk"
Assert-LastExit "zipalign"

# 检查/生成签名密钥
if (-not (Test-Path $KS)) {
    New-Item -ItemType Directory -Path "$APP\keystore" -Force | Out-Null
    & $KEYTOOL -genkeypair -v -keystore $KS -alias ttsnover -keyalg RSA -keysize 2048 -validity 10000 -storepass $KS_PASS -keypass $KS_PASS -dname "CN=TTSNover, OU=App, O=TTSNover, L=City, ST=State, C=CN"
    Assert-LastExit "keytool"
}

# apksigner 签名
& "$BT\apksigner.bat" sign --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" `
  --v1-signing-enabled true --v2-signing-enabled true `
  --out "$OUT" "$APP\build\aligned.apk"
Assert-LastExit "apksigner"

Write-Host ""
Write-Host "================ 构建成功 ================" -ForegroundColor Green
Write-Host "输出 APK: $OUT" -ForegroundColor Green
$apkItem = Get-Item $OUT
Write-Host "文件大小: $([math]::Round($apkItem.Length / 1MB, 2)) MB ($($apkItem.Length) 字节)" -ForegroundColor Green
