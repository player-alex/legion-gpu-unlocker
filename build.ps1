# Builds Legion GPU Unlocker (modern libXposed API 101 module) into a signed APK
# using only the Android SDK build-tools -- no Gradle.
$ErrorActionPreference = "Stop"

function Assert-Exit([string]$what) {
    if ($LASTEXITCODE -ne 0) { throw "$what failed (exit $LASTEXITCODE)" }
}

# ---- Toolchain ----------------------------------------------------------
$Root     = $PSScriptRoot
$Sdk      = "C:\Users\User\AppData\Local\Android\Sdk"
$Bt       = Join-Path $Sdk "build-tools\36.0.0"
$AndJar   = Join-Path $Sdk "platforms\android-36\android.jar"
$Xposed   = Join-Path $Root "libs\libxposed-api.jar"   # compile-only; provided by LSPosed at runtime
$JavaHome = if (Test-Path "$env:JAVA_HOME\bin\javac.exe") { $env:JAVA_HOME } else { "C:\Program Files\Android\Android Studio\jbr" }

$Javac    = Join-Path $JavaHome "bin\javac.exe"
$Jar      = Join-Path $JavaHome "bin\jar.exe"
$Keytool  = Join-Path $JavaHome "bin\keytool.exe"
$D8       = Join-Path $Bt "d8.bat"
$Aapt2    = Join-Path $Bt "aapt2.exe"
$Zipalign = Join-Path $Bt "zipalign.exe"
$Apksign  = Join-Path $Bt "apksigner.bat"

foreach ($t in @($Javac,$Jar,$Keytool,$D8,$Aapt2,$Zipalign,$Apksign,$AndJar,$Xposed)) {
    if (-not (Test-Path $t)) { throw "Missing tool/dep: $t" }
}

# ---- Clean --------------------------------------------------------------
$Build = Join-Path $Root "build"
if (Test-Path $Build) { Remove-Item $Build -Recurse -Force }
New-Item -ItemType Directory -Path "$Build\classes","$Build\dex","$Build\stage" | Out-Null

Write-Host "== 1/6 javac (against android.jar + libxposed-api) ==" -ForegroundColor Cyan
$srcs = @((Get-ChildItem -Recurse "$Root\src" -Filter *.java).FullName)
$Cp = "$AndJar;$Xposed"
& $Javac -source 17 -target 17 -nowarn -classpath $Cp -d "$Build\classes" @srcs
Assert-Exit "javac"

Write-Host "== 2/6 d8 (module classes only; libxposed stays on classpath) ==" -ForegroundColor Cyan
$moduleClasses = @((Get-ChildItem -Recurse "$Build\classes\io" -Filter *.class).FullName)
& $D8 --release --min-api 30 --lib "$AndJar" --classpath "$Xposed" --output "$Build\dex" @moduleClasses
Assert-Exit "d8"

Write-Host "== 3/6 aapt2 link (manifest only, no resources) ==" -ForegroundColor Cyan
& $Aapt2 link -I "$AndJar" --manifest "$Root\AndroidManifest.xml" `
    --min-sdk-version 30 --target-sdk-version 36 -o "$Build\unsigned.apk"
Assert-Exit "aapt2 link"

Write-Host "== 4/6 add classes.dex + META-INF/xposed ==" -ForegroundColor Cyan
Copy-Item "$Build\dex\classes.dex" "$Build\stage\classes.dex"
New-Item -ItemType Directory -Path "$Build\stage\META-INF\xposed" | Out-Null
Copy-Item "$Root\META-INF\xposed\module.prop"    "$Build\stage\META-INF\xposed\module.prop"
Copy-Item "$Root\META-INF\xposed\java_init.list" "$Build\stage\META-INF\xposed\java_init.list"
Copy-Item "$Root\META-INF\xposed\scope.list"     "$Build\stage\META-INF\xposed\scope.list"
& $Jar uf "$Build\unsigned.apk" -C "$Build\stage" .
Assert-Exit "jar (add dex + META-INF/xposed)"

Write-Host "== 5/6 zipalign ==" -ForegroundColor Cyan
& $Zipalign -f 4 "$Build\unsigned.apk" "$Build\aligned.apk"
Assert-Exit "zipalign"

Write-Host "== 6/6 sign ==" -ForegroundColor Cyan
$Ks = Join-Path $Root "release.keystore"
if (-not (Test-Path $Ks)) {
    & $Keytool -genkeypair -v -keystore $Ks -storepass legion -keypass legion `
        -alias legion -keyalg RSA -keysize 2048 -validity 10000 `
        -dname "CN=Legion GPU Unlocker, O=laelaps, C=US"
    Assert-Exit "keytool"
}
$OutApk = Join-Path $Root "LegionGpuUnlocker.apk"
& $Apksign sign --ks $Ks --ks-pass pass:legion --key-pass pass:legion `
    --ks-key-alias legion --out $OutApk "$Build\aligned.apk"
Assert-Exit "apksigner"

Write-Host ""
Write-Host "BUILD OK -> $OutApk" -ForegroundColor Green
& $Apksign verify --print-certs $OutApk | Select-Object -First 1
