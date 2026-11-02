<# Actual process death and bounded project scratch recovery. Isolated .uitest APK only. #>
[CmdletBinding()]
param([string]$Serial='emulator-5556',[string]$AvdName='AutoEditUi_API36',[string]$SdkRoot='')
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$recoveryRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$recoveryPackage='com.veycad.app.uitest'
$recoveryComponent="$recoveryPackage/com.veycad.app.UiProjectRecoveryProbeActivity"
$recoveryRunId=[guid]::NewGuid().ToString()
$recoveryArchive=Join-Path $recoveryRoot "build/reports/ui-tests/project-recovery/$recoveryRunId"
$recoveryReport=Join-Path $recoveryRoot 'build/reports/ui-tests/project-recovery.json'
$recoveryAllowed=$false
$recoveryFailure=$null
$recoveryChecks=0
$recoveryBefore=$null
$recoveryAfter=$null
New-Item -ItemType Directory -Path $recoveryArchive -Force | Out-Null
function Invoke-RecoveryAdb {
    param([string[]]$Arguments,[switch]$AllowFailure)
    $lines=@(& $script:recoveryAdb -s $Serial @Arguments 2>&1 | ForEach-Object {$_.ToString()})
    $code=$LASTEXITCODE
    if($code -ne 0){if($AllowFailure){return ''};throw "adb failed: $($lines -join "`n")"}
    return $lines -join "`n"
}
function Wait-RecoveryJson([string]$Name) {
    $deadline=[DateTime]::UtcNow.AddSeconds(120)
    do {
        $raw=Invoke-RecoveryAdb -Arguments @('shell','run-as',$recoveryPackage,'cat',"files/project-recovery-probe/$recoveryRunId/error.json") -AllowFailure
        if($raw){throw "Project recovery probe failed: $raw"}
        $raw=Invoke-RecoveryAdb -Arguments @('shell','run-as',$recoveryPackage,'cat',"files/project-recovery-probe/$recoveryRunId/$Name") -AllowFailure
        if($raw){$value=$raw | ConvertFrom-Json;if($value.runId -ceq $recoveryRunId){return $value}}
        Start-Sleep -Milliseconds 250
    }while([DateTime]::UtcNow -lt $deadline)
    throw "Missing fresh $Name"
}
function Start-RecoveryProbe([string]$Command) {
    $start=Invoke-RecoveryAdb @('shell','am','start','-W','-f','0x10008000','-n',$recoveryComponent,'--es','command',$Command,'--es','runId',$recoveryRunId)
    "$Command`n$start" | Add-Content -LiteralPath (Join-Path $recoveryArchive 'commands.log') -Encoding utf8
    if($start -match '(?m)^Error'){throw 'Probe could not start'}
}
function Copy-RecoveryFile([string]$Remote,[string]$Destination) {
    $info=[Diagnostics.ProcessStartInfo]::new()
    $info.FileName=$script:recoveryAdb;$info.UseShellExecute=$false;$info.CreateNoWindow=$true
    $info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true
    foreach($item in @('-s',$Serial,'exec-out','run-as',$recoveryPackage,'cat',$Remote)){$info.ArgumentList.Add($item)}
    $process=[Diagnostics.Process]::new();$process.StartInfo=$info
    try {
        if(-not $process.Start()){throw 'Could not read project file'}
        $output=[IO.File]::Create($Destination)
        try {$process.StandardOutput.BaseStream.CopyTo($output)}finally{$output.Dispose()}
        $stderr=$process.StandardError.ReadToEnd()
        if(-not $process.WaitForExit(30000)){$process.Kill();throw 'Project byte copy timed out'}
        if($process.ExitCode -ne 0){throw "Project byte copy failed: $stderr"}
    }finally{$process.Dispose()}
}
try {
    if($Serial -notmatch '^emulator-(\d+)$' -or [int]$Matches[1] -lt 5556 -or [int]$Matches[1]%2 -ne 0){throw 'Use a dedicated even emulator port >=5556'}
    if(-not $AvdName -or $AvdName -eq 'Fear_API_36'){throw 'Dedicated AVD identity required'}
    if(-not $SdkRoot){$SdkRoot=$env:ANDROID_HOME}
    if(-not $SdkRoot){throw 'Set ANDROID_HOME or pass -SdkRoot'}
    $suffix=if([Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT){'.exe'}else{''}
    $script:recoveryAdb=Join-Path $SdkRoot "platform-tools/adb$suffix"
    $actual=@((Invoke-RecoveryAdb @('emu','avd','name')) -split '[\r\n]+' | Where-Object {$_ -and $_ -ne 'OK'})
    if($actual.Count -ne 1 -or $actual[0].Trim() -cne $AvdName){throw 'AVD identity mismatch'}
    if((Invoke-RecoveryAdb @('shell','getprop','sys.boot_completed')).Trim() -ne '1' -or
        (Invoke-RecoveryAdb @('shell','getprop','ro.build.version.sdk')).Trim() -ne '36'){throw 'Booted API36 required'}
    $aapt=Get-ChildItem -LiteralPath (Join-Path $SdkRoot 'build-tools') -Recurse -File -Filter "aapt$suffix" | Sort-Object FullName -Descending | Select-Object -First 1
    if($null -eq $aapt){throw 'aapt required'}
    $metadata=@('app/build/outputs/apk/uiTest/output-metadata.json','app/build/intermediates/apk/uiTest/output-metadata.json') |
        ForEach-Object {Join-Path $recoveryRoot $_} | Where-Object {Test-Path -LiteralPath $_} | Select-Object -First 1
    if(-not $metadata){throw 'Missing isolated APK metadata'}
    $meta=Get-Content -LiteralPath $metadata -Raw | ConvertFrom-Json
    if($meta.applicationId -cne $recoveryPackage -or @($meta.elements).Count -eq 0){throw 'Unsafe APK metadata'}
    foreach($element in $meta.elements){
        $badging=@(& $aapt.FullName dump badging (Join-Path (Split-Path $metadata) $element.outputFile)) -join "`n"
        if($LASTEXITCODE -ne 0 -or $badging -notmatch "(?m)^package: name='com\.veycad\.app\.uitest'"){throw 'APK isolation mismatch'}
    }
    if((Invoke-RecoveryAdb @('shell','cmd','package','resolve-activity','--brief','-n',$recoveryComponent)) -notmatch 'com\.veycad\.app\.UiProjectRecoveryProbeActivity'){throw 'Project recovery probe unavailable'}
    $recoveryAllowed=$true
    Invoke-RecoveryAdb @('shell','am','force-stop',$recoveryPackage) | Out-Null
    # No pm clear: previous host-retained reports and ordinary native AAC evidence must survive.
    Start-RecoveryProbe 'prepare'
    $recoveryBefore=Wait-RecoveryJson 'checkpoint.json'
    $recoveryBefore | ConvertTo-Json -Depth 16 | Set-Content -LiteralPath (Join-Path $recoveryArchive 'checkpoint.json') -Encoding utf8
    $oldPid=(Invoke-RecoveryAdb @('shell','pidof',$recoveryPackage)).Trim()
    if($oldPid -cne [string]$recoveryBefore.pid -or -not $recoveryBefore.cleanupDeniedWhileOwned -or $recoveryBefore.openSpillValues -ne 20000){throw 'Actual live project/spill checkpoint missing'}
    Invoke-RecoveryAdb @('shell','am','force-stop',$recoveryPackage) | Out-Null
    if((Invoke-RecoveryAdb -Arguments @('shell','pidof',$recoveryPackage) -AllowFailure).Trim()){throw 'Old process survived'}
    Start-RecoveryProbe 'verify'
    $recoveryAfter=Wait-RecoveryJson 'verdict.json'
    $recoveryAfter | ConvertTo-Json -Depth 16 | Set-Content -LiteralPath (Join-Path $recoveryArchive 'verdict.json') -Encoding utf8
    $newPid=(Invoke-RecoveryAdb @('shell','pidof',$recoveryPackage)).Trim()
    if($oldPid -ceq $newPid -or $newPid -cne [string]$recoveryAfter.pid -or -not $recoveryAfter.projectUnchanged -or $recoveryAfter.scratchRemoved -lt 4){throw 'Fresh PID/retired scratch proof missing'}
    if($recoveryBefore.projectId -cne $recoveryAfter.projectId -or $recoveryBefore.projectId -notmatch '^recovery-[a-f0-9-]{36}$'){throw 'Wrong recovered project'}
    $expected=@($recoveryBefore.projectFiles)
    $observed=@($recoveryAfter.projectFiles)
    if($expected.Count -eq 0 -or $expected.Count -ne $observed.Count -or @($expected.path | Sort-Object -Unique).Count -ne $expected.Count){throw 'Incomplete project inventory'}
    $files=Join-Path $recoveryArchive 'project';New-Item -ItemType Directory -Path $files | Out-Null
    for($index=0;$index -lt $expected.Count;$index++){
        $entry=$expected[$index];$actualEntry=$observed[$index]
        if($entry.path -cne $actualEntry.path -or $entry.sha256 -cne $actualEntry.sha256 -or $entry.bytes -ne $actualEntry.bytes -or
            $entry.path -notmatch '^[A-Za-z0-9_./-]+$' -or $entry.path -match '(^|/)\.\.(/|$)' -or $entry.path.StartsWith('/')){throw 'Changed/unsafe project inventory'}
        $copy=Join-Path $files $entry.path
        New-Item -ItemType Directory -Path (Split-Path $copy) -Force | Out-Null
        Copy-RecoveryFile "files/projects/$($recoveryAfter.projectId)/$($entry.path)" $copy
        if((Get-Item -LiteralPath $copy).Length -ne $entry.bytes -or (Get-FileHash -LiteralPath $copy -Algorithm SHA256).Hash.ToLowerInvariant() -cne $entry.sha256){throw 'Recovered project bytes differ'}
        $recoveryChecks++
    }
    $deadline=[DateTime]::UtcNow.AddSeconds(90)
    $ready=$false
    $previewSwipes=0
    do {
        Invoke-RecoveryAdb @('shell','uiautomator','dump',"/data/local/tmp/project-$recoveryRunId.xml") | Out-Null
        $ui=Invoke-RecoveryAdb @('exec-out','cat',"/data/local/tmp/project-$recoveryRunId.xml")
        [xml]$tree=$ui
        $ready=$null -ne $tree.SelectSingleNode("//*[@text='Черновик · пауза']")
        # As in the import restart gate, reveal controls below a compact viewport.
        $previewStatus=$tree.SelectSingleNode("//*[@text='Черновик · пауза' or @text='Подготавливаю просмотр…' or starts-with(@text,'Не удалось показать кадр.')]")
        if($null -eq $previewStatus -and $previewSwipes -lt 8){
            $viewport=$tree.SelectSingleNode("//*[@class='android.widget.ScrollView' and @scrollable='true']")
            if($null -ne $viewport -and $viewport.GetAttribute('bounds') -match '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$'){
                $x=[int](([int]$Matches[1]+[int]$Matches[3])/2)
                $top=[int]$Matches[2]; $height=[int]$Matches[4]-$top
                $y1=$top+[int]($height*0.8); $y2=$top+[int]($height*0.2)
                $ui | Set-Content -LiteralPath (Join-Path $recoveryArchive "before-preview-scroll-$previewSwipes.xml") -Encoding utf8
                Invoke-RecoveryAdb @('shell','input','swipe',"$x","$y1","$x","$y2",'300') | Out-Null
                $previewSwipes++
            }
        }
        if(-not $ready){Start-Sleep -Milliseconds 250}
    }while(-not $ready -and [DateTime]::UtcNow -lt $deadline)
    $ui | Set-Content -LiteralPath (Join-Path $recoveryArchive 'recovered-editor.xml') -Encoding utf8
    if(-not $ready){throw 'Recovered editor did not reach real native paused preview'}
    Invoke-RecoveryAdb @('shell','rm','-f',"/data/local/tmp/project-$recoveryRunId.xml") | Out-Null
}catch{$recoveryFailure=$_.Exception.Message;Write-Host "Project recovery gate failed: $recoveryFailure"}
finally {
    if($recoveryAllowed){Invoke-RecoveryAdb -Arguments @('shell','am','force-stop',$recoveryPackage) -AllowFailure | Out-Null}
    $passed=$null -eq $recoveryFailure -and $recoveryChecks -gt 0
    [pscustomobject]@{runId=$recoveryRunId;serial=$Serial;avd=$AvdName;passed=$passed;failure=$recoveryFailure;
        source=if($null -ne $recoveryAfter){$recoveryAfter.source}else{$null};scope='controlled-force-stop-not-OEM-LMK';
        checkpointPid=if($null -ne $recoveryBefore){$recoveryBefore.pid}else{$null};restartedPid=if($null -ne $recoveryAfter){$recoveryAfter.pid}else{$null};
        exactFileChecks=$recoveryChecks;archive=$recoveryArchive} | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $recoveryReport -Encoding utf8
}
if(-not $passed){exit 1}
Write-Host "Project recovery PASS: fresh process, $recoveryChecks exact project files, retired scratch removed, native editor ready. $recoveryReport"
