<# Exercise the real outer failure handler after the ordinary UI suite has passed. #>
$ErrorActionPreference = 'Stop'
$runnerPath = Join-Path $PSScriptRoot 'run_ui_tests.ps1'
$runnerTokens = $null
$runnerErrors = $null
$runnerAst = [Management.Automation.Language.Parser]::ParseFile($runnerPath, [ref]$runnerTokens, [ref]$runnerErrors)
if ($runnerErrors.Count -gt 0) { throw 'UI runner does not parse.' }
$runnerTry = $runnerAst.Find({
    param($node)
    $node -is [Management.Automation.Language.TryStatementAst] -and
        $node.Body.Extent.Text.Contains("Invoke-UiPhase -Name 'screens'")
}, $true)
if ($null -eq $runnerTry -or $runnerTry.CatchClauses.Count -ne 1) { throw 'Cannot locate UI runner failure boundary.' }
$runnerHandler = $runnerTry.CatchClauses[0].Body.Extent.Text
$runnerHandler = $runnerHandler.Substring(1, $runnerHandler.Length - 2)
$uiSucceeded = $true
$uiFailureMessage = $null
try { throw 'Injected process restart gate failure' }
catch { $null = . ([scriptblock]::Create($runnerHandler)) 6>&1 }
if ($uiSucceeded -or $uiFailureMessage -cne 'Injected process restart gate failure') {
    throw 'The UI runner accepted a failed restart gate after the ordinary suite passed.'
}
Write-Host 'UI runner failure-boundary check PASS.'

# Exercise the real viewport branch with the 320px CI screen where both controls
# start below the fold. The decision must permit scrolling before names are found.
$restartAst = [Management.Automation.Language.Parser]::ParseFile(
    (Join-Path $PSScriptRoot 'run_atomic_import_restart_tests.ps1'), [ref]$runnerTokens, [ref]$runnerErrors)
if ($runnerErrors.Count -gt 0) { throw 'Restart runner does not parse.' }
$scrollBranch = $restartAst.Find({
    param($node)
    $node -is [Management.Automation.Language.IfStatementAst] -and
        $node.Extent.Text.Contains('$atomicScroll=$atomicUi.SelectSingleNode')
}, $true)
if ($null -eq $scrollBranch) { throw 'Cannot locate restart runner scrolling boundary.' }
$atomicPackage = 'com.veycad.app.uitest'
[xml]$atomicUi = '<hierarchy><node resource-id="com.veycad.app.uitest:id/directorContent" scrollable="true" bounds="[0,24][320,592]" /></hierarchy>'
$atomicNamesPassed = $false
$atomicButtonPassed = $false
$atomicButton = $null
$atomicSwipes = 0
$scrollCommands = [Collections.Generic.List[object]]::new()
function Invoke-AtomicAdb { param([string[]]$Arguments) $scrollCommands.Add($Arguments) }
. ([scriptblock]::Create($scrollBranch.Extent.Text))
if ($atomicSwipes -ne 1 -or $scrollCommands.Count -ne 1 -or $scrollCommands[0][2] -cne 'swipe') {
    throw 'Restart runner cannot reveal source labels below a small viewport.'
}
Write-Host 'Restart runner small-viewport check PASS.'

# Run the actual merged phase/restart statements with native/device boundaries replaced.
$phaseStart = $runnerTry.Body.Statements | Where-Object {
    $_ -is [Management.Automation.Language.IfStatementAst] -and
        $_.Extent.Text.StartsWith('if ($LegacySaveOnly)') -and
        $_.Extent.Text.Contains("Invoke-UiPhase -Name 'legacy-save'")
} | Select-Object -Last 1
if ($null -eq $phaseStart) { throw 'Cannot locate legacy/ordinary UI selection.' }
$phaseCode = ($runnerTry.Body.Statements | Where-Object {
    $_.Extent.StartOffset -ge $phaseStart.Extent.StartOffset
} | ForEach-Object { $_.Extent.Text }) -join "`n"
& {
    $uiProjectRoot = Split-Path -Parent $PSScriptRoot
    $Serial = 'test'; $AvdName = 'test'; $SdkRoot = 'test'
    $uiPermissionExpected = @(); $uiOrdinaryExpected = @()
    function Invoke-UiPhase {
        param($Name, $SelectionArgument, $Expected)
        $uiPhases.Add([pscustomobject]@{ name = $Name; passed = $true })
    }
    function Invoke-UiNative {
        param($Executable, [switch]$EchoOutput, $Arguments)
        $restartCalls.Add($Arguments)
        [pscustomobject]@{ ExitCode = 0 }
    }
    function Get-Content { param($LiteralPath, [switch]$Raw) '{"passed":true,"phases":[{},{}]}' }
    foreach ($LegacySaveOnly in @($true, $false)) {
        $uiPhases = [Collections.Generic.List[object]]::new()
        $restartCalls = [Collections.Generic.List[object]]::new()
        $uiSucceeded = $false
        . ([scriptblock]::Create($phaseCode))
        $expectedPhases = if ($LegacySaveOnly) { 'legacy-save' } else { 'permissions,screens' }
        $expectedRestarts = if ($LegacySaveOnly) { 0 } else { 1 }
        if (-not $uiSucceeded -or ($uiPhases.name -join ',') -cne $expectedPhases -or
            $restartCalls.Count -ne $expectedRestarts) { throw 'Merged legacy/restart routing failed.' }
    }
}
Write-Host 'UI runner legacy/restart routing check PASS.'
