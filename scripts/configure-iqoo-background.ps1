param(
    [string]$PhoneSerial = "10AF1F0PST00419",
    [string]$PackageName = "com.example.rokidphone",
    [string]$MainActivity = ".MainActivity",
    [switch]$StartApp,
    [switch]$WakeScreen
)

$ErrorActionPreference = "Stop"

function Invoke-PhoneAdb {
    param([string[]]$AdbArgs)

    Write-Host "adb -s $PhoneSerial $($AdbArgs -join ' ')"
    & adb -s $PhoneSerial @AdbArgs
}

Write-Host "Configuring Android background allowances for $PackageName on $PhoneSerial"

Invoke-PhoneAdb @("shell", "dumpsys", "deviceidle", "whitelist", "+$PackageName")
Invoke-PhoneAdb @("shell", "appops", "set", $PackageName, "RUN_ANY_IN_BACKGROUND", "allow")
Invoke-PhoneAdb @("shell", "cmd", "activity", "set-bg-restriction-level", "--user", "0", $PackageName, "unrestricted")
Invoke-PhoneAdb @("shell", "cmd", "activity", "set-standby-bucket", "--user", "0", $PackageName, "active")
Invoke-PhoneAdb @("shell", "am", "set-inactive", $PackageName, "false")

if ($WakeScreen) {
    Invoke-PhoneAdb @("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    Invoke-PhoneAdb @("shell", "wm", "dismiss-keyguard")
}

if ($StartApp) {
    Invoke-PhoneAdb @("shell", "am", "start", "-n", "$PackageName/$MainActivity")
    Start-Sleep -Seconds 1
}

try {
    Invoke-PhoneAdb @("shell", "cmd", "activity", "unfreeze", "--sticky", $PackageName)
} catch {
    Write-Warning "Sticky unfreeze failed. This is expected if the app process is not running yet."
}

Write-Host ""
Write-Host "Current background restriction level:"
Invoke-PhoneAdb @("shell", "cmd", "activity", "get-bg-restriction-level", "--user", "0", $PackageName)

Write-Host ""
Write-Host "Current standby bucket:"
Invoke-PhoneAdb @("shell", "cmd", "activity", "get-standby-bucket", "--user", "0", $PackageName)

Write-Host ""
Write-Host "If vivo/iQOO still freezes the app, open the in-app Background Power Settings button and enable Auto-start plus unrestricted background power manually."
