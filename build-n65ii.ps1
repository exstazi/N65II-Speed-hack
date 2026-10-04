$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

Write-Host "============================================================"
Write-Host "n65II CLEAN BUILD"
Write-Host "============================================================"

# Java 17+; Android Studio's JBR is preferred when JAVA_HOME is not already valid.
$javaCandidates = @( @(
    $env:JAVA_HOME,
    "$env:ProgramFiles\Android\Android Studio\jbr",
    "$env:LOCALAPPDATA\Programs\Android Studio\jbr"
) | Where-Object { $_ -and (Test-Path (Join-Path $_ 'bin\java.exe')) } )
if ($javaCandidates.Count -gt 0) {
    $env:JAVA_HOME = @($javaCandidates)[0]
    $env:Path = "$env:JAVA_HOME\bin;$env:Path"
}
if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    throw "Java 17+ not found. Install Android Studio/JDK 17 and run again."
}

# Reuse the user's Android SDK. No unrelated SDK/tooling is stored in the source ZIP.
$sdkCandidates = @( @(
    $env:ANDROID_SDK_ROOT,
    $env:ANDROID_HOME,
    "$env:LOCALAPPDATA\Android\Sdk",
    (Join-Path $root '.android-sdk')
) | Where-Object { $_ -and (Test-Path $_) } )
if ($sdkCandidates.Count -eq 0) {
    throw "Android SDK not found. Install Android SDK Platform 35 (Android Studio SDK Manager) or set ANDROID_SDK_ROOT."
}
$env:ANDROID_SDK_ROOT = @($sdkCandidates)[0]
$env:ANDROID_HOME = $env:ANDROID_SDK_ROOT
Write-Host "Android SDK: $env:ANDROID_SDK_ROOT"
Write-Host "Android platform 35: $(Join-Path $env:ANDROID_SDK_ROOT 'platforms\android-35')"

$sdkManagerCandidates = @(
    (Join-Path $env:ANDROID_SDK_ROOT 'cmdline-tools\latest\bin\sdkmanager.bat'),
    (Join-Path $env:ANDROID_SDK_ROOT 'cmdline-tools\bin\sdkmanager.bat'),
    (Join-Path $env:ANDROID_SDK_ROOT 'tools\bin\sdkmanager.bat')
) | Where-Object { Test-Path $_ }

$sdkManager = $null
if (@($sdkManagerCandidates).Count -gt 0) {
    $sdkManager = Get-Item (@($sdkManagerCandidates)[0])
} else {
    $sdkManager = Get-ChildItem -Path $env:ANDROID_SDK_ROOT -Filter sdkmanager.bat -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
}
if (-not (Test-Path (Join-Path $env:ANDROID_SDK_ROOT 'platforms\android-35\android.jar'))) {
    if ($sdkManager) {
        Write-Host "Accepting Android SDK licenses (if needed)..."
        1..20 | ForEach-Object { 'y' } | & $sdkManager.FullName --licenses | Out-Host
        Write-Host "Installing Android 35 platform/build tools..."
        & $sdkManager.FullName 'platforms;android-35' 'build-tools;35.0.0' 'platform-tools'
        if ($LASTEXITCODE -ne 0) { throw "sdkmanager failed with exit code $LASTEXITCODE" }
    } else {
        throw "Android 35 platform is missing and sdkmanager.bat was not found under: $env:ANDROID_SDK_ROOT. In Android Studio open Settings > Android SDK > SDK Platforms, install Android 15 (API 35), and under SDK Tools install Android SDK Command-line Tools (latest)."
    }
}

$gradle = Get-Command gradle.bat -ErrorAction SilentlyContinue
if (-not $gradle) { $gradle = Get-Command gradle -ErrorAction SilentlyContinue }
if (-not $gradle) {
    $tools = Join-Path $root '.tools'
    $version = '8.9'
    $gradleHome = Join-Path $tools "gradle-$version"
    $gradleBat = Join-Path $gradleHome 'bin\gradle.bat'
    if (-not (Test-Path $gradleBat)) {
        New-Item -ItemType Directory -Force -Path $tools | Out-Null
        $zip = Join-Path $tools "gradle-$version-bin.zip"
        if (-not (Test-Path $zip)) {
            Write-Host "Downloading Gradle $version..."
            Invoke-WebRequest -UseBasicParsing -Uri "https://services.gradle.org/distributions/gradle-$version-bin.zip" -OutFile $zip
        }
        Write-Host "Extracting Gradle..."
        Expand-Archive -Force -Path $zip -DestinationPath $tools
    }
    $gradleCmd = $gradleBat
} else {
    $gradleCmd = $gradle.Source
}

Write-Host "Building debug APK..."
& $gradleCmd --no-daemon clean :app:assembleDebug
if ($LASTEXITCODE -ne 0) { throw "Gradle build failed with exit code $LASTEXITCODE" }

$apk = Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk'
if (-not (Test-Path $apk)) { throw "Build finished but APK was not found: $apk" }
Write-Host ""
Write-Host "BUILD SUCCESSFUL"
Write-Host "APK: $apk"
