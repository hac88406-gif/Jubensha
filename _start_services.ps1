# 启动 urban-script-reservation 全部微服务
$root = "D:\javaProgram\urban-script-reservation"
$log  = "D:\javaProgram\urban-script-reservation\_logs"
New-Item -ItemType Directory -Force -Path $log | Out-Null

# 顺序: order -> shop -> user -> gateway -> agent-gateway (依赖Nacos已就绪)
$svcs = @(
  @{ name="order";  jar="$root\order-service\target\order-service-1.0.0.jar";         port=8084 },
  @{ name="shop";   jar="$root\shop-service\target\shop-service-1.0.0.jar";           port=8083 },
  @{ name="user";   jar="$root\user-service\target\user-service-1.0.0.jar";           port=8082 },
  @{ name="gateway";jar="$root\reservation-gateway\target\reservation-gateway-1.0.0.jar"; port=8081 },
  @{ name="agent";  jar="$root\agent-gateway\target\agent-gateway-1.0.0.jar" }
)

foreach ($s in $svcs) {
  $out = "$log\$($s.name).out.log"
  $err = "$log\$($s.name).err.log"
  Start-Process -FilePath "java" -ArgumentList "-jar", $s.jar `
    -RedirectStandardOutput $out -RedirectStandardError $err -WindowStyle Hidden
  Write-Output "STARTED $($s.name) -> $out"
}
Write-Output "ALL_DISPATCHED"
