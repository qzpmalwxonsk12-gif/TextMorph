$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Windows.Forms

$ProjectDir  = "C:\Users\Arjun\.gemini\antigravity\scratch\TextMorph"
$Java21      = "C:\Program Files\Eclipse Adoptium\jdk-21.0.1.12-hotspot\bin\java.exe"
$LspatchJar  = "$ProjectDir\lspatch_tools\lspatch.jar"
$ModuleApk   = "$ProjectDir\TextMorph-Universal.apk"
$OutDir      = "$ProjectDir\Patched_Apps"
$AdbExe      = "C:\Android\adb.exe"

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "   TextMorph Native 120Hz In-App Hook Patcher (PC Tool)   " -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "How it works:"
Write-Host "  This embeds TextMorph's native TextView.setText() hook directly"
Write-Host "  into your target app's APK (Integrated Mode)."
Write-Host "  - No LSPatch Manager or Shizuku needed on your phone!"
Write-Host "  - 100% native font, colors, and 120Hz scrolling (Zero lag!)."
Write-Host "  - Inside the patched app, tap the '🪄' button (or 3-finger tap)"
Write-Host "    to tap any text on screen and replace it permanently!"
Write-Host ""
Write-Host "Choose how to select the target app:" -ForegroundColor Yellow
Write-Host "  [1] Pick an .APK file from your PC (Opens File Picker)"
Write-Host "  [2] Pull an already-installed app from your phone via USB (ADB)"
Write-Host ""
$choice = Read-Host "Enter 1 or 2 (default is 1)"

$TargetApks = @()

if ($choice -eq "2") {
    Write-Host "`nChecking connected phone via ADB..." -ForegroundColor Cyan
    & $AdbExe devices
    $filter = Read-Host "Type part of the app name or package (e.g. instagram, twitter, whatsapp, calculator)"
    $pkgs = & $AdbExe shell pm list packages | Where-Object { $_ -match $filter } | ForEach-Object { ($_ -replace "package:","").Trim() }
    if (-not $pkgs -or $pkgs.Count -eq 0) {
        Write-Host "No matching packages found on phone (make sure USB Debugging is on)." -ForegroundColor Red
        Read-Host "Press Enter to exit"
        exit 1
    }
    $pkgList = @($pkgs)
    for ($i = 0; $i -lt $pkgList.Count; $i++) {
        Write-Host "  [$i] $($pkgList[$i])"
    }
    $idx = Read-Host "Select package number"
    $selectedPkg = $pkgList[[int]$idx]
    $PullDir = "$ProjectDir\build_out\pulled_$selectedPkg"
    if (Test-Path $PullDir) { Remove-Item -Recurse -Force $PullDir }
    New-Item -ItemType Directory -Force -Path $PullDir | Out-Null

    $paths = & $AdbExe shell pm path $selectedPkg | ForEach-Object { ($_ -replace "package:","").Trim() }
    foreach ($p in $paths) {
        if ($p) {
            & $AdbExe pull $p $PullDir
        }
    }
    $TargetApks = Get-ChildItem -Path $PullDir -Filter "*.apk" | ForEach-Object { $_.FullName }
} else {
    Write-Host "`nOpening file picker dialog... Select the .APK file(s) of the app you want to patch." -ForegroundColor Green
    $Dialog = New-Object System.Windows.Forms.OpenFileDialog
    $Dialog.Title = "Select Target App APK to Embed TextMorph Native Hook"
    $Dialog.Filter = "Android Package (*.apk)|*.apk|All Files (*.*)|*.*"
    $Dialog.Multiselect = $true
    if ($Dialog.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) {
        $TargetApks = $Dialog.FileNames
    } else {
        Write-Host "Cancelled." -ForegroundColor Yellow
        exit 0
    }
}

Write-Host "`nPatching app with TextMorph Native 120Hz Hook..." -ForegroundColor Cyan
& $Java21 -jar $LspatchJar `
    -f `
    -v `
    -l 2 `
    --target-sdk 34 `
    -m $ModuleApk `
    -o $OutDir `
    $TargetApks

Write-Host "`n==========================================================" -ForegroundColor Green
Write-Host "SUCCESS! Your patched APK is ready in:" -ForegroundColor Green
Write-Host "  $OutDir" -ForegroundColor Yellow
Write-Host "==========================================================" -ForegroundColor Green
Write-Host "Next steps on your OnePlus 12R:"
Write-Host "  1. Uninstall the original version of that app from your phone first"
Write-Host "     (Android requires this because the patched APK has a new signature)."
Write-Host "  2. Copy the *-lspatched.apk from Patched_Apps to your phone and install it."
Write-Host "  3. Open the app -> tap the '🪄' button (or tap with 3 fingers)"
Write-Host "     -> tap any text on screen to replace it natively with ZERO scroll lag!"
Write-Host ""

explorer.exe $OutDir
Read-Host "Press Enter to close"
