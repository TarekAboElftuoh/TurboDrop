# Build Script for TurboDrop Android APK
$ErrorActionPreference = "Stop"

$sdk = "C:\android-sdk"
$buildTools = "$sdk\build-tools\34.0.0"
$platform = "$sdk\platforms\android-34"
$androidJar = "$platform\android.jar"
$aapt2 = "$buildTools\aapt2.exe"
$d8 = "$buildTools\d8.bat"
$zipalign = "$buildTools\zipalign.exe"
$apksigner = "$buildTools\apksigner.bat"

$root = "c:\Users\Administrator\Downloads\phontrans\android_app"
Set-Location $root

Write-Host "==> Cleaning previous build directories..."
Remove-Item -Recurse -Force "build", "gen", "bin", "res.zip", "*.apk" -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path "gen", "bin", "build" -Force | Out-Null

Write-Host "==> 1. Compiling resources with aapt2..."
& $aapt2 compile --dir res -o res.zip
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

Write-Host "==> 2. Linking resources and generating R.java..."
& $aapt2 link res.zip -I $androidJar --manifest AndroidManifest.xml --java gen -o build\uncompiled.apk --auto-add-overlay
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Host "==> 3. Compiling Java sources with javac..."
$javaFiles = Get-ChildItem -Path "src\com\turbodrop\app\*.java", "gen\com\turbodrop\app\*.java" | ForEach-Object { $_.FullName }
& javac -encoding UTF-8 -cp "$androidJar" -d bin $javaFiles
if ($LASTEXITCODE -ne 0) { throw "javac compilation failed" }

Write-Host "==> 4. Converting .class files to classes.dex with d8..."
$classFiles = Get-ChildItem -Path "bin\com\turbodrop\app\*.class" | ForEach-Object { $_.FullName }
& $d8 --output bin --lib $androidJar $classFiles
if ($LASTEXITCODE -ne 0) { throw "d8 dexing failed" }

Write-Host "==> 5. Packaging classes.dex into APK..."
Copy-Item "build\uncompiled.apk" "build\with_dex.apk" -Force
& jar -uf build\with_dex.apk -C bin classes.dex
if ($LASTEXITCODE -ne 0) { throw "jar packaging failed" }

Write-Host "==> 6. Aligning APK with zipalign..."
& $zipalign -p -f 4 build\with_dex.apk build\aligned.apk
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

Write-Host "==> 7. Creating debug keystore (if needed) & signing..."
$keystore = "$root\debug.keystore"
if (-not (Test-Path $keystore)) {
    & keytool -genkeypair -v -keystore $keystore -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
}

$outputApk = "c:\Users\Administrator\Downloads\phontrans\TurboDrop.apk"
& $apksigner sign --ks $keystore --ks-pass pass:android --key-pass pass:android --ks-key-alias androiddebugkey --out $outputApk build\aligned.apk
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

Write-Host "============================================================"
Write-Host " SUCCESS! APK Built Successfully at:"
Write-Host " $outputApk"
Write-Host " Size: $((Get-Item $outputApk).Length / 1KB) KB"
Write-Host "============================================================"
