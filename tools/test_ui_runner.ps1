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
