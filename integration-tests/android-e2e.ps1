param([string]$Serial="emulator-5554")
$ErrorActionPreference="Stop"
& "$PSScriptRoot\..\gradlew.bat" :server-android:serverJar :cli:installDist
$cli="$PSScriptRoot\..\cli\build\install\cli\bin\cli.bat"
$jar=(Resolve-Path "$PSScriptRoot\..\server-android\build\server\droidfiles-server.jar").Path
$root="/data/local/tmp/droidfiles-e2e-$([guid]::NewGuid().ToString('N'))"
$source=Join-Path $env:TEMP "droidfiles-source.bin"; $target=Join-Path $env:TEMP "droidfiles-target.bin"
try {
  [IO.File]::WriteAllBytes($source,(1..1048576|ForEach-Object{[byte]($_%251)}))
  & $cli $Serial $jar mkdir $root
  & $cli $Serial $jar push $source "$root/source.bin"
  & $cli $Serial $jar pull "$root/source.bin" $target
  if((Get-FileHash $source).Hash -ne (Get-FileHash $target).Hash){throw "SHA-256 mismatch"}
  & $cli $Serial $jar mkdir "$root/copies"
  & $cli $Serial $jar cp "$root/source.bin" "$root/copies"
  & $cli $Serial $jar mv "$root/source.bin" "$root/renamed.bin"
  & $cli $Serial $jar ls $root
} finally { & $cli $Serial $jar rm $root; Remove-Item $source,$target -ErrorAction SilentlyContinue }

