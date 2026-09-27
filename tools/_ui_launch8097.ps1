$ErrorActionPreference = 'Stop'
$work = 'd:\study\ybtest\yb-interface'
$log = Join-Path $work 'run_ui_8097.log'
if (Test-Path $log) { Remove-Item $log -Force }
Start-Process -FilePath 'mvn' `
  -ArgumentList '-o','spring-boot:run','-Dspring-boot.run.arguments=--server.port=8097' `
  -WorkingDirectory $work -WindowStyle Hidden `
  -RedirectStandardOutput $log `
  -RedirectStandardError (Join-Path $work 'run_ui_8097.err')
Write-Output 'launching on 8097'
$ready = $false
for ($i = 0; $i -lt 60; $i++) {
  Start-Sleep -Seconds 3
  if (Get-NetTCPConnection -LocalPort 8097 -State Listen -ErrorAction SilentlyContinue) { $ready = $true; break }
}
Write-Output ("ready={0} waited={1}s" -f $ready, ($i * 3))
if ($ready) {
  try {
    $r = Invoke-WebRequest -Uri 'http://127.0.0.1:8097/css/theme.css' -UseBasicParsing -TimeoutSec 20
    Write-Output ("theme.css status=" + $r.StatusCode + " bytes=" + $r.RawContentLength + " hasYbBrand=" + ($r.Content -match '--yb-brand:'))
    $h = Invoke-WebRequest -Uri 'http://127.0.0.1:8097/index.html' -UseBasicParsing -TimeoutSec 20
    Write-Output ("index.html hasThemeLink=" + ($h.Content -match 'theme\.css'))
  } catch { Write-Output ("HTTP FAIL " + $_.Exception.Message) }
}
