$ErrorActionPreference = 'Stop'
$src = 'd:\study\ybtest\yb-interface\src\main\resources\static'
$dst = 'd:\study\ybtest\yb-interface\target\classes\static'
$files = @('index.html', 'css\theme.css', 'css\his.css', 'js\app.js')
Get-ChildItem "$src\js\views" -Filter *.js -Recurse | ForEach-Object {
  $files += $_.FullName.Substring($src.Length + 1)
}
$n = 0
foreach ($f in $files) {
  $s = Join-Path $src $f; $d = Join-Path $dst $f
  if (-not (Test-Path $d)) { New-Item -ItemType Directory -Force -Path (Split-Path $d) | Out-Null }
  Copy-Item -Force $s $d
  if ((Get-FileHash $s).Hash -ne (Get-FileHash $d).Hash) { Write-Output "MISMATCH $f" } else { $n++ }
}
Write-Output "synced_ok=$n total=$($files.Count)"
foreach ($p in 8080, 8097) {
  $c = Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue
  Write-Output ("port_{0}={1}" -f $p, $(if ($c) { 'LISTEN pid=' + $c[0].OwningProcess } else { 'free' }))
}
