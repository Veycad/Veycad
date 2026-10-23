<#
.SYNOPSIS
Runs every UI/integration test on a dedicated emulator and rejects incomplete results.
.EXAMPLE
pwsh tools/run_ui_tests.ps1 -Serial emulator-5556 -AvdName AutoEditUi_API36
.NOTES
The emulator must already be running. Only com.veycad.app.uitest is installed/reset.
Runtime permission tests run first with denied grants; all other tests run afterward.
#>
[CmdletBinding()]
param(
    [string]$Serial = 'emulator-5556',
    [string]$AvdName = 'AutoEditUi_API36',
    [string]$SdkRoot = '',
    [switch]$LegacySaveOnly
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$uiProjectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$uiPackage = 'com.veycad.app.uitest'
$uiPermissionClass = if ($LegacySaveOnly) { 'com.veycad.app.AutomaticSaveDestinationTest' } else { 'com.veycad.app.RuntimePermissionsTest' }
$uiWindows = [Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT
$uiNativeExtension = if ($uiWindows) { '.exe' } else { '' }
$uiWrapper = Join-Path $uiProjectRoot $(if ($uiWindows) { 'gradlew.bat' } else { 'gradlew' })
$uiOriginalSerial = [Environment]::GetEnvironmentVariable('ANDROID_SERIAL', 'Process')
$uiRunId = [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss-fff')
$uiSummaryFile = Join-Path $uiProjectRoot 'build/reports/ui-tests.json'
$uiPhases = [Collections.Generic.List[object]]::new()
$uiOriginalSettings = [Collections.Generic.List[object]]::new()

function Invoke-UiNative {
    param([string]$Executable, [string[]]$Arguments, [switch]$EchoOutput)
    # Capture native stderr without treating a normal adb diagnostic as a PowerShell exception.
    $ErrorActionPreference = 'Continue'
    # Invocation errors must not inherit a previous successful native exit code.
    $global:LASTEXITCODE = $null
    $uiLines = @(& $Executable @Arguments 2>&1 | ForEach-Object {
        $uiLine = $_.ToString()
        if ($EchoOutput) { Write-Host $uiLine }
        $uiLine
    })
    $uiExitCode = if ($null -eq $LASTEXITCODE) { 1 } else { $LASTEXITCODE }
    [pscustomobject]@{ ExitCode = $uiExitCode; Output = ($uiLines -join "`n") }
}

function Invoke-UiAdb {
    param([string[]]$Arguments)
    $uiResult = Invoke-UiNative -Executable $script:uiAdb -Arguments (@('-s', $Serial) + $Arguments)
    if ($uiResult.ExitCode -ne 0) { throw "adb failed for $Serial`: $($uiResult.Output)" }
    return $uiResult.Output
}

function Assert-UiApk {
    param([string]$MetadataPath, [string]$ExpectedPackage, [switch]$Instrumentation)
    $uiMetadata = Get-Content -LiteralPath $MetadataPath -Raw | ConvertFrom-Json
    if ($uiMetadata.applicationId -cne $ExpectedPackage) {
        throw "Unsafe APK package '$($uiMetadata.applicationId)': expected '$ExpectedPackage'."
    }
    if (@($uiMetadata.elements).Count -eq 0) { throw "No APKs declared in $MetadataPath" }
    foreach ($uiElement in $uiMetadata.elements) {
        $uiApk = Join-Path (Split-Path -Parent $MetadataPath) $uiElement.outputFile
        $uiBadging = Invoke-UiNative -Executable $script:uiAapt -Arguments @('dump', 'badging', $uiApk)
        if ($uiBadging.ExitCode -ne 0 -or $uiBadging.Output -notmatch "(?m)^package: name='([^']+)'") {
            throw "Cannot independently inspect APK package: $uiApk"
        }
        if ($Matches[1] -cne $ExpectedPackage) { throw "Unsafe manifest package in $uiApk" }
        if ($Instrumentation) {
            $uiTree = Invoke-UiNative -Executable $script:uiAapt -Arguments @('dump', 'xmltree', $uiApk, 'AndroidManifest.xml')
            if ($uiTree.ExitCode -ne 0 -or $uiTree.Output -notmatch 'A:\s+android:targetPackage[^=]*="([^"]+)"' -or
                $Matches[1] -cne $uiPackage) {
                throw "Instrumentation must target only $uiPackage`: $uiApk"
            }
        }
    }
}

function Read-UiExpectedTests {
    $uiExpected = [Collections.Generic.List[string]]::new()
    $uiSourceDirectory = Join-Path $uiProjectRoot 'app/src/androidTest'
    foreach ($uiSource in Get-ChildItem -LiteralPath $uiSourceDirectory -Recurse -File -Filter '*Test.kt') {
        $uiSourceText = Get-Content -LiteralPath $uiSource.FullName -Raw
        $uiPackageMatch = [regex]::Match($uiSourceText, '(?m)^\s*package\s+([A-Za-z0-9_.]+)')
        $uiClassMatch = [regex]::Match($uiSourceText, '(?m)^\s*class\s+(\w+Test)\b')
        if (-not $uiPackageMatch.Success -or -not $uiClassMatch.Success) {
            throw "Cannot determine test identity: $($uiSource.Name)"
        }
        foreach ($uiMethod in [regex]::Matches($uiSourceText, '@Test(?:\s*\([^)]*\))?\s+fun\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(')) {
            $uiExpected.Add("$($uiPackageMatch.Groups[1].Value).$($uiClassMatch.Groups[1].Value)#$($uiMethod.Groups[1].Value)")
        }
    }
    if ($uiExpected.Count -eq 0) { throw 'No instrumented tests found in source.' }
    if (@($uiExpected | Sort-Object -Unique).Count -ne $uiExpected.Count) { throw 'Duplicate source test identities.' }
    return $uiExpected.ToArray()
}

function Invoke-UiPhase {
    param([string]$Name, [string]$SelectionArgument, [string[]]$Expected)
    $uiArchive = Join-Path $uiProjectRoot "build/reports/ui-tests/runs/$uiRunId/$Name"
    New-Item -ItemType Directory -Path $uiArchive -Force | Out-Null
    $uiLatestXml = Join-Path $uiProjectRoot "build/reports/ui-tests/$Name.xml"
    if (Test-Path -LiteralPath $uiLatestXml) { Remove-Item -LiteralPath $uiLatestXml }
    # Delete exactly one report owned by the isolated app before instrumentation.
    Invoke-UiAdb @('shell', 'run-as', $uiPackage, 'rm', '-f', 'files/ui-test-results.xml') | Out-Null
    if ($Name -eq 'screens') {
        # Fresh evidence for this run; later per-test resets must retain it for collection.
        Invoke-UiAdb @('shell', 'run-as', $uiPackage, 'rm', '-rf', '--', 'files/source-audio-native-evidence') | Out-Null
    }
    $uiResult = Invoke-UiNative -Executable $uiAdb -EchoOutput -Arguments @(
        '-s', $Serial, 'shell', 'am', 'instrument', '-w', '-r',
        '-e', 'listener', 'com.veycad.app.UiXmlRunListener',
        '-e', $SelectionArgument, $uiPermissionClass,
        "$uiPackage.test/androidx.test.runner.AndroidJUnitRunner"
    )
    $uiResult.Output | Set-Content -LiteralPath (Join-Path $uiArchive 'instrumentation.txt') -Encoding UTF8
    $uiLogcat = Invoke-UiNative -Executable $uiAdb -Arguments @('-s', $Serial, 'logcat', '-d', '-v', 'threadtime')
    $uiLogcat.Output | Set-Content -LiteralPath (Join-Path $uiArchive 'logcat.txt') -Encoding UTF8
    $uiMediaFailure = $null
    if ($Name -eq 'screens') {
        # Binary-safe on Windows PowerShell 5 as well as pwsh: no native redirection or text
        # pipeline may touch the emulator-produced AAC/WAV/MP4 bytes, including failed tests.
        $uiMediaPath = Join-Path $uiArchive 'source-audio-native-evidence.tar'
        $uiMediaPartial = "$uiMediaPath.partial"
        $uiStart = [Diagnostics.ProcessStartInfo]::new()
        $uiStart.FileName = $uiAdb
        $uiStart.Arguments = "-s $Serial exec-out run-as $uiPackage tar -cf - files/source-audio-native-evidence"
        $uiStart.UseShellExecute = $false
        $uiStart.CreateNoWindow = $true
        $uiStart.RedirectStandardOutput = $true
        $uiStart.RedirectStandardError = $true
        $uiMediaError = ''
        try {
            $uiProcess = [Diagnostics.Process]::Start($uiStart)
            try {
                $uiMedia = [IO.File]::Create($uiMediaPartial)
                try {
                    $uiErrorRead = $uiProcess.StandardError.ReadToEndAsync()
                    $uiProcess.StandardOutput.BaseStream.CopyTo($uiMedia)
                    $uiProcess.WaitForExit()
                    $uiMediaError = $uiErrorRead.GetAwaiter().GetResult()
                    if ($uiProcess.ExitCode -ne 0) { throw "Source AAC evidence collection failed: $uiMediaError" }
                } finally { $uiMedia.Dispose() }
            } finally { $uiProcess.Dispose() }
            # exec-out can merge remote stderr into stdout and report zero even for missing files.
            $uiPython = if (-not [string]::IsNullOrWhiteSpace($env:PYTHON)) { $env:PYTHON }
                elseif ($uiWindows) { 'python' } else { 'python3' }
            $uiValidation = Invoke-UiNative -Executable $uiPython -Arguments @(
                (Join-Path $uiProjectRoot 'tools/verify_source_aac.py'), '--validate-archive', $uiMediaPartial)
            if ($uiValidation.ExitCode -ne 0) { throw "Invalid source AAC transfer: $($uiValidation.Output)" }
            Move-Item -LiteralPath $uiMediaPartial -Destination $uiMediaPath
            (Get-FileHash -LiteralPath $uiMediaPath -Algorithm SHA256).Hash | Set-Content -LiteralPath "$uiMediaPath.sha256" -Encoding ASCII
        } catch {
            $uiPrefixHex = ''
            if (Test-Path -LiteralPath $uiMediaPartial) {
                $uiPrefixStream = [IO.File]::OpenRead($uiMediaPartial)
                try {
                    $uiPrefix = [byte[]]::new(96)
                    $uiPrefixCount = $uiPrefixStream.Read($uiPrefix, 0, $uiPrefix.Length)
                    $uiPrefixHex = [BitConverter]::ToString($uiPrefix, 0, $uiPrefixCount)
                } finally { $uiPrefixStream.Dispose() }
            }
            $uiMediaFailure = "$($_.Exception.Message)`nstderr=$uiMediaError`ntransferPrefixHex=$uiPrefixHex"
            $uiMediaFailure | Set-Content -LiteralPath (Join-Path $uiArchive 'source-audio-collection-error.txt') -Encoding UTF8
            if (Test-Path -LiteralPath $uiMediaPartial) { Remove-Item -LiteralPath $uiMediaPartial }
            Write-Warning $uiMediaFailure
            # Continue pulling native XML: a transfer failure must not hide test failures/counts.
        }
    }
    $uiPulled = Invoke-UiNative -Executable $uiAdb -Arguments @(
        '-s', $Serial, 'exec-out', 'run-as', $uiPackage, 'cat', 'files/ui-test-results.xml'
    )
    $uiCases = [Collections.Generic.List[object]]::new()
    $uiReportCount = 0
    $uiExecuted = 0
    if ($uiPulled.ExitCode -eq 0 -and -not [string]::IsNullOrWhiteSpace($uiPulled.Output)) {
        $uiCopy = Join-Path $uiArchive 'tests.xml'
        $uiPulled.Output | Set-Content -LiteralPath $uiCopy -Encoding UTF8
        Copy-Item -LiteralPath $uiCopy -Destination $uiLatestXml -Force
        $uiReaderSettings = [Xml.XmlReaderSettings]::new()
        $uiReaderSettings.DtdProcessing = [Xml.DtdProcessing]::Prohibit
        $uiReaderSettings.XmlResolver = $null
        $uiReader = [Xml.XmlReader]::Create($uiCopy, $uiReaderSettings)
        try {
            $uiDocument = [Xml.XmlDocument]::new()
            $uiDocument.XmlResolver = $null
            $uiDocument.Load($uiReader)
        } finally { $uiReader.Dispose() }
        if ($uiDocument.DocumentElement.LocalName -cne 'testsuite') { throw "Unexpected XML report root in $Name" }
        $uiReportCount++
        $uiExecuted = [int]$uiDocument.DocumentElement.GetAttribute('executed')
        foreach ($uiCase in $uiDocument.SelectNodes('//testcase')) {
            $uiCases.Add([pscustomobject]@{
                id = "$($uiCase.GetAttribute('classname'))#$($uiCase.GetAttribute('name'))"
                failed = $null -ne $uiCase.SelectSingleNode('failure')
                error = $null -ne $uiCase.SelectSingleNode('error')
                skipped = ($null -ne $uiCase.SelectSingleNode('skipped') -or $uiCase.GetAttribute('status') -eq 'notrun')
            })
        }
    }
    $uiIds = @($uiCases | ForEach-Object { $_.id })
    $uiMissing = @($Expected | Where-Object { $_ -notin $uiIds })
    $uiUnexpected = @($uiIds | Where-Object { $_ -notin $Expected })
    $uiFailures = @($uiCases | Where-Object { $_.failed }).Count
    $uiErrors = @($uiCases | Where-Object { $_.error }).Count
    $uiSkipped = @($uiCases | Where-Object { $_.skipped }).Count
    $uiDuplicateCount = $uiIds.Count - @($uiIds | Sort-Object -Unique).Count
    $uiFinished = $uiResult.Output -match '(?m)^INSTRUMENTATION_CODE:\s*-1\s*$' -and
        $uiResult.Output -notmatch '(?m)^INSTRUMENTATION_(?:FAILED|ABORTED):|^INSTRUMENTATION_RESULT:\s*shortMsg='
    $uiTotalsMatch = $uiReportCount -gt 0 -and
        [int]$uiDocument.DocumentElement.GetAttribute('tests') -eq $uiCases.Count -and
        [int]$uiDocument.DocumentElement.GetAttribute('failures') -eq $uiFailures -and
        [int]$uiDocument.DocumentElement.GetAttribute('errors') -eq $uiErrors -and
        [int]$uiDocument.DocumentElement.GetAttribute('skipped') -eq $uiSkipped
    $uiPhase = [pscustomobject]@{
        name = $Name; expected = $Expected.Count; run = $uiCases.Count
        failures = $uiFailures; errors = $uiErrors; skipped = $uiSkipped
        duplicates = $uiDuplicateCount; missing = $uiMissing; unexpected = $uiUnexpected
        instrumentation_exit_code = $uiResult.ExitCode; executed = $uiExecuted
        instrumentation_finished = $uiFinished; report_totals_match = $uiTotalsMatch
        xml_reports = $uiReportCount; report_directory = $uiArchive
        source_audio_collection_error = $uiMediaFailure
        passed = ($null -eq $uiMediaFailure -and $uiResult.ExitCode -eq 0 -and $uiFinished -and $uiTotalsMatch -and $Expected.Count -gt 0 -and $uiReportCount -gt 0 -and
            $uiExecuted -eq $Expected.Count -and $uiCases.Count -eq $Expected.Count -and $uiFailures -eq 0 -and $uiErrors -eq 0 -and
            $uiSkipped -eq 0 -and $uiDuplicateCount -eq 0 -and $uiMissing.Count -eq 0 -and $uiUnexpected.Count -eq 0)
    }
    $uiPhases.Add($uiPhase)
    Write-Host "$Name`: $($uiPhase.run)/$($uiPhase.expected) tests, $uiFailures failures, $uiErrors errors, $uiSkipped skipped."
}

$uiSucceeded = $false
$uiFailureMessage = $null
$uiRestart = $null
Push-Location -LiteralPath $uiProjectRoot
try {
    & (Join-Path $uiProjectRoot 'tools/test_ui_runner.ps1')
    if ($Serial -notmatch '^emulator-(\d+)$' -or [int]$Matches[1] -lt 5556 -or [int]$Matches[1] % 2 -ne 0) {
        throw 'Use a dedicated emulator on an even port >=5556; the owner emulator-5554 is excluded.'
    }
    if ([string]::IsNullOrWhiteSpace($AvdName) -or $AvdName -eq 'Fear_API_36') {
        throw 'A dedicated UI-test AVD name is required; the owner AVD is excluded.'
    }
    if (-not $SdkRoot) { $SdkRoot = [Environment]::GetEnvironmentVariable('ANDROID_HOME', 'Process') }
    if (-not $SdkRoot) { $SdkRoot = [Environment]::GetEnvironmentVariable('ANDROID_SDK_ROOT', 'Process') }
    if (-not $SdkRoot) {
        $uiLocalProperties = Get-Content -LiteralPath (Join-Path $uiProjectRoot 'local.properties') -Raw
        if ($uiLocalProperties -match '(?m)^\s*sdk\.dir\s*=(.+)$') {
            $SdkRoot = $Matches[1].Trim().Replace('\\', '\').Replace('\:', ':')
        }
    }
    if (-not $SdkRoot) { throw 'Set ANDROID_HOME or pass -SdkRoot.' }
    $script:uiAdb = Join-Path $SdkRoot "platform-tools/adb$uiNativeExtension"
    if (-not (Test-Path -LiteralPath $uiAdb)) { throw "Android adb not found: $uiAdb" }
    $uiAaptFile = Get-ChildItem -LiteralPath (Join-Path $SdkRoot 'build-tools') -Recurse -File -Filter "aapt$uiNativeExtension" |
        Sort-Object FullName -Descending | Select-Object -First 1
    if ($null -eq $uiAaptFile) { throw 'Android build-tools with aapt are required to inspect APK isolation.' }
    $script:uiAapt = $uiAaptFile.FullName
    if ((Invoke-UiAdb @('get-state')).Trim() -ne 'device') { throw "$Serial is not online." }
    $uiActualAvd = @((Invoke-UiAdb @('emu', 'avd', 'name')) -split '[\r\n]+' |
        ForEach-Object { $_.Trim() } | Where-Object { $_ -and $_ -ne 'OK' })
    if ($uiActualAvd.Count -ne 1 -or $uiActualAvd[0].Trim() -cne $AvdName) {
        throw "$Serial must run dedicated AVD '$AvdName'; actual '$($uiActualAvd -join ',')'."
    }
    if ((Invoke-UiAdb @('shell', 'getprop', 'sys.boot_completed')).Trim() -ne '1') { throw 'The UI emulator has not completed boot.' }
    $uiExpectedApi = if ($LegacySaveOnly) { 28 } else { 36 }
    if ([int](Invoke-UiAdb @('shell', 'getprop', 'ro.build.version.sdk')).Trim() -ne $uiExpectedApi) {
        throw "This test selection requires API $uiExpectedApi."
    }
    # Only the verified dedicated emulator receives temporary display/animation settings.
    foreach ($uiSetting in @(
        @{ Namespace = 'global'; Name = 'stay_on_while_plugged_in'; Value = '7' },
        @{ Namespace = 'system'; Name = 'screen_off_timeout'; Value = '1800000' },
        @{ Namespace = 'global'; Name = 'window_animation_scale'; Value = '0' },
        @{ Namespace = 'global'; Name = 'transition_animation_scale'; Value = '0' },
        @{ Namespace = 'global'; Name = 'animator_duration_scale'; Value = '0' }
    )) {
        $uiPriorValue = (Invoke-UiAdb @('shell', 'settings', 'get', $uiSetting.Namespace, $uiSetting.Name)).Trim()
        $uiOriginalSettings.Add([pscustomobject]@{
            Namespace = $uiSetting.Namespace; Name = $uiSetting.Name; Value = $uiPriorValue
        })
        Invoke-UiAdb @('shell', 'settings', 'put', $uiSetting.Namespace, $uiSetting.Name, $uiSetting.Value) | Out-Null
    }
    Invoke-UiAdb @('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP') | Out-Null
    Invoke-UiAdb @('shell', 'wm', 'dismiss-keyguard') | Out-Null
    $env:ANDROID_SERIAL = $Serial
    $uiExpected = @(Read-UiExpectedTests)
    $uiPermissionExpected = @($uiExpected | Where-Object { $_.StartsWith("$uiPermissionClass#") })
    $uiOrdinaryExpected = @($uiExpected | Where-Object { -not $_.StartsWith("$uiPermissionClass#") })
    if ($uiPermissionExpected.Count -ne 2 -or $uiOrdinaryExpected.Count -eq 0) {
        throw 'Expected two permission tests and a nonempty ordinary UI suite.'
    }
    $uiBuild = Invoke-UiNative -Executable $uiWrapper -EchoOutput -Arguments @(
        '--no-daemon', '--max-workers=2', '--console=plain', '-PpreviewNativeCi=true', ':app:assembleUiTest', ':app:assembleUiTestAndroidTest'
    )
    if ($uiBuild.ExitCode -ne 0) { throw 'Could not assemble isolated UI APKs.' }
    Assert-UiApk -MetadataPath (Join-Path $uiProjectRoot 'app/build/outputs/apk/uiTest/output-metadata.json') -ExpectedPackage $uiPackage
    Assert-UiApk -MetadataPath (Join-Path $uiProjectRoot 'app/build/outputs/apk/androidTest/uiTest/output-metadata.json') -ExpectedPackage "$uiPackage.test" -Instrumentation
    Invoke-UiAdb @('install', '-r', (Join-Path $uiProjectRoot 'app/build/outputs/apk/uiTest/app-universal-uiTest.apk')) | Out-Null
    Invoke-UiAdb @('install', '-r', (Join-Path $uiProjectRoot 'app/build/outputs/apk/androidTest/uiTest/app-uiTest-androidTest.apk')) | Out-Null
    if (-not $LegacySaveOnly) {
        Invoke-UiAdb @('shell', 'pm', 'revoke', $uiPackage, 'android.permission.POST_NOTIFICATIONS') | Out-Null
        Invoke-UiAdb @('shell', 'pm', 'clear-permission-flags', $uiPackage, 'android.permission.POST_NOTIFICATIONS', 'user-set', 'user-fixed') | Out-Null
    }
    if ($LegacySaveOnly) {
        Invoke-UiPhase -Name 'legacy-save' -SelectionArgument 'class' -Expected $uiPermissionExpected
    } else {
        Invoke-UiPhase -Name 'permissions' -SelectionArgument 'class' -Expected $uiPermissionExpected
        Invoke-UiPhase -Name 'screens' -SelectionArgument 'notClass' -Expected $uiOrdinaryExpected
    }
    $uiExpectedPhases = if ($LegacySaveOnly) { 1 } else { 2 }
    $uiSucceeded = $uiPhases.Count -eq $uiExpectedPhases -and @($uiPhases | Where-Object { -not $_.passed }).Count -eq 0
    if ($uiSucceeded -and -not $LegacySaveOnly) {
        $uiRestartRun = Invoke-UiNative -Executable (Get-Process -Id $PID).Path -EchoOutput -Arguments @(
            '-NoProfile', '-File', (Join-Path $uiProjectRoot 'tools/run_atomic_import_restart_tests.ps1'),
            '-Serial', $Serial, '-AvdName', $AvdName, '-SdkRoot', $SdkRoot
        )
        if ($uiRestartRun.ExitCode -ne 0) { throw 'Atomic import process restart checks failed.' }
        $uiRestart = Get-Content -LiteralPath (Join-Path $uiProjectRoot 'build/reports/ui-tests/atomic-import-restart.json') -Raw | ConvertFrom-Json
        $uiSucceeded = $uiRestart.passed -eq $true -and @($uiRestart.phases).Count -eq 2
    }
} catch {
    $uiSucceeded = $false
    $uiFailureMessage = $_.Exception.Message
    Write-Host "UI verification failed: $uiFailureMessage"
} finally {
    # Pull while emulator-runner still owns a live device, even when instrumentation failed.
    # Only the isolated synthetic fixture screenshots are collected.
    try {
        $uiPreviewEvidence = Join-Path $uiProjectRoot 'build/reports/ui-tests/preview-evidence'
        New-Item -ItemType Directory -Path $uiPreviewEvidence -Force | Out-Null
        Invoke-UiAdb @('pull', '/sdcard/Android/data/com.veycad.app.uitest/files/preview-evidence/.', $uiPreviewEvidence) | Out-Null
    } catch { Write-Host "Preview evidence unavailable: $($_.Exception.Message)" }
    foreach ($uiOriginalSetting in $uiOriginalSettings) {
        try {
            if ($uiOriginalSetting.Value -eq 'null' -or [string]::IsNullOrWhiteSpace($uiOriginalSetting.Value)) {
                Invoke-UiAdb @('shell', 'settings', 'delete', $uiOriginalSetting.Namespace, $uiOriginalSetting.Name) | Out-Null
            } else {
                Invoke-UiAdb @('shell', 'settings', 'put', $uiOriginalSetting.Namespace, $uiOriginalSetting.Name, $uiOriginalSetting.Value) | Out-Null
            }
        } catch {
            $uiSucceeded = $false
            $uiRestoreFailure = "Could not restore emulator setting $($uiOriginalSetting.Name): $($_.Exception.Message)"
            $uiFailureMessage = if ($uiFailureMessage) { "$uiFailureMessage; $uiRestoreFailure" } else { $uiRestoreFailure }
            Write-Host $uiRestoreFailure
        }
    }
    [Environment]::SetEnvironmentVariable('ANDROID_SERIAL', $uiOriginalSerial, 'Process')
    Pop-Location
    New-Item -ItemType Directory -Path (Split-Path -Parent $uiSummaryFile) -Force | Out-Null
    $uiTotalRun = if ($uiPhases.Count -eq 0) { 0 } else {
        ($uiPhases | Measure-Object -Property run -Sum).Sum
    }
    [pscustomobject]@{
        serial = $Serial; avd = $AvdName; package = $uiPackage; run_id = $uiRunId
        run = $(if ($null -eq $uiTotalRun) { 0 } else { $uiTotalRun })
        passed = $uiSucceeded; error = $uiFailureMessage; phases = $uiPhases.ToArray()
        atomic_import_restart = $uiRestart
    } | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $uiSummaryFile -Encoding UTF8
}
if (-not $uiSucceeded) { exit 1 }
Write-Host "UI verification passed: $uiTotalRun tests on $Serial ($AvdName). Report: $uiSummaryFile"
