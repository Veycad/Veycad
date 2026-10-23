$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$tokens = $null
$errors = $null
$runner = [Management.Automation.Language.Parser]::ParseFile(
    (Join-Path $PSScriptRoot 'run_ui_tests.ps1'), [ref]$tokens, [ref]$errors)
if ($errors.Count) { throw "UI runner does not parse: $errors" }
$phase = $runner.Find({ param($node)
    $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Invoke-UiPhase'
}, $true)
if (-not $phase) { throw 'Missing UI phase function' }
# Exercise the real error path without launching adb or touching an emulator.
$start = '[Diagnostics.Process]::Start($uiStart)'
if (-not $phase.Extent.Text.Contains($start)) { throw 'Missing native collection process' }
Invoke-Expression $phase.Extent.Text.Replace($start, "& { throw 'collection-selfcheck-failure' }")

$uiProjectRoot = Join-Path ([IO.Path]::GetTempPath()) ('veycad-collection-' + [guid]::NewGuid())
$uiRunId = 'selfcheck'
$uiPackage = 'com.veycad.app.uitest'
$uiPermissionClass = 'com.veycad.app.RuntimePermissionsTest'
$uiAdb = 'unused-adb'
$uiWindows = $true
$Serial = 'emulator-5556'
$uiPhases = [Collections.Generic.List[object]]::new()
$script:xmlPulled = $false
function Invoke-UiAdb { }
function Invoke-UiNative {
    param([string]$Executable, [string[]]$Arguments, [switch]$EchoOutput)
    $output = if ($Arguments -contains 'instrument') { 'INSTRUMENTATION_CODE: -1' }
    elseif ($Arguments -contains 'cat') {
        $script:xmlPulled = $true
        '<testsuite tests="1" executed="1" failures="0" errors="0" skipped="0"><testcase classname="test.Class" name="works"/></testsuite>'
    } else { 'logcat' }
    [pscustomobject]@{ ExitCode = 0; Output = $output }
}
try {
    Invoke-UiPhase -Name 'screens' -SelectionArgument 'notClass' -Expected @('test.Class#works')
    if (-not $script:xmlPulled -or $uiPhases.Count -ne 1) { throw 'Collection failure hid native XML' }
    $result = $uiPhases[0]
    if ($result.passed -or $result.source_audio_collection_error -notmatch 'collection-selfcheck-failure') {
        throw 'Collection failure was not recorded as a failed phase'
    }
    if ($result.run -ne 1 -or -not $result.report_totals_match -or $result.xml_reports -ne 1) {
        throw 'Native test totals were lost after collection failure'
    }
    $archive = Join-Path $uiProjectRoot 'build/reports/ui-tests/runs/selfcheck/screens'
    if (-not (Test-Path (Join-Path $archive 'tests.xml')) -or
        -not (Test-Path (Join-Path $archive 'source-audio-collection-error.txt')) -or
        (Test-Path (Join-Path $archive 'source-audio-native-evidence.tar'))) {
        throw 'Failed transfer was published or diagnostics were lost'
    }
    Write-Host 'PASS: failed evidence collection retains XML/counts and fails the UI phase.'
} finally {
    # This GUID directory is created only by this check.
    if (-not [IO.Path]::GetFullPath($uiProjectRoot).StartsWith([IO.Path]::GetFullPath([IO.Path]::GetTempPath()))) {
        throw 'Self-check cleanup target leaves the temporary directory'
    }
    if (Test-Path -LiteralPath $uiProjectRoot) { Remove-Item -LiteralPath $uiProjectRoot -Recurse -Force }
}
