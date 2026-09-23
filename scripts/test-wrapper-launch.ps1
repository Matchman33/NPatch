param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [Parameter(Mandatory = $true)][string]$Package,
    [Parameter(Mandatory = $true)][string]$Activity,
    [ValidateRange(1, 60)][int]$Seconds = 10,
    [string]$ExpectedLog,
    [string]$OutputDirectory = "out/wrapper/launch"
)
$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Force $OutputDirectory | Out-Null
$started = [long](adb -s $Serial shell date '+%s')
if ($LASTEXITCODE -ne 0) { throw 'Cannot read device clock' }
adb -s $Serial shell am force-stop $Package
adb -s $Serial shell am start -W -n "$Package/$Activity"
if ($LASTEXITCODE -ne 0) { throw 'Cannot launch activity' }
Start-Sleep -Seconds $Seconds
$pattern = '^\s*(\d+)\.\d+\s'
$crash = @(adb -s $Serial logcat -b crash -d -v epoch | Where-Object {
    $_ -match $pattern -and [long]$Matches[1] -ge $started
})
$logs = @(adb -s $Serial logcat -d -v epoch -s WrapperLoader:V AndroidRuntime:V libc:V nativeloader:V | Where-Object {
    $_ -match $pattern -and [long]$Matches[1] -ge $started
})
$crash | Set-Content -Encoding UTF8 (Join-Path $OutputDirectory 'crash.log')
$logs | Set-Content -Encoding UTF8 (Join-Path $OutputDirectory 'launch.log')
$appPid = adb -s $Serial shell pidof $Package
$capturePid = $appPid
if (-not $capturePid) {
    foreach ($line in $crash) {
        if ($line -match ('Process: ' + [regex]::Escape($Package) + ', PID: (\d+)') -or
                $line -match ('pid: (\d+),.*>>> ' + [regex]::Escape($Package) + ' <<<')) {
            $capturePid = $Matches[1]
            break
        }
    }
}
if ($capturePid) {
    adb -s $Serial logcat -d -v epoch --pid=$($capturePid.Trim().Split(' ')[0]) | Where-Object {
        $_ -match $pattern -and [long]$Matches[1] -ge $started
    } | Set-Content -Encoding UTF8 (Join-Path $OutputDirectory 'app.log')
}
$crash | Select-String 'Process:|Caused by:|Wrapper loader failed|Cmdline:|Abort message:|signal '
if (-not $appPid -or ($crash -match [regex]::Escape($Package))) {
    Write-Output "FAIL: $Package crashed or exited"
    exit 1
}
if ($ExpectedLog -and -not (Select-String -Path (Join-Path $OutputDirectory 'app.log') -Pattern $ExpectedLog -Quiet)) {
    Write-Output "FAIL: expected application log not found: $ExpectedLog"
    exit 1
}
Write-Output "ALIVE: $Package pid=$appPid after $Seconds seconds; inspect UI for functional success"
