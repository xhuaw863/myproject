$ErrorActionPreference = 'Continue'
$work = 'd:\study\ybtest\yb-interface'
$log = Join-Path $work 'run_emp_18082.log'
if (Test-Path $log) { Remove-Item $log -Force }
Start-Process -FilePath 'mvn' `
  -ArgumentList '-o','spring-boot:run','-Dspring-boot.run.arguments=--server.port=18082' `
  -WorkingDirectory $work -WindowStyle Hidden `
  -RedirectStandardOutput $log `
  -RedirectStandardError (Join-Path $work 'run_emp_18082.err')
Write-Output 'launching on 18082'
$ready = $false
for ($i = 0; $i -lt 80; $i++) {
  Start-Sleep -Seconds 3
  try {
    $r = Invoke-WebRequest -Uri 'http://127.0.0.1:18082/index.html' -UseBasicParsing -TimeoutSec 5
    if ($r.StatusCode -eq 200) { $ready = $true; break }
  } catch { }
}
Write-Output ("ready={0} waited={1}s" -f $ready, ($i * 3))
if ($ready) {
  try {
    $body = '{"tenantCode":"H42010000000","username":"admin","password":"admin123"}'
    $r = Invoke-WebRequest -Uri 'http://127.0.0.1:18082/api/auth/login' -Method POST -ContentType 'application/json' -Body $body -UseBasicParsing -TimeoutSec 20
    Write-Output ("login status=" + $r.StatusCode)
  } catch { Write-Output ("LOGIN FAIL " + $_.Exception.Message) }
} else {
  Get-Content $log -Tail 20
}
