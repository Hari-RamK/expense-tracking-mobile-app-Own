# Session-only environment for the project-local toolchain in .toolchain/.
# Usage (PowerShell):  . .\tools\dev-env.ps1
# Nothing system-wide is changed: no PATH, registry or user-profile edits. Deleting .toolchain/ removes it all.
$root = Split-Path $PSScriptRoot -Parent
$tc = Join-Path $root '.toolchain'
$env:JAVA_HOME = (Get-ChildItem (Join-Path $tc 'jdk') -Directory | Select-Object -First 1).FullName
$env:ANDROID_HOME = Join-Path $tc 'android-sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:ANDROID_USER_HOME = Join-Path $tc 'android-home'
$env:GRADLE_USER_HOME = Join-Path $tc 'gradle-home'
$gradleBin = (Get-ChildItem (Join-Path $tc 'gradle') -Directory | Select-Object -First 1).FullName + '\bin'
$env:Path = "$env:JAVA_HOME\bin;$gradleBin;$env:ANDROID_HOME\platform-tools;$env:Path"
Write-Output "JAVA_HOME=$env:JAVA_HOME"
