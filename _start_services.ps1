# Start all microservices of urban-script-reservation (all comments in English
# to stay safe across codepages: PS 5.1 reads BOM-less .ps1 as ANSI/GBK).
#
# Bugfix history:
#   Old script built jar paths with "..." interpolation "$root\order-service\..."
#   which lost the root prefix in some PowerShell environments, causing:
#     "Unable to access jarfile \order-service\target\order-service-1.0.0.jar"
#   Fixed by using Join-Path explicitly, Test-Path pre-check before launch,
#   and resolving java via (Get-Command java).Source.

$ErrorActionPreference = 'Stop'

# $PSScriptRoot = directory of this script (no hard-coded path needed)
$root = $PSScriptRoot
$log  = Join-Path $root '_logs'
New-Item -ItemType Directory -Force -Path $log | Out-Null

# Full path of java executable (avoid PATH resolution differences)
$javaExe = (Get-Command java -ErrorAction Stop).Source

# Order: order -> shop -> user -> gateway -> agent -> recommend (depends on Nacos up)
$svcs = @(
  @{ name = 'order';   jar = Join-Path $root 'order-service\target\order-service-1.0.0.jar';              port = 8084 },
  @{ name = 'shop';    jar = Join-Path $root 'shop-service\target\shop-service-1.0.0.jar';                port = 8083 },
  @{ name = 'user';    jar = Join-Path $root 'user-service\target\user-service-1.0.0.jar';                port = 8082 },
  @{ name = 'gateway'; jar = Join-Path $root 'reservation-gateway\target\reservation-gateway-1.0.0.jar';  port = 8081 },
  @{ name = 'agent';   jar = Join-Path $root 'agent-gateway\target\agent-gateway-1.0.0.jar' },
  @{ name = 'recommend'; jar = Join-Path $root 'recommend-service\target\recommend-service-1.0.0.jar';    port = 8086 }
)

foreach ($s in $svcs) {
  # Verify the jar actually exists before launching; fail loudly if missing
  if (-not (Test-Path -LiteralPath $s.jar)) {
    Write-Error "jar not found, skipped $($s.name): $($s.jar)"
    continue
  }
  $out = Join-Path $log "$($s.name).out.log"
  $err = Join-Path $log "$($s.name).err.log"
  Start-Process -FilePath $javaExe -ArgumentList @('-jar', $s.jar) `
    -RedirectStandardOutput $out -RedirectStandardError $err -WindowStyle Hidden
  Write-Output "STARTED $($s.name) -> $($s.jar)"
}
Write-Output "ALL_DISPATCHED"