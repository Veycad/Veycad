<# Real process death, separate from instrumentation. Only the isolated .uitest package is reset. #>
[CmdletBinding()]
param([string]$Serial='emulator-5556', [string]$AvdName='AutoEditUi_API36', [string]$SdkRoot='')
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$atomicRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$atomicPackage='com.veycad.app.uitest'
$atomicComponent="$atomicPackage/com.veycad.app.UiImportRestartProbeActivity"
$atomicReport=Join-Path $atomicRoot 'build/reports/ui-tests/atomic-import-restart.json'
$atomicRunId=[guid]::NewGuid().ToString()
$atomicArchive=Join-Path $atomicRoot "build/reports/ui-tests/restart/$atomicRunId"
$atomicResults=[Collections.Generic.List[object]]::new()
$atomicAllowed=$false
$atomicFailure=$null
New-Item -ItemType Directory -Path $atomicArchive -Force | Out-Null

function Invoke-AtomicAdb {
    param([string[]]$Arguments, [switch]$AllowFailure)
    $atomicLines=@(& $script:atomicAdb -s $Serial @Arguments 2>&1 | ForEach-Object { $_.ToString() })
    $atomicExit=$LASTEXITCODE
    $atomicText=$atomicLines -join "`n"
    if($atomicExit -ne 0){
        if(-not $AllowFailure){throw "adb failed: $atomicText"}
        return ''
    }
    return $atomicText
}
function Read-AtomicJson {
    param([string]$Path)
    # shell-v2 carries the remote exit code; exec-out may return 0 for a missing file.
    $atomicRaw=Invoke-AtomicAdb -Arguments @('shell','run-as',$atomicPackage,'cat',$Path) -AllowFailure
    if([string]::IsNullOrWhiteSpace($atomicRaw)){return $null}
    return ($atomicRaw | ConvertFrom-Json)
}
function Wait-AtomicJson {
    param([string]$Directory,[string]$Name,[string]$RunId)
    $atomicDeadline=[DateTime]::UtcNow.AddSeconds(90)
    do {
        $atomicError=Read-AtomicJson "$Directory/error.json"
        if($null -ne $atomicError){throw "Probe failed: $($atomicError.error)"}
        $atomicValue=Read-AtomicJson "$Directory/$Name"
        if($null -ne $atomicValue -and $atomicValue.runId -ceq $RunId){return $atomicValue}
        Start-Sleep -Milliseconds 250
    } while([DateTime]::UtcNow -lt $atomicDeadline)
    throw "Missing fresh $Name for $RunId"
}
function Start-AtomicProbe {
    param([string]$Command,[string]$Phase,[string]$RunId)
    $atomicStart=Invoke-AtomicAdb @('shell','am','start','-W','-f','0x10008000','-n',$atomicComponent,'--es','command',$Command,'--es','phase',$Phase,'--es','runId',$RunId)
    "$Command $Phase $RunId`n$atomicStart" | Add-Content -LiteralPath (Join-Path $atomicArchive 'commands.log') -Encoding UTF8
    if($atomicStart -match '(?m)^Error'){throw "Probe could not start: $atomicStart"}
}
function Copy-AtomicBinary {
    param([string[]]$Arguments,[string]$Destination)
    $atomicInfo=[Diagnostics.ProcessStartInfo]::new()
    $atomicInfo.FileName=$script:atomicAdb
    $atomicInfo.UseShellExecute=$false
    $atomicInfo.CreateNoWindow=$true
    $atomicInfo.RedirectStandardOutput=$true
    $atomicInfo.RedirectStandardError=$true
    foreach($atomicArgument in (@('-s',$Serial)+$Arguments)){$atomicInfo.ArgumentList.Add($atomicArgument)}
    $atomicProcess=[Diagnostics.Process]::new()
    $atomicProcess.StartInfo=$atomicInfo
    try {
        if(-not $atomicProcess.Start()){throw 'Could not start binary adb read'}
        $atomicOutput=[IO.File]::Create($Destination)
        try {$atomicProcess.StandardOutput.BaseStream.CopyTo($atomicOutput)} finally {$atomicOutput.Dispose()}
        $atomicError=$atomicProcess.StandardError.ReadToEnd()
        if(-not $atomicProcess.WaitForExit(30000)){$atomicProcess.Kill();throw 'Binary adb read timed out'}
        if($atomicProcess.ExitCode -ne 0){throw "Binary adb read failed: $atomicError"}
    } finally {$atomicProcess.Dispose()}
}

try {
    if($Serial -notmatch '^emulator-(\d+)$' -or [int]$Matches[1] -lt 5556 -or [int]$Matches[1] % 2 -ne 0){throw 'Use a dedicated even emulator port >=5556'}
    if([string]::IsNullOrWhiteSpace($AvdName) -or $AvdName -eq 'Fear_API_36'){throw 'A dedicated test AVD is required'}
    if(-not $SdkRoot){$SdkRoot=$env:ANDROID_HOME}
    if(-not $SdkRoot){throw 'Set ANDROID_HOME or pass -SdkRoot'}
    $atomicExtension=if([Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT){'.exe'}else{''}
    $script:atomicAdb=Join-Path $SdkRoot "platform-tools/adb$atomicExtension"
    $atomicActualAvd=@((Invoke-AtomicAdb @('emu','avd','name')) -split '[\r\n]+' | Where-Object {$_ -and $_ -ne 'OK'})
    if($atomicActualAvd.Count -ne 1 -or $atomicActualAvd[0].Trim() -cne $AvdName){throw 'AVD identity mismatch'}
    if((Invoke-AtomicAdb @('shell','getprop','sys.boot_completed')).Trim() -ne '1' -or
        (Invoke-AtomicAdb @('shell','getprop','ro.build.version.sdk')).Trim() -ne '36'){throw 'A booted API36 AVD is required'}
    $atomicAapt=Get-ChildItem -LiteralPath (Join-Path $SdkRoot 'build-tools') -Recurse -File -Filter "aapt$atomicExtension" |
        Sort-Object FullName -Descending | Select-Object -First 1
    if($null -eq $atomicAapt){throw 'aapt is required for APK isolation checks'}
    $atomicMetadata=@('app/build/outputs/apk/uiTest/output-metadata.json','app/build/intermediates/apk/uiTest/output-metadata.json') |
        ForEach-Object {Join-Path $atomicRoot $_} | Where-Object {Test-Path -LiteralPath $_} | Select-Object -First 1
    if(-not $atomicMetadata){throw 'Missing built UI APK metadata'}
    $atomicMeta=Get-Content -LiteralPath $atomicMetadata -Raw | ConvertFrom-Json
    if($atomicMeta.applicationId -cne $atomicPackage){throw 'Unsafe APK package'}
    if(@($atomicMeta.elements).Count -eq 0){throw 'No UI APKs declared'}
    foreach($atomicElement in $atomicMeta.elements){
        $atomicApk=Join-Path (Split-Path $atomicMetadata) $atomicElement.outputFile
        $atomicBadging=@(& $atomicAapt.FullName dump badging $atomicApk) -join "`n"
        if($LASTEXITCODE -ne 0 -or $atomicBadging -notmatch "(?m)^package: name='com\.veycad\.app\.uitest'"){throw 'APK manifest package mismatch'}
    }
    $atomicResolved=Invoke-AtomicAdb @('shell','cmd','package','resolve-activity','--brief','-n',$atomicComponent)
    if($atomicResolved -notmatch 'com\.veycad\.app\.UiImportRestartProbeActivity'){throw 'Restart probe unavailable; no fresh checkpoint can be produced'}
    $atomicAllowed=$true
    foreach($atomicPhase in @('FILES_PREPARED','MANIFEST_COMMITTED')){
        $atomicCaseId=[guid]::NewGuid().ToString()
        $atomicDirectory="files/import-restart-probe/$atomicCaseId"
        $atomicCaseArchive=Join-Path $atomicArchive $atomicPhase
        New-Item -ItemType Directory -Path $atomicCaseArchive -Force | Out-Null
        if((Invoke-AtomicAdb @('shell','pm','clear',$atomicPackage)).Trim() -ne 'Success'){throw 'Could not reset isolated test package'}
        Start-AtomicProbe 'prepare' $atomicPhase $atomicCaseId
        $null=Wait-AtomicJson $atomicDirectory 'ready.json' $atomicCaseId
        Start-AtomicProbe 'replace' $atomicPhase $atomicCaseId
        $atomicCheckpoint=Wait-AtomicJson $atomicDirectory 'checkpoint.json' $atomicCaseId
        if($atomicCheckpoint.phase -cne $atomicPhase){throw 'Wrong checkpoint phase'}
        $atomicOldPid=(Invoke-AtomicAdb @('shell','pidof',$atomicPackage)).Trim()
        if($atomicOldPid -cne [string]$atomicCheckpoint.pid){throw 'Checkpoint PID is not the running process'}
        $atomicCheckpoint | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $atomicCaseArchive 'checkpoint.json') -Encoding UTF8
        Invoke-AtomicAdb @('shell','am','force-stop',$atomicPackage) | Out-Null
        if((Invoke-AtomicAdb -Arguments @('shell','pidof',$atomicPackage) -AllowFailure).Trim()){throw 'Old process survived force-stop'}
        Start-AtomicProbe 'verify' $atomicPhase $atomicCaseId
        $atomicVerdict=Wait-AtomicJson $atomicDirectory 'verdict.json' $atomicCaseId
        $atomicVerdict | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $atomicCaseArchive 'verdict.json') -Encoding UTF8
        $atomicNewPid=(Invoke-AtomicAdb @('shell','pidof',$atomicPackage)).Trim()
        if($atomicNewPid -ceq $atomicOldPid -or $atomicNewPid -cne [string]$atomicVerdict.pid){throw 'A fresh process was not demonstrated'}
        $atomicExpected=if($atomicPhase -ceq 'FILES_PREPARED'){$atomicCheckpoint.old}else{$atomicCheckpoint.new}
        $atomicManifest=Read-AtomicJson 'files/source-draft/current.json'
        if($null -eq $atomicManifest -or $atomicManifest.batchId -cne $atomicExpected.batchId -or @($atomicManifest.sources).Count -ne 2){throw 'Restored wrong or incomplete batch'}
        for($atomicIndex=0;$atomicIndex -lt 2;$atomicIndex++){
            $atomicEntry=$atomicManifest.sources[$atomicIndex]
            $atomicExpectedEntry=$atomicExpected.sources[$atomicIndex]
            if($atomicEntry.fileName -cne $atomicExpectedEntry.fileName -or $atomicEntry.displayName -cne $atomicExpectedEntry.displayName -or $atomicEntry.length -ne $atomicExpectedEntry.length){throw 'Mixed sources, order or display names'}
            if($atomicEntry.fileName -notmatch '^source-[01]-[0-9a-f-]{36}\.mp4$'){throw 'Unsafe source path in test manifest'}
            $atomicCopy=Join-Path $atomicCaseArchive "selected-$atomicIndex.mp4"
            Copy-AtomicBinary @('exec-out','run-as',$atomicPackage,'cat',"files/source-draft/$($atomicEntry.fileName)") $atomicCopy
            $atomicHash=(Get-FileHash -LiteralPath $atomicCopy -Algorithm SHA256).Hash.ToLowerInvariant()
            if($atomicHash -cne $atomicExpectedEntry.sha256 -or (Get-Item -LiteralPath $atomicCopy).Length -ne $atomicExpectedEntry.length){throw 'Selected source bytes differ'}
        }
        $atomicUiDeadline=[DateTime]::UtcNow.AddSeconds(30)
        $atomicUiPassed=$false
        $atomicNamesPassed=$false
        $atomicButtonPassed=$false
        $atomicSwipes=0
        $atomicRemoteUi="/data/local/tmp/atomic-$atomicCaseId.xml"
        do {
            Invoke-AtomicAdb @('shell','uiautomator','dump',$atomicRemoteUi) | Out-Null
            $atomicUiRaw=Invoke-AtomicAdb @('exec-out','cat',$atomicRemoteUi)
            [xml]$atomicUi=$atomicUiRaw
            $atomicButton=$atomicUi.SelectSingleNode("//*[@resource-id='${atomicPackage}:id/renderButton']")
            $atomicNames=$atomicUi.SelectSingleNode("//*[@resource-id='${atomicPackage}:id/videoSelection']")
            $atomicLabel="1 · $($atomicExpected.sources[0].displayName)`n2 · $($atomicExpected.sources[1].displayName)"
            if($null -ne $atomicNames -and $atomicNames.GetAttribute('text').Replace('\n',"`n") -ceq $atomicLabel){
                $atomicNamesPassed=$true
                $atomicUiRaw | Set-Content -LiteralPath (Join-Path $atomicCaseArchive 'selection-ui.xml') -Encoding UTF8
            }
            if($null -ne $atomicButton -and $atomicButton.GetAttribute('enabled') -ceq 'true'){$atomicButtonPassed=$true}
            # Either control can begin below the viewport on a small CI display.
            # Retain each actual UI observation while searching farther down the director.
            if((-not $atomicNamesPassed -or -not $atomicButtonPassed) -and $atomicSwipes -lt 8){
                $atomicScroll=$atomicUi.SelectSingleNode("//*[@resource-id='${atomicPackage}:id/directorContent' and @scrollable='true']")
                if($null -ne $atomicScroll -and $atomicScroll.GetAttribute('bounds') -match '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$'){
                    $atomicX=[int](([int]$Matches[1]+[int]$Matches[3])/2)
                    $atomicTop=[int]$Matches[2]; $atomicHeight=[int]$Matches[4]-$atomicTop
                    $atomicY1=$atomicTop+[int]($atomicHeight*0.8); $atomicY2=$atomicTop+[int]($atomicHeight*0.2)
                    Invoke-AtomicAdb @('shell','input','swipe',"$atomicX","$atomicY1","$atomicX","$atomicY2",'300') | Out-Null
                    $atomicSwipes++
                }
            }
            $atomicUiPassed=$atomicNamesPassed -and $atomicButtonPassed
            if(-not $atomicUiPassed){Start-Sleep -Milliseconds 300}
        } while(-not $atomicUiPassed -and [DateTime]::UtcNow -lt $atomicUiDeadline)
        $atomicUiRaw | Set-Content -LiteralPath (Join-Path $atomicCaseArchive 'ui.xml') -Encoding UTF8
        Invoke-AtomicAdb @('shell','rm','-f',$atomicRemoteUi) | Out-Null
        Copy-AtomicBinary @('exec-out','screencap','-p') (Join-Path $atomicCaseArchive 'screen.png')
        if(-not $atomicUiPassed -or -not $atomicVerdict.confirmed -or $atomicVerdict.batchId -cne $atomicExpected.batchId){throw "MainActivity did not restore a ready complete choice (names=$atomicNamesPassed, button=$atomicButtonPassed, confirmed=$($atomicVerdict.confirmed))"}
        $atomicResults.Add([pscustomobject]@{phase=$atomicPhase;checkpointPid=$atomicOldPid;restartedPid=$atomicNewPid;selectedBatch=$atomicManifest.batchId;byteChecks=2;uiChecks=$true;status='PASS';archive=$atomicCaseArchive})
    }
} catch {$atomicFailure=$_.Exception.Message; Write-Host "Atomic restart gate failed: $atomicFailure"}
finally {
    if($atomicAllowed){Invoke-AtomicAdb -Arguments @('shell','am','force-stop',$atomicPackage) -AllowFailure | Out-Null}
    $atomicPassed=$null -eq $atomicFailure -and $atomicResults.Count -eq 2
    [pscustomobject]@{runId=$atomicRunId;serial=$Serial;avd=$AvdName;passed=$atomicPassed;failure=$atomicFailure;phases=$atomicResults.ToArray();archive=$atomicArchive} |
        ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $atomicReport -Encoding UTF8
}
if(-not $atomicPassed){exit 1}
Write-Host "Atomic restart gate PASS: 2/2 real process restarts, 4 source byte checks. Report: $atomicReport"
