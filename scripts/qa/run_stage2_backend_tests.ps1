<#
.SYNOPSIS
    Interactive Stage 2 backend test launcher for SOMNISENSE (somnisense_test_db only).

.DESCRIPTION
    Prompts interactively for the PostgreSQL password using Read-Host -AsSecureString,
    verifies PostgreSQL connectivity and existence of somnisense_test_db (read-only check),
    runs `.\mvnw.cmd test` in backend\ with DB_PASSWORD passed only to the child process
    environment, clears temporary environment variables, and releases unmanaged memory in
    a finally block.

    Compatible with Windows PowerShell 5.1 and PowerShell 7+.
    Note: Immutable managed .NET System.String instances cannot be securely zeroized in place;
    exposure is minimized by passing credentials only to child process environments, never
    logging or passing them on command lines, and freeing the unmanaged BSTR and SecureString
    immediately in finally.
#>

[CmdletBinding()]
param(
    [string]$DbHost = 'localhost',
    [int]$DbPort = 5432,
    [string]$DbUser = 'postgres',
    [string]$TestDbName = 'somnisense_test_db'
)

$ErrorActionPreference = 'Stop'

if ($TestDbName -ne 'somnisense_test_db') {
    Write-Error "Refusing to run tests against non-test database '$TestDbName'. Expected 'somnisense_test_db'."
    exit 1
}

$scriptPath = $PSCommandPath
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$backendDir = Join-Path $repoRoot 'backend'
$mvnwPath = Join-Path $backendDir 'mvnw.cmd'

if (-not (Test-Path $mvnwPath)) {
    Write-Error "Maven wrapper not found at: $mvnwPath"
    exit 1
}

function Find-PsqlPath {
    $cmd = Get-Command psql -ErrorAction SilentlyContinue
    if ($cmd) {
        return $cmd.Source
    }
    $fallback = Get-ChildItem -Path 'C:\Program Files\PostgreSQL\*\bin\psql.exe' -ErrorAction SilentlyContinue |
        Sort-Object FullName -Descending |
        Select-Object -First 1
    if ($fallback) {
        return $fallback.FullName
    }
    return $null
}

function Format-ProcessArguments {
    param(
        [Parameter(Mandatory = $true)][string[]]$ArgumentList
    )
    $quoted = foreach ($arg in $ArgumentList) {
        if ($null -eq $arg -or $arg.Length -eq 0) {
            '""'
        } elseif ($arg -notmatch '[\s"]') {
            $arg
        } else {
            $escaped = $arg -replace '(\\*)"', '$1$1\"'
            $escaped = $escaped -replace '(\\+)$', '$1$1'
            '"' + $escaped + '"'
        }
    }
    return ($quoted -join ' ')
}

function Invoke-ChildProcess {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string[]]$ArgumentList,
        [Parameter(Mandatory = $true)][string]$WorkingDirectory,
        [hashtable]$ExtraEnv = @{},
        [switch]$CaptureOutput
    )

    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $FilePath
    $psi.Arguments = Format-ProcessArguments -ArgumentList $ArgumentList
    $psi.WorkingDirectory = $WorkingDirectory
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = [bool]$CaptureOutput
    $psi.RedirectStandardError = [bool]$CaptureOutput

    foreach ($key in $ExtraEnv.Keys) {
        $psi.EnvironmentVariables[$key] = $ExtraEnv[$key]
    }

    $proc = $null
    try {
        $proc = [System.Diagnostics.Process]::Start($psi)
        if ($null -eq $proc) {
            throw "Failed to start child process '$FilePath'."
        }

        $stdout = ''
        $stderr = ''
        if ($CaptureOutput) {
            $stderrTask = if ($null -ne $proc.StandardError) { $proc.StandardError.ReadToEndAsync() } else { $null }
            if ($null -ne $proc.StandardOutput) {
                $stdout = $proc.StandardOutput.ReadToEnd()
            }
            if ($null -ne $stderrTask) {
                $stderr = $stderrTask.GetAwaiter().GetResult()
            }
        }

        $proc.WaitForExit()
        return [pscustomobject]@{
            ExitCode = $proc.ExitCode
            StdOut   = if ($null -ne $stdout) { $stdout.Trim() } else { '' }
            StdErr   = if ($null -ne $stderr) { $stderr.Trim() } else { '' }
        }
    }
    finally {
        if ($null -ne $proc) {
            $proc.Dispose()
        }
    }
}

$psqlPath = Find-PsqlPath
if (-not $psqlPath) {
    Write-Error "psql.exe was not found on PATH or under C:\Program Files\PostgreSQL."
    exit 1
}

$securePassword = $null
$bstr = [IntPtr]::Zero
$plainPassword = $null
$mavenExitCode = 1

try {
    Write-Host "SOMNISENSE Stage 2 Backend Test Runner" -ForegroundColor Cyan
    Write-Host "Target test database: $TestDbName ($DbHost`:$DbPort, user: $DbUser)"
    $securePassword = Read-Host -Prompt "Enter PostgreSQL password for user '$DbUser'" -AsSecureString

    $bstr = [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
    $plainPassword = [System.Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)

    if ([string]::IsNullOrEmpty($plainPassword)) {
        throw "PostgreSQL password cannot be empty."
    }

    if ($DbUser -notmatch '^[A-Za-z_][A-Za-z0-9_]*$') {
        throw "Invalid PostgreSQL username '$DbUser'."
    }

    Write-Host "Verifying PostgreSQL connectivity against maintenance database 'postgres'..."
    $connResult = Invoke-ChildProcess -FilePath $psqlPath `
        -ArgumentList @('-X', '-h', $DbHost, '-p', $DbPort.ToString(), '-U', $DbUser, '-d', 'postgres', '-t', '-A', '-c', 'SELECT current_database();') `
        -WorkingDirectory $backendDir `
        -ExtraEnv @{ 'PGPASSWORD' = $plainPassword } `
        -CaptureOutput

    Write-Host ("  psql connectivity check -> exit={0}, stdout='{1}', stderr='{2}'" -f $connResult.ExitCode, $connResult.StdOut, $connResult.StdErr)
    if ($connResult.ExitCode -ne 0 -or $connResult.StdOut -ne 'postgres') {
        $detail = if ($connResult.StdErr) { $connResult.StdErr } else { 'No error details returned by psql.' }
        throw "PostgreSQL connection check failed (exit $($connResult.ExitCode)): $detail"
    }

    Write-Host "Checking catalog for dedicated test database '$TestDbName'..."
    $probeSql = "SELECT 1 FROM pg_database WHERE datname = '$TestDbName';"
    $probeArgs = @('-X', '-h', $DbHost, '-p', $DbPort.ToString(), '-U', $DbUser, '-d', 'postgres', '-t', '-A', '-c', $probeSql)

    $probeResult = Invoke-ChildProcess -FilePath $psqlPath `
        -ArgumentList $probeArgs `
        -WorkingDirectory $backendDir `
        -ExtraEnv @{ 'PGPASSWORD' = $plainPassword } `
        -CaptureOutput

    Write-Host ("  psql catalog check -> exit={0}, stdout='{1}', stderr='{2}'" -f $probeResult.ExitCode, $probeResult.StdOut, $probeResult.StdErr)
    if ($probeResult.ExitCode -ne 0) {
        $detail = if ($probeResult.StdErr) { $probeResult.StdErr } else { 'No error details returned by psql.' }
        throw "PostgreSQL catalog check failed (exit $($probeResult.ExitCode)): $detail"
    }

    if ($probeResult.StdOut -ne '1') {
        if ($TestDbName -ne 'somnisense_test_db') {
            throw "Refusing to create non-test database '$TestDbName'."
        }
        Write-Host "Dedicated test database '$TestDbName' does not exist. Creating '$TestDbName' owned by '$DbUser'..." -ForegroundColor Yellow
        $createSql = "CREATE DATABASE somnisense_test_db OWNER $DbUser;"
        $createResult = Invoke-ChildProcess -FilePath $psqlPath `
            -ArgumentList @('-X', '-h', $DbHost, '-p', $DbPort.ToString(), '-U', $DbUser, '-d', 'postgres', '-v', 'ON_ERROR_STOP=1', '-c', $createSql) `
            -WorkingDirectory $backendDir `
            -ExtraEnv @{ 'PGPASSWORD' = $plainPassword } `
            -CaptureOutput

        Write-Host ("  psql create database -> exit={0}, stdout='{1}', stderr='{2}'" -f $createResult.ExitCode, $createResult.StdOut, $createResult.StdErr)
        if ($createResult.ExitCode -ne 0) {
            $detail = if ($createResult.StdErr) { $createResult.StdErr } else { 'No error details returned by psql.' }
            throw "Failed to create '$TestDbName' (exit $($createResult.ExitCode)): $detail"
        }
        Write-Host "Created dedicated test database '$TestDbName' (somnisense_db untouched)." -ForegroundColor Green
    } else {
        Write-Host "Dedicated test database '$TestDbName' already exists." -ForegroundColor Green
    }

    $verifyResult = Invoke-ChildProcess -FilePath $psqlPath `
        -ArgumentList @('-X', '-h', $DbHost, '-p', $DbPort.ToString(), '-U', $DbUser, '-d', $TestDbName, '-t', '-A', '-c', 'SELECT current_database();') `
        -WorkingDirectory $backendDir `
        -ExtraEnv @{ 'PGPASSWORD' = $plainPassword } `
        -CaptureOutput

    Write-Host ("  psql target DB check -> exit={0}, stdout='{1}', stderr='{2}'" -f $verifyResult.ExitCode, $verifyResult.StdOut, $verifyResult.StdErr)
    if ($verifyResult.ExitCode -ne 0) {
        $detail = if ($verifyResult.StdErr) { $verifyResult.StdErr } else { 'No error details returned by psql.' }
        throw "Unable to connect to '$TestDbName' (exit $($verifyResult.ExitCode)): $detail"
    }
    if ($verifyResult.StdOut -ne $TestDbName) {
        throw "Connected database mismatch: expected '$TestDbName', got '$($verifyResult.StdOut)'."
    }

    Write-Host "PostgreSQL and '$TestDbName' verified. Running Maven test suite..." -ForegroundColor Green
    $testDbUrl = "jdbc:postgresql://$DbHost`:$DbPort/$TestDbName"
    $mavenResult = Invoke-ChildProcess -FilePath 'cmd.exe' `
        -ArgumentList @('/c', 'mvnw.cmd', 'test') `
        -WorkingDirectory $backendDir `
        -ExtraEnv @{
            'DB_PASSWORD' = $plainPassword
            'DB_USERNAME' = $DbUser
            'TEST_DB_URL' = $testDbUrl
        }

    $mavenExitCode = $mavenResult.ExitCode

    $surefireDir = Join-Path $backendDir 'target\surefire-reports'
    if (Test-Path $surefireDir) {
        $xmlFiles = Get-ChildItem -Path $surefireDir -Filter 'TEST-*.xml' -ErrorAction SilentlyContinue
        if ($xmlFiles) {
            $total = 0; $failures = 0; $errors = 0; $skipped = 0
            foreach ($f in $xmlFiles) {
                [xml]$doc = Get-Content $f.FullName
                $ts = $doc.testsuite
                $total += [int]$ts.tests
                $failures += [int]$ts.failures
                $errors += [int]$ts.errors
                $skipped += [int]$ts.skipped
            }
            $passed = $total - $failures - $errors - $skipped
            Write-Host ""
            Write-Host ("Surefire summary: Total={0}, Passed={1}, Failures={2}, Errors={3}, Skipped={4}" -f $total, $passed, $failures, $errors, $skipped) -ForegroundColor Cyan
        }
    }

    Write-Host ""
    Write-Host "Final Maven exit code: $mavenExitCode" -ForegroundColor $(if ($mavenExitCode -eq 0) { 'Green' } else { 'Red' })
}
catch {
    $errRecord = $_
    $lineNum = if ($errRecord.InvocationInfo) { $errRecord.InvocationInfo.ScriptLineNumber } else { 'unknown' }
    $failingCmd = if ($errRecord.InvocationInfo -and $errRecord.InvocationInfo.Line) { $errRecord.InvocationInfo.Line.Trim() } else { 'unknown' }
    Write-Host ""
    Write-Host "ERROR in $scriptPath at line $lineNum" -ForegroundColor Red
    Write-Host "Failing command : $failingCmd" -ForegroundColor Red
    Write-Host "Message         : $($errRecord.Exception.Message)" -ForegroundColor Red
    if ($errRecord.ScriptStackTrace) {
        Write-Host "Stack trace     :" -ForegroundColor Red
        Write-Host $errRecord.ScriptStackTrace -ForegroundColor Red
    }
    $mavenExitCode = 1
}
finally {
    if ($bstr -ne [IntPtr]::Zero) {
        [System.Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
        $bstr = [IntPtr]::Zero
    }
    if ($null -ne $securePassword) {
        $securePassword.Dispose()
        $securePassword = $null
    }
    $plainPassword = $null
    Remove-Item Env:DB_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:DB_USERNAME -ErrorAction SilentlyContinue
    Remove-Item Env:TEST_DB_URL -ErrorAction SilentlyContinue
}

exit $mavenExitCode
