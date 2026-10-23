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
    function Collect-UiPreviewEvidence { param([switch]$Required) }
    $projectReport = '{"passed":true,"exactFileChecks":5,"checkpointPid":10,"restartedPid":20}'
    function Get-Content {
        param($LiteralPath, [switch]$Raw)
        if ($LiteralPath.EndsWith('project-recovery.json')) { return $projectReport }
        '{"passed":true,"phases":[{},{}]}'
    }
    foreach ($LegacySaveOnly in @($true, $false)) {
        $uiPhases = [Collections.Generic.List[object]]::new()
        $restartCalls = [Collections.Generic.List[object]]::new()
        $uiSucceeded = $false
        . ([scriptblock]::Create($phaseCode))
        $expectedPhases = if ($LegacySaveOnly) { 'legacy-save' } else { 'permissions,screens' }
        $expectedRestarts = if ($LegacySaveOnly) { 0 } else { 2 }
        if (-not $uiSucceeded -or ($uiPhases.name -join ',') -cne $expectedPhases -or
            $restartCalls.Count -ne $expectedRestarts) { throw 'Merged legacy/restart routing failed.' }
    }
    $LegacySaveOnly = $false
    foreach ($projectReport in @('{"passed":false,"exactFileChecks":5,"checkpointPid":10,"restartedPid":20}',
        '{"passed":true,"exactFileChecks":0,"checkpointPid":10,"restartedPid":20}',
        '{"passed":true,"exactFileChecks":5,"checkpointPid":10,"restartedPid":10}')) {
        $uiPhases = [Collections.Generic.List[object]]::new()
        $rejected = $false
        try { . ([scriptblock]::Create($phaseCode)) } catch { $rejected = $_.Exception.Message -ceq 'Project recovery report is incomplete.' }
        if (-not $rejected -or $uiSucceeded) { throw 'Incomplete or same-PID project recovery was accepted.' }
    }
}
Write-Host 'UI runner legacy/restart routing check PASS.'

# Execute the real collector and routing against files that the restart boundary erases.
$collector = $runnerAst.Find({
    param($node)
    $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -ceq 'Collect-UiPreviewEvidence'
}, $true)
if ($null -eq $collector) { throw 'Cannot locate preview collection boundary.' }
& {
    $fixtureRoot = [IO.Path]::GetFullPath((Join-Path ([IO.Path]::GetTempPath()) ('veycad-preview-retention-' + [guid]::NewGuid())))
    New-Item -ItemType Directory -Path $fixtureRoot | Out-Null
    try {
        $uiProjectRoot = $fixtureRoot
        $uiDevice = Join-Path $fixtureRoot 'device'
        New-Item -ItemType Directory -Path $uiDevice | Out-Null
        $names = @('music-editor-empty.png', 'music-editor-controls.png', 'music-editor-preview.png',
            'music-editor-export.png', 'music-editor-final.mp4', 'editor-export-success.png',
            'editor-export-success.txt', 'editor-paused-after-seek.png', 'editor-paused-after-seek.txt',
            'editor-landscape-large-font-retry.png', 'editor-landscape-large-font-retry.txt')
        foreach ($name in $names) { [IO.File]::WriteAllText((Join-Path $uiDevice $name), "native-fixture-$name") }
        $LegacySaveOnly = $false
        $uiRunId = 'before-restart'
        $script:uiPreviewCollected = $false
        $script:retentionPulls = 0
        . ([scriptblock]::Create($collector.Extent.Text))
        function Invoke-UiAdb {
            param([string[]]$Arguments)
            if ($Arguments[0] -cne 'pull' -or -not (Test-Path -LiteralPath $uiDevice)) { throw 'Device evidence was cleared' }
            $script:retentionPulls++
            Get-ChildItem -LiteralPath $uiDevice -File | Copy-Item -Destination $Arguments[2]
        }
        function Invoke-UiPhase {
            param($Name, $SelectionArgument, $Expected)
            $uiPhases.Add([pscustomobject]@{ name = $Name; passed = $true })
        }
        function Invoke-UiNative {
            param($Executable, [switch]$EchoOutput, $Arguments)
            $inventory = Join-Path $fixtureRoot 'build/reports/ui-tests/runs/before-restart/preview-evidence/inventory.json'
            if (-not $script:uiPreviewCollected -or -not (Test-Path -LiteralPath $inventory)) {
                throw 'Restart reached pm clear before preview evidence was retained'
            }
            if (-not [IO.Path]::GetFullPath($uiDevice).StartsWith($fixtureRoot + [IO.Path]::DirectorySeparatorChar)) {
                throw 'Unsafe fixture removal target'
            }
            if (Test-Path -LiteralPath $uiDevice) { Remove-Item -LiteralPath $uiDevice -Recurse -Force }
            [pscustomobject]@{ ExitCode = 0 }
        }
        function Get-Content {
            param($LiteralPath, [switch]$Raw)
            if ($LiteralPath.EndsWith('atomic-import-restart.json')) { return '{"passed":true,"phases":[{},{}]}' }
            if ($LiteralPath.EndsWith('project-recovery.json')) { return '{"passed":true,"exactFileChecks":5,"checkpointPid":10,"restartedPid":20}' }
            Microsoft.PowerShell.Management\Get-Content -LiteralPath $LiteralPath -Raw
        }
        $Serial = 'test'; $AvdName = 'test'; $SdkRoot = 'test'
        $uiPermissionExpected = @(); $uiOrdinaryExpected = @()
        $uiPhases = [Collections.Generic.List[object]]::new()
        . ([scriptblock]::Create($phaseCode))
        Collect-UiPreviewEvidence
        $saved = Join-Path $fixtureRoot 'build/reports/ui-tests/runs/before-restart/preview-evidence'
        $inventory = [IO.File]::ReadAllText((Join-Path $saved 'inventory.json')) | ConvertFrom-Json
        if (-not $uiSucceeded -or $script:retentionPulls -ne 1 -or $inventory.files.Count -ne $names.Count -or -not $inventory.required) {
            throw 'Successful preview collection was not retained once before restart'
        }
        foreach ($entry in $inventory.files) {
            $file = Join-Path $saved $entry.path
            if ((Get-Item -LiteralPath $file).Length -ne $entry.bytes -or
                (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant() -cne $entry.sha256) {
                throw 'Retained preview inventory does not authenticate its bytes'
            }
        }
        $uiRunId = 'missing-success-evidence'
        $script:uiPreviewCollected = $false
        New-Item -ItemType Directory -Path $uiDevice | Out-Null
        [IO.File]::WriteAllText((Join-Path $uiDevice 'music-editor-empty.png'), 'partial-native-fixture')
        $rejected = $false
        try { Collect-UiPreviewEvidence -Required } catch { $rejected = $_.Exception.Message.Contains('Successful UI phase did not retain') }
        if (-not $rejected -or $script:uiPreviewCollected) { throw 'Missing successful-route evidence was accepted' }
        Collect-UiPreviewEvidence
        if (-not (Test-Path -LiteralPath (Join-Path $fixtureRoot 'build/reports/ui-tests/runs/missing-success-evidence/preview-evidence/music-editor-empty.png'))) {
            throw 'Failed phase lost its partial preview evidence'
        }
    } finally {
        $temporaryRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
        if (-not $fixtureRoot.StartsWith($temporaryRoot) -or (Split-Path -Leaf $fixtureRoot) -notlike 'veycad-preview-retention-*') {
            throw 'Unsafe fixture cleanup target'
        }
        Remove-Item -LiteralPath $fixtureRoot -Recurse -Force
    }
}
Write-Host 'Preview evidence pre-restart retention and hash-inventory check PASS.'
