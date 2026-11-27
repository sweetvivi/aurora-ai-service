# Load .env into the process environment, then start Spring Boot.
# Usage: .\run-local.ps1
#        .\run-local.ps1 --spring.profiles.active=mysql

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$envFile = Join-Path $root ".env"

if (Test-Path $envFile) {
    Get-Content $envFile | ForEach-Object {
        $line = $_.Trim()
        if (-not $line -or $line.StartsWith("#")) { return }
        $eq = $line.IndexOf("=")
        if ($eq -lt 1) { return }
        $name = $line.Substring(0, $eq).Trim()
        $value = $line.Substring($eq + 1).Trim()
        if (($value.StartsWith('"') -and $value.EndsWith('"')) -or
            ($value.StartsWith("'") -and $value.EndsWith("'"))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        Set-Item -Path "Env:$name" -Value $value
    }
    Write-Host "Loaded environment from .env"
    Write-Host "  NEW_API_BASE_URL=$env:NEW_API_BASE_URL"
    Write-Host "  LLM_BASE_URL=$env:LLM_BASE_URL"
} else {
    Write-Host "No .env found; continuing with existing environment"
}

Set-Location $root
# Quote -D flags for PowerShell; skip tests so local start isn't blocked by test compile.
& "$root\mvnw.cmd" '-q' '-Dmaven.test.skip=true' 'spring-boot:run' @args
