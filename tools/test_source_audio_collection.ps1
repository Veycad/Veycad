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
$originalPython = $env:PYTHON
function Invoke-UiAdb { }
function Invoke-UiNative {
    param([string]$Executable, [string[]]$Arguments, [switch]$EchoOutput)
    if ($Arguments -contains '--validate-archive') {
        $script:validationExecutable = $Executable
        $lines = @(& $Executable @Arguments 2>&1)
        return [pscustomobject]@{ ExitCode = $LASTEXITCODE; Output = ($lines -join "`n") }
    }
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

    $configuredPython = if (-not [string]::IsNullOrWhiteSpace($originalPython)) { $originalPython }
        elseif ([Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT) { (Get-Command python).Source }
        else { (Get-Command python3).Source }
    $env:PYTHON = $configuredPython
    $tools = New-Item -ItemType Directory -Path (Join-Path $uiProjectRoot 'tools') -Force
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'verify_source_aac.py') -Destination $tools.FullName
    $tar = Join-Path $uiProjectRoot 'native.tar'
    & $configuredPython -c 'import io,sys,tarfile; t=tarfile.open(sys.argv[1],"w"); e=tarfile.TarInfo("files/source-audio-native-evidence/selfcheck.txt"); e.size=2; t.addfile(e,io.BytesIO(b"ok")); t.close()' $tar
    if ($LASTEXITCODE -ne 0) { throw 'Set PYTHON to a working interpreter before this self-check' }
    $script:archiveBytes = [IO.File]::ReadAllBytes($tar)
    function New-CollectionSelfcheckProcess {
        $stderr = [pscustomobject]@{}
        $stderr | Add-Member ScriptMethod ReadToEndAsync { [Threading.Tasks.Task]::FromResult([string]'') }
        $process = [pscustomobject]@{
            StandardOutput = [pscustomobject]@{ BaseStream = [IO.MemoryStream]::new($script:archiveBytes) }
            StandardError = $stderr
            ExitCode = 0
        }
        $process | Add-Member ScriptMethod WaitForExit { }
        $process | Add-Member ScriptMethod Dispose { $this.StandardOutput.BaseStream.Dispose() }
        return $process
    }
    Invoke-Expression $phase.Extent.Text.Replace($start, 'New-CollectionSelfcheckProcess')
    $uiRunId = 'configured-python'
    $uiPhases.Clear()
    $script:validationExecutable = $null
    Invoke-UiPhase -Name 'screens' -SelectionArgument 'notClass' -Expected @('test.Class#works')
    if ($script:validationExecutable -cne $configuredPython -or -not $uiPhases[0].passed) {
        throw 'Configured PYTHON was not used for successful archive validation'
    }
    $published = Join-Path $uiProjectRoot 'build/reports/ui-tests/runs/configured-python/screens/source-audio-native-evidence.tar'
    if (-not (Test-Path -LiteralPath $published) -or (Test-Path -LiteralPath "$published.partial") -or
        (Get-FileHash -LiteralPath $tar).Hash -ne (Get-FileHash -LiteralPath $published).Hash) {
        throw 'Validated native bytes were not published intact'
    }
    Write-Host 'PASS: configured PYTHON actually validates and publishes native archive bytes.'
} finally {
    $env:PYTHON = $originalPython
    # This GUID directory is created only by this check.
    if (-not [IO.Path]::GetFullPath($uiProjectRoot).StartsWith([IO.Path]::GetFullPath([IO.Path]::GetTempPath()))) {
        throw 'Self-check cleanup target leaves the temporary directory'
    }
    if (Test-Path -LiteralPath $uiProjectRoot) { Remove-Item -LiteralPath $uiProjectRoot -Recurse -Force }
}
