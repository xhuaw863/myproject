$ErrorActionPreference = 'Stop'
$work = 'd:\study\ybtest\yb-interface'
# 1) 静态资源全量下发(本轮含右栏 clamp 修复与新 theme 双件套)
Copy-Item -Recurse -Force (Join-Path $work 'src\main\resources\static\*') (Join-Path $work 'target\classes\static\')
Write-Output 'static synced'
# 2) 后台启动 8080
$log = Join-Path $work 'run_start.log'
if (Test-Path $log) { Remove-Item $log -Force }
Start-Process -FilePath 'mvn' `
  -ArgumentList '-o','spring-boot:run','-Dspring-boot.run.arguments=--server.port=8080' `
  -WorkingDirectory $work -WindowStyle Hidden `
  -RedirectStandardOutput $log -RedirectStandardError (Join-Path $work 'run_start.err')
Write-Output 'launching on 8080'
# 3) 轮询就绪
$ready = $false
for ($i = 0; $i -lt 40; $i++) {
  Start-Sleep -Seconds 3
  if (Select-String -Path $log -Pattern 'Started YbInterfaceApplication' -Quiet -ErrorAction SilentlyContinue) { $ready = $true; break }
}
Write-Output ("ready={0} waited={1}s" -f $ready, ($i * 3))
if ($ready) {
  $r = Invoke-WebRequest -Uri 'http://localhost:8080/' -UseBasicParsing -TimeoutSec 8
  Write-Output ("home status=" + $r.StatusCode)
  $c = Invoke-WebRequest -Uri 'http://localhost:8080/css/his.css' -UseBasicParsing -TimeoutSec 8
  Write-Output ("his.css hasDwRightClamp=" + ($c.Content -match 'dw-right \{ width: clamp'))
} else {
  Get-Content $log -Tail 20
}
