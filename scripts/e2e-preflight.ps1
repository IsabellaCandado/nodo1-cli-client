# Verifica que los nodos necesarios estén escuchando antes de lanzar la prueba E2E.
# Uso:  .\scripts\e2e-preflight.ps1  [-HostName localhost]
param([string]$HostName = "localhost")

$checks = @(
    @{ Name = "PostgreSQL (opcional, para verificar BD)"; Port = 5432;  Required = $false },
    @{ Name = "Pasarela de pagos (PaymentGatewayAdapter)"; Port = 10000; Required = $true  },
    @{ Name = "Server: callback del Processor";            Port = 10001; Required = $true  },
    @{ Name = "Server: CheckoutService";                   Port = 10002; Required = $true  }
)

$allRequiredOk = $true
foreach ($c in $checks) {
    $ok = (Test-NetConnection -ComputerName $HostName -Port $c.Port -WarningAction SilentlyContinue).TcpTestSucceeded
    $mark = if ($ok) { "OK  " } elseif ($c.Required) { "FALTA" } else { "no   " }
    Write-Host ("[{0}] {1} (puerto {2})" -f $mark, $c.Name, $c.Port)
    if (-not $ok -and $c.Required) { $allRequiredOk = $false }
}

if ($allRequiredOk) { Write-Host "`nListo para: .\gradlew.bat e2eTest" -ForegroundColor Green }
else { Write-Host "`nLevante los nodos marcados como FALTA." -ForegroundColor Red; exit 1 }