<#
.SYNOPSIS
    Full-stack ephemeral Milestone 4D Chromium Playwright E2E launcher for SOMNISENSE.

.DESCRIPTION
    Prompts privately once for PostgreSQL password, creates/resets an isolated `somnisense_e2e_db`
    database (never modifying `somnisense_db` or `somnisense_test_db`), starts ai-service (8090),
    simulator-worker (8095), Spring Boot backend (8081), and Vite live frontend (5173), runs
    `scripts/qa/stage4d_full_stack_e2e.py`, and tears down all child processes & secrets in finally.
#>

[CmdletBinding()]
param(
    [string]$DbHost = $(if ($env:SOMNISENSE_E2E_DB_HOST) { $env:SOMNISENSE_E2E_DB_HOST } else { 'localhost' }),
    [int]$DbPort = $(if ($env:SOMNISENSE_E2E_DB_PORT) { [int]$env:SOMNISENSE_E2E_DB_PORT } else { 5432 }),
    [string]$DbUser = $(if ($env:SOMNISENSE_E2E_DB_USER) { $env:SOMNISENSE_E2E_DB_USER } else { 'postgres' }),
    [string]$E2eDbName = $(if ($env:SOMNISENSE_E2E_DB_NAME) { $env:SOMNISENSE_E2E_DB_NAME } else { 'somnisense_e2e_db' }),
    [int]$BackendPort = 8081,
    [int]$FrontendPort = 5173,
    [int]$AiPort = 8090,
    [int]$SimPort = 8095
)

$ErrorActionPreference = 'Stop'

if ($E2eDbName -ne 'somnisense_e2e_db') {
    Write-Error "Refusing to run E2E setup against '$E2eDbName'. Expected 'somnisense_e2e_db'."
    exit 1
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$venvPython = 'C:\Users\LORD\Downloads\Minor_Project\SomniSense\.venv\Scripts\python.exe'
$backendDir = Join-Path $repoRoot 'backend'
$frontendDir = Join-Path $repoRoot 'frontend'
$aiDir = Join-Path $repoRoot 'ai-service'
$simDir = Join-Path $repoRoot 'simulator-worker'
$e2eScript = Join-Path $PSScriptRoot 'stage4d_full_stack_e2e.py'

function Find-PsqlPath {
    $cmd = Get-Command psql -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    $fallback = Get-ChildItem -Path 'C:\Program Files\PostgreSQL\*\bin\psql.exe' -ErrorAction SilentlyContinue |
        Sort-Object FullName -Descending | Select-Object -First 1
    if ($fallback) { return $fallback.FullName }
    return $null
}

function New-RandomSecret([int]$Length = 48) {
    $bytes = New-Object byte[] ($Length)
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    $chars = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789'
    -join ($bytes | ForEach-Object { $chars[$_ % $chars.Length] })
}

function Start-ChildProcess($FilePath, $ArgumentList, $WorkingDirectory, $EnvMap) {
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $FilePath
    $psi.Arguments = $ArgumentList
    $psi.WorkingDirectory = $WorkingDirectory
    $psi.UseShellExecute = $false
    foreach ($k in $EnvMap.Keys) {
        $psi.EnvironmentVariables[$k] = $EnvMap[$k]
    }
    return [System.Diagnostics.Process]::Start($psi)
}

function Wait-HttpOk($Url, [int]$TimeoutSeconds = 90) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        try {
            $resp = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 3
            if ($resp.StatusCode -ge 200 -and $resp.StatusCode -lt 300) {
                return
            }
        } catch {
            Start-Sleep -Milliseconds 700
        }
    }
    throw "Timed out waiting for $Url"
}

$psqlPath = Find-PsqlPath
if (-not $psqlPath) {
    Write-Error "psql.exe not found."
    exit 1
}
if (-not (Test-Path $venvPython)) {
    Write-Error "Python venv not found at $venvPython"
    exit 1
}

$securePassword = $null
$bstr = [IntPtr]::Zero
$plainPassword = $null
$procs = @()
$exitCode = 1

try {
    if ($env:SOMNISENSE_E2E_DB_PASSWORD_FILE -and (Test-Path $env:SOMNISENSE_E2E_DB_PASSWORD_FILE)) {
        $plainPassword = (Get-Content -Raw -Path $env:SOMNISENSE_E2E_DB_PASSWORD_FILE).TrimEnd("`r", "`n")
    } elseif (-not [string]::IsNullOrEmpty($env:SOMNISENSE_E2E_DB_PASSWORD)) {
        $plainPassword = $env:SOMNISENSE_E2E_DB_PASSWORD
    } else {
        $securePassword = Read-Host -Prompt "Enter PostgreSQL password for user '$DbUser' (for isolated $E2eDbName)" -AsSecureString
        $bstr = [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
        $plainPassword = [System.Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
    }

    if ([string]::IsNullOrEmpty($plainPassword)) {
        throw "PostgreSQL password cannot be empty."
    }

    Write-Host "Resetting isolated E2E database '$E2eDbName'..." -ForegroundColor Cyan
    $env:PGPASSWORD = $plainPassword
    & $psqlPath -X -h $DbHost -p $DbPort -U $DbUser -d postgres -v ON_ERROR_STOP=1 -c "DROP DATABASE IF EXISTS somnisense_e2e_db;" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Failed dropping previous somnisense_e2e_db" }
    & $psqlPath -X -h $DbHost -p $DbPort -U $DbUser -d postgres -v ON_ERROR_STOP=1 -c "CREATE DATABASE somnisense_e2e_db OWNER $DbUser;" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Failed creating somnisense_e2e_db" }
    Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue

    $ingestionKey = New-RandomSecret 48
    $aiKey = New-RandomSecret 48
    $simKey = New-RandomSecret 48
    $adminUser = 'e2e_admin'
    $adminPass = New-RandomSecret 20

    Write-Host "Starting Python ai-service (127.0.0.1:$AiPort)..." -ForegroundColor Cyan
    $procs += Start-ChildProcess $venvPython "-m uvicorn app.main:app --host 127.0.0.1 --port $AiPort" $aiDir @{
        'AI_SERVICE_API_KEY' = $aiKey
        'AI_SERVICE_HOST' = '127.0.0.1'
        'AI_SERVICE_PORT' = "$AiPort"
    }

    Write-Host "Starting Python simulator-worker (127.0.0.1:$SimPort)..." -ForegroundColor Cyan
    $procs += Start-ChildProcess $venvPython "-m uvicorn app.main:app --host 127.0.0.1 --port $SimPort" $simDir @{
        'SIM_WORKER_INTERNAL_KEY' = $simKey
        'INGESTION_API_KEY' = $ingestionKey
        'BACKEND_BASE_URL' = "http://127.0.0.1:$BackendPort"
        'SIM_WORKER_HOST' = '127.0.0.1'
        'SIM_WORKER_PORT' = "$SimPort"
    }

    Wait-HttpOk "http://127.0.0.1:$AiPort/internal/ai/ready" 30
    Wait-HttpOk "http://127.0.0.1:$SimPort/internal/sim/ready" 30

    Write-Host "Starting Spring Boot backend (127.0.0.1:$BackendPort -> $E2eDbName)..." -ForegroundColor Cyan
    $procs += Start-ChildProcess 'cmd.exe' "/c mvnw.cmd spring-boot:run" $backendDir @{
        'SERVER_PORT' = "$BackendPort"
        'DB_URL' = "jdbc:postgresql://$DbHost`:$DbPort/$E2eDbName"
        'DB_USERNAME' = $DbUser
        'DB_PASSWORD' = $plainPassword
        'INGESTION_API_KEY' = $ingestionKey
        'AI_SERVICE_BASE_URL' = "http://127.0.0.1:$AiPort"
        'AI_SERVICE_API_KEY' = $aiKey
        'AI_SCHEDULER_ENABLED' = 'true'
        'AI_SCHEDULER_FIXED_DELAY_MS' = '2000'
        'SIM_WORKER_BASE_URL' = "http://127.0.0.1:$SimPort"
        'SIM_WORKER_INTERNAL_KEY' = $simKey
        'SIM_INGESTION_BASE_URL' = "http://127.0.0.1:$BackendPort"
        'CORS_ALLOWED_ORIGINS' = "http://localhost:$FrontendPort,http://127.0.0.1:$FrontendPort"
        'SOMNISENSE_ADMIN_USERNAME' = $adminUser
        'SOMNISENSE_ADMIN_EMAIL' = 'e2e-admin@somnisense.local'
        'SOMNISENSE_ADMIN_PASSWORD' = $adminPass
    }

    Write-Host "Starting Vite live frontend (127.0.0.1:$FrontendPort)..." -ForegroundColor Cyan
    $procs += Start-ChildProcess 'cmd.exe' "/c npm run dev -- --host 127.0.0.1 --port $FrontendPort --strictPort" $frontendDir @{
        'VITE_USE_MOCK_API' = 'false'
        'VITE_API_BASE_URL' = "http://127.0.0.1:$BackendPort/api/v1"
    }

    Wait-HttpOk "http://127.0.0.1:$BackendPort/api/v1/health" 120
    Wait-HttpOk "http://127.0.0.1:$FrontendPort/" 45

    Write-Host "Executing Chromium Playwright E2E suite..." -ForegroundColor Green
    $env:E2E_FRONTEND_URL = "http://127.0.0.1:$FrontendPort"
    $env:E2E_BACKEND_URL = "http://127.0.0.1:$BackendPort"
    $env:E2E_ADMIN_USER = $adminUser
    $env:E2E_ADMIN_PASSWORD = $adminPass
    & $venvPython $e2eScript
    $exitCode = $LASTEXITCODE
}
finally {
    if ($bstr -ne [IntPtr]::Zero) {
        [System.Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
    }
    if ($null -ne $securePassword) {
        $securePassword.Dispose()
    }
    $plainPassword = $null
    Remove-Item Env:PGPASSWORD, Env:E2E_FRONTEND_URL, Env:E2E_BACKEND_URL, Env:E2E_ADMIN_USER, Env:E2E_ADMIN_PASSWORD -ErrorAction SilentlyContinue

    foreach ($p in $procs) {
        try {
            if ($p -and -not $p.HasExited) {
                taskkill /PID $p.Id /T /F | Out-Null
            }
        } catch {}
    }
}

exit $exitCode
