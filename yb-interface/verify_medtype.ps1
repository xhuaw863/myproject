$ErrorActionPreference = 'Stop'
$base = 'http://localhost:8082'
$login = Invoke-WebRequest -Uri "$base/api/auth/login" -Method Post -ContentType 'application/json' -Body '{"tenantCode":"H42010000000","username":"admin","password":"admin123"}' -UseBasicParsing
$lj = $login.Content | ConvertFrom-Json
$tok = $lj.data.token
Write-Output ("LOGIN code=" + $lj.code + " hasToken=" + [bool]$tok)
$H = @{ Authorization = ("Bearer " + $tok) }

function GetJson($url) {
  $r = Invoke-WebRequest -Uri $url -Headers $H -UseBasicParsing -TimeoutSec 15
  return ($r.Content | ConvertFrom-Json)
}

$imp = Invoke-WebRequest -Uri "$base/api/his/med-type/import" -Method Post -Headers $H -ContentType 'application/json' -Body '{}' -UseBasicParsing -TimeoutSec 30
$ij = $imp.Content | ConvertFrom-Json
Write-Output ("IMPORT code=" + $ij.code + " total=" + $ij.data.total + " inserted=" + $ij.data.inserted + " skipped=" + $ij.data.skipped)

$pg = GetJson "$base/api/his/med-type/page?page=1&size=5"
Write-Output ("PAGE code=" + $pg.code + " total=" + $pg.data.total + " recs=" + $pg.data.records.Count)

$oOTP = GetJson "$base/api/his/med-type/options?scene=OTP"
Write-Output ("OPTIONS_OTP code=" + $oOTP.code + " n=" + $oOTP.data.Count)
$oIPT = GetJson "$base/api/his/med-type/options?scene=IPT"
Write-Output ("OPTIONS_IPT code=" + $oIPT.code + " n=" + $oIPT.data.Count)

# pick first row from page to test update + code lock
$row = $pg.data.records[0]
$origCode = $row.code
$body = @{ name = 'ZZTestName'; otpUseFlag = 1; iptUseFlag = 0; openLevels = '1,2'; sortNo = 9; status = 1; memo = 'smoke'; code = 'HACKED' } | ConvertTo-Json
$up = Invoke-WebRequest -Uri ("$base/api/his/med-type/update?id=" + $row.id) -Method Put -Headers $H -ContentType 'application/json' -Body $body -UseBasicParsing -TimeoutSec 15
$uj = $up.Content | ConvertFrom-Json
Write-Output ("UPDATE code=" + $uj.code + " name=" + $uj.data.name + " origCode=" + $origCode + " stillCode=" + $uj.data.code + " codeLocked=" + ($uj.data.code -eq $origCode) + " otp=" + $uj.data.otpUseFlag + " ipt=" + $uj.data.iptUseFlag + " levels=" + $uj.data.openLevels)

# verify code-lock: options OTP should now include this code
$o2 = GetJson "$base/api/his/med-type/options?scene=OTP"
$hit = $o2.data | Where-Object { $_.code -eq $origCode }
Write-Output ("AFTER_UPDATE_in_otp_options=" + [bool]$hit)
Write-Output "SMOKE_DONE"
