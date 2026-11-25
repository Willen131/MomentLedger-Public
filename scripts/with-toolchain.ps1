$ErrorActionPreference = "Stop"

$toolchainRoot = Join-Path $env:LOCALAPPDATA "MomentLedgerToolchain"
$jdkHome = Join-Path $toolchainRoot "jdk-17"
$gradleHome = Join-Path $toolchainRoot "gradle-8.11.1"
$sdkRoot = Join-Path $toolchainRoot "android-sdk"

if (-not (Test-Path (Join-Path $jdkHome "bin\java.exe"))) {
    Write-Error "Portable JDK 17 not found at $jdkHome"
    exit 1
}
if (-not (Test-Path $sdkRoot)) {
    Write-Error "Portable Android SDK not found at $sdkRoot"
    exit 1
}

$env:JAVA_HOME = $jdkHome
$env:ANDROID_HOME = $sdkRoot
$env:ANDROID_SDK_ROOT = $sdkRoot

$binPaths = @(
    (Join-Path $jdkHome "bin"),
    (Join-Path $gradleHome "bin"),
    (Join-Path $sdkRoot "cmdline-tools\latest\bin"),
    (Join-Path $sdkRoot "platform-tools"),
    (Join-Path $sdkRoot "build-tools\35.0.0")
) | Where-Object { Test-Path $_ }

$env:PATH = ($binPaths -join ";") + ";" + $env:PATH

if ($args.Length -eq 0) {
    Write-Error "Usage: scripts/with-toolchain.ps1 <command> [args...]"
    exit 1
}

if ($args.Length -eq 1) {
    & $args[0]
} else {
    & $args[0] @($args[1..($args.Length - 1)])
}
exit $LASTEXITCODE
