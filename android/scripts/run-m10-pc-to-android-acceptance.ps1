# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)] [string] $ModelDir,
    [Parameter(Mandatory = $true)] [string] $ModelId,
    [Parameter(Mandatory = $true)] [string] $PcAddress,
    [string] $Serial
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
Add-Type -AssemblyName System.Net.Http
$androidRoot = Split-Path -Parent $PSScriptRoot
$repoRoot = Split-Path -Parent $androidRoot
$sdkLine = Get-Content -LiteralPath (Join-Path $androidRoot 'local.properties') |
    Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
if ($null -eq $sdkLine) { throw 'android/local.properties does not define sdk.dir.' }
$sdkDir = ($sdkLine -replace '^sdk\.dir=', '').Replace('\:', ':').Replace('\\', '\')
$adb = Join-Path $sdkDir 'platform-tools/adb.exe'
$fixture = (Resolve-Path -LiteralPath $ModelDir).Path
$cliDir = Join-Path $repoRoot 'desktop/cli-bin'
$brokerPath = Join-Path $cliDir 'nvpair-ui-broker.exe'
$pairingPath = 'cache/m10-pairing.json'
$modelRoot = 'files/mnn/models'
$modelRelativePath = "$modelRoot/$ModelId"
$deviceModelDir = "/data/user/0/com.nv.pair/$modelRelativePath"
$runId = [guid]::NewGuid().ToString('N')
$tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) "pair-m10-$runId"
$deviceStage = "/data/local/tmp/pair-m10-$runId"
$stdoutPath = Join-Path ([System.IO.Path]::GetTempPath()) "pair-m10-gradle-$runId.out"
$stderrPath = Join-Path ([System.IO.Path]::GetTempPath()) "pair-m10-gradle-$runId.err"
$brokerProcess = $null
$gradleProcess = $null
$rpcId = 0
$script:brokerReadTask = $null

if ($ModelId -notmatch '^[A-Za-z0-9._-]+$') { throw 'ModelId must be a single safe model-directory name.' }
if (-not (Test-Path -LiteralPath $adb -PathType Leaf)) { throw "adb was not found at $adb." }
if (-not (Test-Path -LiteralPath $brokerPath -PathType Leaf)) { throw "PC broker binary was not found at $brokerPath." }
foreach ($requiredFile in @('config.json', 'llm_config.json', 'llm.mnn', 'llm.mnn.weight')) {
    if (-not (Test-Path -LiteralPath (Join-Path $fixture $requiredFile) -PathType Leaf)) {
        throw "Required MNN fixture file is missing: $requiredFile"
    }
}
if (-not (Test-Path -LiteralPath (Join-Path $fixture 'tokenizer.mtok') -PathType Leaf) -and
    -not (Test-Path -LiteralPath (Join-Path $fixture 'tokenizer.txt') -PathType Leaf)) {
    throw 'Required MNN fixture tokenizer is missing (expected tokenizer.mtok or tokenizer.txt).'
}
$deviceList = @(& $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match '\S+\s+device$' })
if ($LASTEXITCODE -ne 0) { throw 'Unable to query Android devices with adb.' }
if ([string]::IsNullOrWhiteSpace($Serial)) {
    if ($deviceList.Count -ne 1) { throw "Expected exactly one authorized Android device; found $($deviceList.Count). Pass -Serial to select one." }
    $Serial = ($deviceList[0] -split '\s+')[0]
}
if (-not ($deviceList | Where-Object { $_ -match "^$([regex]::Escape($Serial))\s+device$" })) {
    throw "Android device '$Serial' is not connected and authorized."
}
$script:adb = $adb
$script:Serial = $Serial
$installedApk = & $adb -s $Serial shell pm path com.nv.pair
if ($LASTEXITCODE -ne 0 -or -not $installedApk) {
    Push-Location $androidRoot
    try {
        & .\gradlew.bat :app:installDebug
        if ($LASTEXITCODE -ne 0) { throw 'Unable to build/install the Android debug app for M10 acceptance.' }
    } finally { Pop-Location }
}
$existingModel = & $adb -s $Serial shell run-as com.nv.pair test -e $modelRelativePath
if ($LASTEXITCODE -eq 0) { throw "Android already has a model directory named '$ModelId'; choose a fresh explicit ModelId to keep cleanup isolated." }
$portProbe = [System.Net.Sockets.TcpClient]::new()
try {
    $pending = $portProbe.BeginConnect($PcAddress, 14324, $null, $null)
    if ($pending.AsyncWaitHandle.WaitOne(500) -and $portProbe.Connected) {
        throw "TCP $PcAddress`:14324 is already occupied; stop the owning PAIR broker before M10 acceptance."
    }
} finally { $portProbe.Dispose() }

function Invoke-BrokerRpc([string] $Method, [hashtable] $Params) {
    $script:rpcId++
    $requestId = $script:rpcId
    $request = @{ jsonrpc = '2.0'; id = $requestId; method = $Method; params = $Params } | ConvertTo-Json -Compress
    $requestBytes = [System.Text.UTF8Encoding]::new($false).GetBytes($request + "`n")
    $inputStream = $script:brokerProcess.StandardInput.BaseStream
    $inputStream.Write($requestBytes, 0, $requestBytes.Length)
    $inputStream.Flush()
    $deadline = [DateTime]::UtcNow.AddSeconds(35)
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($null -eq $script:brokerReadTask) {
            $script:brokerReadTask = $script:brokerProcess.StandardOutput.ReadLineAsync()
        }
        if (-not $script:brokerReadTask.Wait(50)) { continue }
        $line = $script:brokerReadTask.Result
        if ($null -eq $line) { throw 'PC broker closed its JSON-RPC output.' }
        $script:brokerReadTask = $script:brokerProcess.StandardOutput.ReadLineAsync()
        try { $message = $line | ConvertFrom-Json } catch { continue }
        if ($message.id -eq $requestId) {
            if ($null -ne $message.error) { throw "PC broker RPC '$Method' failed (code $($message.error.code))." }
            return $message.result
        }
    }
    throw "PC broker RPC '$Method' timed out."
}

function Wait-ForBrokerReady {
    $deadline = [DateTime]::UtcNow.AddSeconds(35)
    while ([DateTime]::UtcNow -lt $deadline) {
        if (-not $script:brokerReadTask.Wait(50)) { continue }
        $line = $script:brokerReadTask.Result
        if ($null -eq $line) { throw 'PC broker closed its JSON-RPC output before readiness.' }
        $script:brokerReadTask = $script:brokerProcess.StandardOutput.ReadLineAsync()
        try { $message = $line | ConvertFrom-Json } catch { continue }
        if ($message.method -eq 'app:ready') { return }
    }
    throw 'PC broker did not report app:ready before the startup deadline.'
}

function Invoke-Adb([string[]] $Arguments, [string] $FailureMessage) {
    & $script:adb -s $script:Serial @Arguments
    if ($LASTEXITCODE -ne 0) { throw $FailureMessage }
}

function Get-MnnAndroidHost([string] $ExpectedModelId) {
    $deadline = [DateTime]::UtcNow.AddSeconds(75)
    while ([DateTime]::UtcNow -lt $deadline) {
        $result = Invoke-BrokerRpc 'discovery:get-nodes' @{}
        $nodes = @($result.nodes)
        $node = $nodes | Where-Object {
            $_.trusted -eq $true -and $_.hostUuid -and
            @($_.modelsByEngine.mnn) -contains $ExpectedModelId
        } | Select-Object -First 1
        if ($null -ne $node) { return $node }
        Start-Sleep -Milliseconds 500
    }
    throw "The paired Android node did not advertise MNN model '$ExpectedModelId'."
}

function Get-Workload([string] $ExpectedModelId, [long] $StartedAt, [string] $ExpectedState, [string] $AndroidHostUuid) {
    $deadline = [DateTime]::UtcNow.AddSeconds(150)
    while ([DateTime]::UtcNow -lt $deadline) {
        $result = Invoke-BrokerRpc 'workloads:get-initial' @{}
        $workload = @($result.workloads) | Where-Object {
            $_.engine -eq 'mnn' -and $_.model -eq $ExpectedModelId -and
            [long]$_.createdAt -ge $StartedAt
        } | Sort-Object { [long]$_.createdAt } -Descending | Select-Object -First 1
        if ($null -ne $workload -and $workload.state -eq $ExpectedState) {
            if ($workload.scheduledOn -ne $AndroidHostUuid) {
                throw "MNN workload scheduledOn '$($workload.scheduledOn)' does not match Android host '$AndroidHostUuid'."
            }
            return $workload
        }
        Start-Sleep -Milliseconds 350
    }
    throw "No MNN workload reached state '$ExpectedState' for model '$ExpectedModelId'."
}

function New-MnnRequest([string] $ExpectedModelId, [int] $MaxTokens) {
    $body = @{
        model = $ExpectedModelId
        messages = @(@{ role = 'user'; content = 'Reply with one short sentence confirming the MNN route works.' })
        stream = $true
        max_tokens = $MaxTokens
        temperature = 0
    } | ConvertTo-Json -Depth 8 -Compress
    $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post, 'http://127.0.0.1:14324/v1/chat/completions')
    $request.Headers.Add('Accept', 'text/event-stream')
    $request.Content = [System.Net.Http.StringContent]::new($body, [System.Text.Encoding]::UTF8, 'application/json')
    return $request
}

function Read-MnnStream([System.Net.Http.HttpClient] $Client, [string] $ExpectedModelId, [int] $MaxTokens, [bool] $CancelAfterFirstToken) {
    $request = New-MnnRequest $ExpectedModelId $MaxTokens
    $response = $Client.SendAsync($request, [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead).GetAwaiter().GetResult()
    if (-not $response.IsSuccessStatusCode) {
        try {
            $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        } finally {
            $response.Dispose()
            $request.Dispose()
        }
        throw "PC MNN facade returned HTTP $([int]$response.StatusCode): $body"
    }
    if ($response.Content.Headers.ContentType.MediaType -ne 'text/event-stream') {
        $response.Dispose()
        $request.Dispose()
        throw 'MNN chat response was not SSE.'
    }
    $reader = [System.IO.StreamReader]::new($response.Content.ReadAsStreamAsync().GetAwaiter().GetResult())
    $firstTokenAt = $null
    $sawDone = $false
    try {
        while (($line = $reader.ReadLine()) -ne $null) {
            if (-not $line.StartsWith('data: ')) { continue }
            $data = $line.Substring(6)
            if ($data -eq '[DONE]') { $sawDone = $true; break }
            try { $chunk = $data | ConvertFrom-Json } catch { continue }
            $token = $chunk.choices[0].delta.content
            if (-not [string]::IsNullOrEmpty([string]$token)) {
                if ($null -eq $firstTokenAt) { $firstTokenAt = [DateTime]::UtcNow }
                if ($CancelAfterFirstToken) { break }
            }
        }
    } finally {
        $reader.Dispose()
        $response.Dispose()
        $request.Dispose()
    }
    if ($null -eq $firstTokenAt) { throw 'MNN SSE stream did not yield a content token.' }
    if (-not $CancelAfterFirstToken -and -not $sawDone) { throw 'MNN SSE stream ended without the [DONE] marker.' }
    return $firstTokenAt
}

function Read-MnnStreamAfterCancellation([System.Net.Http.HttpClient] $Client, [string] $ExpectedModelId) {
    for ($attempt = 1; $attempt -le 30; $attempt++) {
        try {
            return Read-MnnStream $Client $ExpectedModelId 96 $false
        } catch {
            if ($_.Exception.Message -notmatch 'HTTP 409:.*engine_busy') { throw }
            Start-Sleep -Milliseconds 250
        }
    }
    throw 'Android MNN remained busy for more than 7.5 seconds after client disconnect cancellation.'
}

function Invoke-M10HostAcceptance([string] $ExpectedModelId, [string] $AndroidHostUuid) {
    $handler = [System.Net.Http.HttpClientHandler]::new()
    $handler.UseProxy = $false
    $client = [System.Net.Http.HttpClient]::new($handler)
    $client.Timeout = [TimeSpan]::FromMinutes(4)
    try {
        $modelsResponse = $client.GetAsync('http://127.0.0.1:14324/v1/models').GetAwaiter().GetResult()
        if (-not $modelsResponse.IsSuccessStatusCode) { throw "PC MNN facade /v1/models returned HTTP $([int]$modelsResponse.StatusCode)." }
        $models = $modelsResponse.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json
        if (-not (@($models.data | ForEach-Object { $_.id }) -contains $ExpectedModelId)) {
            throw "PC MNN facade did not return the explicitly requested Android model '$ExpectedModelId'."
        }
        $modelsResponse.Dispose()
        Write-Host "PC loopback model inventory contains '$ExpectedModelId'."

        $requestStartedAt = [DateTime]::UtcNow
        $startedAt = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
        $tokenAt = Read-MnnStream $client $ExpectedModelId 96 $false
        if ($tokenAt -le $requestStartedAt) { throw 'The streamed token did not arrive after the request began.' }
        $completed = Get-Workload $ExpectedModelId $startedAt 'completed' $AndroidHostUuid
        Write-Host "Completed streaming workload $($completed.id) on Android host $AndroidHostUuid."

        $startedAt = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
        $null = Read-MnnStream $client $ExpectedModelId 2048 $true
        $cancelled = Get-Workload $ExpectedModelId $startedAt 'cancelled' $AndroidHostUuid
        Write-Host "Client disconnect cancelled workload $($cancelled.id) on Android host $AndroidHostUuid."

        $startedAt = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
        $null = Read-MnnStreamAfterCancellation $client $ExpectedModelId
        $next = Get-Workload $ExpectedModelId $startedAt 'completed' $AndroidHostUuid
        Write-Host "Post-cancellation streaming workload $($next.id) completed on Android host $AndroidHostUuid."
    } finally {
        $client.Dispose()
        $handler.Dispose()
    }
}

try {
    New-Item -ItemType Directory -Path $tempRoot | Out-Null
    Write-Host 'Starting isolated PC broker.'
    $brokerInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $brokerInfo.FileName = $brokerPath
    $brokerInfo.WorkingDirectory = $cliDir
    $brokerInfo.Arguments = "--proxy-engines=mnn --cluster-dir `"$(Join-Path $tempRoot 'cluster')`""
    $brokerInfo.EnvironmentVariables['LOCALAPPDATA'] = Join-Path $tempRoot 'localappdata'
    $brokerInfo.UseShellExecute = $false
    $brokerInfo.CreateNoWindow = $true
    $brokerInfo.RedirectStandardInput = $true
    $brokerInfo.RedirectStandardOutput = $true
    $brokerInfo.RedirectStandardError = $false
    $brokerProcess = [System.Diagnostics.Process]::new()
    $brokerProcess.StartInfo = $brokerInfo
    if (-not $brokerProcess.Start()) { throw 'Unable to start the isolated PC broker.' }
    $script:brokerReadTask = $brokerProcess.StandardOutput.ReadLineAsync()
    Write-Host 'Waiting for isolated PC broker RPC.'
    Wait-ForBrokerReady
    # Prime the redirected writer so its UTF-8 preamble cannot prefix a JSON-RPC request.
    $brokerProcess.StandardInput.WriteLine('')
    $null = Invoke-BrokerRpc 'ping' @{}
    Write-Host 'PC broker RPC is ready.'

    $serialArgs = @('-s', $Serial)
    Invoke-Adb @('shell', 'mkdir', '-p', "$deviceStage/model") 'Unable to create the temporary device model directory.'
    Invoke-Adb @('push', $fixture, "$deviceStage/model") 'Unable to transfer the MNN fixture to Android.'
    $remoteFixture = "$deviceStage/model"
    & $adb @serialArgs shell test -f "$remoteFixture/config.json" | Out-Null
    if ($LASTEXITCODE -ne 0) {
        $remoteFixture = "$deviceStage/model/$(Split-Path -Leaf $fixture)"
        & $adb @serialArgs shell test -f "$remoteFixture/config.json" | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Transferred MNN fixture did not contain config.json at the expected path.' }
    }
    Invoke-Adb @('shell', 'chmod', '-R', 'a+rX', $remoteFixture) 'Unable to make the staged MNN fixture readable to the app process.'

    $gradleArgs = @(
        ':app:connectedDebugAndroidTest',
        "-PpairMnnDeviceModelDir=$deviceModelDir",
        "-PpairMnnDeviceModelId=$ModelId",
        '-PpairM10Acceptance=true',
        "-PpairM10PcAddress=$PcAddress"
    )
    $javaCommand = Get-Command java.exe -CommandType Application -ErrorAction Stop
    $wrapperJar = Join-Path $androidRoot 'gradle/wrapper/gradle-wrapper.jar'
    $javaArgs = @(
        '"-Xmx64m"',
        '"-Xms64m"',
        '"-Dorg.gradle.appname=gradlew"',
        '-classpath',
        ('"' + $wrapperJar + '"'),
        'org.gradle.wrapper.GradleWrapperMain'
    ) + $gradleArgs
    $gradleProcess = Start-Process -FilePath $javaCommand.Source `
        -ArgumentList ($javaArgs -join ' ') -WorkingDirectory $androidRoot -WindowStyle Hidden `
        -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath -PassThru

    $pairingAccepted = $false
    $modelStaged = $false
    $hostAcceptanceComplete = $false
    while (-not $gradleProcess.HasExited) {
        if (-not $modelStaged) {
            $targetPackage = & $adb @serialArgs shell pm path com.nv.pair
            $instrumentationPackage = & $adb @serialArgs shell pm path com.nv.pair.test
            if ($LASTEXITCODE -eq 0 -and $targetPackage -and $instrumentationPackage) {
                Invoke-Adb @('shell', 'run-as', 'com.nv.pair', 'mkdir', '-p', $modelRelativePath) 'Unable to create the app-private MNN model directory.'
                Invoke-Adb @('shell', 'run-as', 'com.nv.pair', 'cp', '-R', "$remoteFixture/.", $modelRelativePath) 'Unable to copy the MNN fixture into app-private storage.'
                $modelStaged = $true
                Write-Host "Installed MNN model '$ModelId' after Android instrumentation package installation."
            }
        }
        if ($modelStaged -and -not $pairingAccepted) {
            & $adb @serialArgs shell run-as com.nv.pair test -f $pairingPath
            if ($LASTEXITCODE -eq 0) {
                $pairingText = & $adb @serialArgs shell run-as com.nv.pair cat $pairingPath
                if ($LASTEXITCODE -eq 0 -and $pairingText) {
                    $invite = ($pairingText -join "`n") | ConvertFrom-Json
                    $null = Invoke-BrokerRpc 'cluster:respond-to-invite' @{
                        inviteId = [string]$invite.inviteId
                        accept = $true
                        pin = [string]$invite.pin
                    }
                    $pairingAccepted = $true
                    $pinDirectory = Join-Path $tempRoot 'cluster/trusted'
                    $pinCount = @(Get-ChildItem -LiteralPath $pinDirectory -File -ErrorAction SilentlyContinue).Count
                    Write-Host "PC accepted the Android M10 pairing invitation; pinned peer count=$pinCount."
                }
            }
        }
        if ($pairingAccepted -and -not $hostAcceptanceComplete) {
            $androidNode = Get-MnnAndroidHost $ModelId
            Write-Host "Android advertises requested MNN model on host $($androidNode.hostUuid)."
            Invoke-M10HostAcceptance $ModelId ([string]$androidNode.hostUuid)
            Invoke-Adb @('shell', 'run-as', 'com.nv.pair', 'touch', 'cache/m10-host-complete') 'Unable to signal successful PC-side M10 acceptance to Android.'
            $hostAcceptanceComplete = $true
        }
        Start-Sleep -Milliseconds 400
        $gradleProcess.Refresh()
    }
    $gradleProcess.WaitForExit()
    $gradleProcess.Refresh()
    $gradleOutput = Get-Content -LiteralPath $stdoutPath -Raw
    Write-Output $gradleOutput
    if (Test-Path -LiteralPath $stderrPath) { Get-Content -LiteralPath $stderrPath | Write-Output }
    if ($gradleOutput -notmatch '(?m)^BUILD SUCCESSFUL(?: in .*)?$') { throw "M10 Android acceptance failed (Gradle exit code $($gradleProcess.ExitCode))." }
    Write-Host 'M10 PC-to-Android MNN acceptance passed.'
} catch {
    Write-Host ('M10 acceptance stopped: ' + $_.Exception.Message)
    throw
} finally {
    if ($null -ne $gradleProcess -and -not $gradleProcess.HasExited) {
        if ($adb -and $Serial) {
            & $adb -s $Serial shell run-as com.nv.pair touch cache/m10-host-complete | Out-Null
        }
        $gradleProcess.Refresh()
        if (-not $gradleProcess.HasExited -and -not $gradleProcess.WaitForExit(30000)) {
            Stop-Process -Id $gradleProcess.Id -Force -ErrorAction SilentlyContinue
        }
    }
    if ($null -ne $brokerProcess) {
        if (-not $brokerProcess.HasExited) {
            $brokerProcess.StandardInput.Close()
            if (-not $brokerProcess.WaitForExit(30000)) {
                & taskkill.exe /PID $brokerProcess.Id /T /F | Out-Null
                $brokerProcess.WaitForExit()
            }
        }
        $brokerProcess.Dispose()
    }
    if ($adb -and $Serial) { & $adb -s $Serial shell rm -rf $deviceStage | Out-Null }
    & $adb -s $Serial shell pm path com.nv.pair | Out-Null
    if ($LASTEXITCODE -eq 0) { & $adb -s $Serial shell run-as com.nv.pair rm -rf $modelRelativePath | Out-Null }
    $resolvedTempRoot = [System.IO.Path]::GetFullPath($tempRoot)
    $tempDirectoryPrefix = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
    if ($resolvedTempRoot.StartsWith($tempDirectoryPrefix, [StringComparison]::OrdinalIgnoreCase) -and
        (Split-Path -Leaf $resolvedTempRoot) -like 'pair-m10-*') {
        Remove-Item -LiteralPath $resolvedTempRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
    Remove-Item -LiteralPath $stdoutPath, $stderrPath -Force -ErrorAction SilentlyContinue
}
