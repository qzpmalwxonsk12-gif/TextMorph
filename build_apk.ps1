$ErrorActionPreference = "Stop"

$ProjectDir = $PSScriptRoot
$BuildDir   = "$ProjectDir\build_out"
$SdkDir     = "C:\Users\Arjun\AppData\Local\Android\Sdk"
$BuildTools = "$SdkDir\build-tools\35.0.0"
$AndroidJar = "$SdkDir\platforms\android-35\android.jar"
$JavaHome   = "C:\Program Files\JAVA"

$Aapt2      = "$BuildTools\aapt2.exe"
$D8         = "$BuildTools\d8.bat"
$ZipAlign   = "$BuildTools\zipalign.exe"
$ApkSigner  = "$BuildTools\apksigner.bat"
$Javac      = "$JavaHome\bin\javac.exe"
$JarExe     = "$JavaHome\bin\jar.exe"
$Keytool    = "$JavaHome\bin\keytool.exe"

Write-Host "Cleaning build output directory..."
if (Test-Path $BuildDir) { Remove-Item -Recurse -Force $BuildDir }
New-Item -ItemType Directory -Force -Path "$BuildDir\compiled_res" | Out-Null
New-Item -ItemType Directory -Force -Path "$BuildDir\gen" | Out-Null
New-Item -ItemType Directory -Force -Path "$BuildDir\stub_classes" | Out-Null
New-Item -ItemType Directory -Force -Path "$BuildDir\classes" | Out-Null
New-Item -ItemType Directory -Force -Path "$BuildDir\dex" | Out-Null

Write-Host "0. Compiling Xposed API Stub JAR (compileOnly)..."
$StubFiles = Get-ChildItem -Path "$ProjectDir\xposed_stub\src" -Recurse -Filter "*.java" | ForEach-Object { $_.FullName }
& $Javac --release 17 -encoding UTF-8 -cp $AndroidJar -d "$BuildDir\stub_classes" $StubFiles
$XposedStubJar = "$BuildDir\xposed-stub.jar"
& $JarExe -cf $XposedStubJar -C "$BuildDir\stub_classes" .

Write-Host "1. Compiling resources with aapt2..."
& $Aapt2 compile --dir "$ProjectDir\app\src\main\res" -o "$BuildDir\compiled_res\res.zip"

Write-Host "2. Linking resources, assets/xposed_init, and AndroidManifest with aapt2..."
& $Aapt2 link `
    -I $AndroidJar `
    --manifest "$ProjectDir\app\src\main\AndroidManifest.xml" `
    -A "$ProjectDir\app\src\main\assets" `
    --java "$BuildDir\gen" `
    --min-sdk-version 28 `
    --target-sdk-version 35 `
    --version-code 2 `
    --version-name "2.0.0" `
    -o "$BuildDir\unaligned.apk" `
    "$BuildDir\compiled_res\res.zip"

Write-Host "3. Compiling Java sources with javac..."
$JavaFiles = Get-ChildItem -Path "$ProjectDir\app\src\main\java", "$BuildDir\gen" -Recurse -Filter "*.java" | ForEach-Object { $_.FullName }
& $Javac --release 17 -encoding UTF-8 -cp "$AndroidJar;$XposedStubJar" -d "$BuildDir\classes" $JavaFiles

Write-Host "4. Converting bytecode to DEX with d8..."
$ClassFiles = Get-ChildItem -Path "$BuildDir\classes" -Recurse -Filter "*.class" | ForEach-Object { $_.FullName }
& $D8 --lib $AndroidJar --classpath $XposedStubJar --min-api 28 --output "$BuildDir\dex" $ClassFiles

Write-Host "5. Adding classes.dex into APK..."
& $JarExe -uf "$BuildDir\unaligned.apk" -C "$BuildDir\dex" classes.dex

Write-Host "6. Running zipalign (4-byte alignment for Android 14/15)..."
& $ZipAlign -f -p 4 "$BuildDir\unaligned.apk" "$BuildDir\aligned.apk"

$Keystore = "$BuildDir\release-key.jks"
if (-not (Test-Path $Keystore)) {
    Write-Host "7. Generating signing keystore..."
    & $Keytool -genkeypair `
        -keystore $Keystore `
        -alias textmorph `
        -keyalg RSA `
        -keysize 2048 `
        -validity 10000 `
        -storepass $env:ANDROID_KEYSTORE_PASSWORD `
        -keypass $env:ANDROID_KEYSTORE_PASSWORD `
        -dname "CN=TextMorph, OU=Mobile, O=Antigravity, L=City, S=State, C=US"
}

$FinalApk = "$ProjectDir\TextMorph-Universal.apk"
Write-Host "8. Signing APK (v1 + v2 + v3 schemes) with apksigner..."
& $ApkSigner sign `
    --ks $Keystore `
    --ks-key-alias textmorph `
    --ks-pass env:ANDROID_KEYSTORE_PASSWORD `
    --key-pass env:ANDROID_KEYSTORE_PASSWORD `
    --v1-signing-enabled true `
    --v2-signing-enabled true `
    --v3-signing-enabled true `
    --out $FinalApk `
    "$BuildDir\aligned.apk"

Write-Host "9. Verifying APK signature and assets/xposed_init..."
& $ApkSigner verify --verbose $FinalApk
& $JarExe -tf $FinalApk | Select-String "xposed_init|classes.dex"

Write-Host "SUCCESS! Built Native Hook + Universal APK at: $FinalApk"
Get-Item $FinalApk | Select-Object FullName, Length, LastWriteTime
