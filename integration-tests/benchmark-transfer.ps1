param([string]$Serial="emulator-5554",[int]$SizeMiB=16)
$ErrorActionPreference="Stop"
& "$PSScriptRoot\..\gradlew.bat" :server-android:serverJar :cli:installDist --console=plain
$cli="$PSScriptRoot\..\cli\build\install\cli\bin\cli.bat"
$jar=(Resolve-Path "$PSScriptRoot\..\server-android\build\server\droidfiles-server.jar").Path
$id=[guid]::NewGuid().ToString('N');$remote="/data/local/tmp/droidfiles-benchmark-$id";$source=Join-Path $env:TEMP "$id-source.bin";$custom=Join-Path $env:TEMP "$id-custom.bin";$adbCopy=Join-Path $env:TEMP "$id-adb.bin"
try {
  $stream=[IO.File]::Create($source);try{$buffer=New-Object byte[] (1024*1024);1..$SizeMiB|ForEach-Object{[Security.Cryptography.RandomNumberGenerator]::Fill($buffer);$stream.Write($buffer)}}finally{$stream.Dispose()}
  & adb -s $Serial shell mkdir $remote
  $adbPush=(Measure-Command{& adb -s $Serial push $source "$remote/adb.bin"|Out-Null}).TotalSeconds
  $adbPull=(Measure-Command{& adb -s $Serial pull "$remote/adb.bin" $adbCopy|Out-Null}).TotalSeconds
  $benchOutput=(& "$PSScriptRoot\..\gradlew.bat" :cli:benchmarkTransfer "-PbenchSerial=$Serial" "-PbenchJar=$jar" "-PbenchSource=$source" "-PbenchRemote=$remote/custom.bin" "-PbenchTarget=$custom" --console=plain 2>$null) -join "`n"
  $match=[regex]::Match($benchOutput,'DROIDFILES_BENCH (\d+) (\d+)');if(!$match.Success){throw "Active-session benchmark output missing"};$customPush=[long]$match.Groups[1].Value/1e9;$customPull=[long]$match.Groups[2].Value/1e9
  if((Get-FileHash $source).Hash -ne (Get-FileHash $custom).Hash -or (Get-FileHash $source).Hash -ne (Get-FileHash $adbCopy).Hash){throw "Benchmark SHA-256 mismatch"}
  $bytes=$SizeMiB*1MB;$result=[ordered]@{date=(Get-Date).ToString('o');serial=$Serial;adbVersion=((& adb version)[0]);connection='Android emulator via local ADB';sizeBytes=$bytes;adbPushMiBs=[math]::Round($SizeMiB/$adbPush,2);droidFilesPushMiBs=[math]::Round($SizeMiB/$customPush,2);pushRatio=[math]::Round($adbPush/$customPush,3);adbPullMiBs=[math]::Round($SizeMiB/$adbPull,2);droidFilesPullMiBs=[math]::Round($SizeMiB/$customPull,2);pullRatio=[math]::Round($adbPull/$customPull,3);scope='DroidFiles data-channel timings exclude server bootstrap and teardown; adb timings are process end-to-end'}
  $result|ConvertTo-Json|Tee-Object -FilePath "$PSScriptRoot\benchmark-last.json"
} finally {& adb -s $Serial shell rm -rf $remote;Remove-Item $source,$custom,$adbCopy -ErrorAction SilentlyContinue}
