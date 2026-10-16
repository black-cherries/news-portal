<#
.SYNOPSIS
  Creates the local development and test databases and their owner roles,
  then writes the generated credentials to the git-ignored .env file.

.DESCRIPTION
  Safe to re-run: existing roles/databases are left alone, and an existing
  .env is never overwritten. The PostgreSQL superuser password is read from a
  file outside the repository and is not stored in .env.

.EXAMPLE
  .\scripts\db\setup-local-db.ps1
#>
param(
    [string]$PsqlPath = 'D:\PostgreSQL\17\bin\psql.exe',
    [string]$SuperuserPasswordFile = 'D:\dev-secrets\pg-superuser.txt',
    [string]$DbHost = '127.0.0.1',
    [int]$Port = 5432
)

$ErrorActionPreference = 'Stop'
$repoRoot = Resolve-Path (Join-Path $PSScriptRoot '..\..')
$envFile = Join-Path $repoRoot '.env'

if (-not (Test-Path $PsqlPath)) { throw "psql not found at $PsqlPath" }
if (-not (Test-Path $SuperuserPasswordFile)) { throw "Superuser password file not found: $SuperuserPasswordFile" }

function New-Secret {
    $chars = [char[]]'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789'
    $bytes = New-Object byte[] 24
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    -join ($bytes | ForEach-Object { $chars[$_ % $chars.Length] })
}

function Invoke-Psql([string]$Sql, [string]$Database = 'postgres') {
    $out = & $PsqlPath -h $DbHost -p $Port -U postgres -d $Database -v ON_ERROR_STOP=1 -tAc $Sql
    if ($LASTEXITCODE -ne 0) { throw "psql failed: $Sql" }
    $out
}

$env:PGPASSWORD = (Get-Content $SuperuserPasswordFile -Raw).Trim()
try {
    $databases = @(
        @{ Name = 'newsportal_dev';  Prefix = 'DB';      RestoreKey = 'RESTORE_TEST_DB_URL' },
        @{ Name = 'newsportal_test'; Prefix = 'TEST_DB'; RestoreKey = 'TEST_RESTORE_DB_URL' }
    )
    $envLines = @("PG_BIN_DIR=$((Split-Path $PsqlPath -Parent) -replace '\\','/')")

    foreach ($db in $databases) {
        $name = $db.Name
        $password = New-Secret

        if (Invoke-Psql "SELECT 1 FROM pg_roles WHERE rolname = '$name'") {
            Write-Host "Role $name exists; resetting its password."
            Invoke-Psql "ALTER ROLE $name WITH LOGIN PASSWORD '$password'" | Out-Null
        } else {
            Invoke-Psql "CREATE ROLE $name WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD '$password'" | Out-Null
            Write-Host "Created role $name."
        }

        if (Invoke-Psql "SELECT 1 FROM pg_database WHERE datname = '$name'") {
            Write-Host "Database $name exists."
        } else {
            Invoke-Psql "CREATE DATABASE $name OWNER $name ENCODING 'UTF8' TEMPLATE template0" | Out-Null
            Invoke-Psql "REVOKE ALL ON DATABASE $name FROM PUBLIC" | Out-Null
            Write-Host "Created database $name."
        }
        # Flyway runs as the owner role, so it must own the public schema.
        Invoke-Psql "ALTER SCHEMA public OWNER TO $name" $name | Out-Null

        # Scratch database for restore tests (docs/operations.md), owned by the same role.
        $restore = "${name}_restore"
        if (-not (Invoke-Psql "SELECT 1 FROM pg_database WHERE datname = '$restore'")) {
            Invoke-Psql "CREATE DATABASE $restore OWNER $name ENCODING 'UTF8' TEMPLATE template0" | Out-Null
            Invoke-Psql "REVOKE ALL ON DATABASE $restore FROM PUBLIC" | Out-Null
            Write-Host "Created database $restore."
        }
        Invoke-Psql "ALTER SCHEMA public OWNER TO $name" $restore | Out-Null

        $envLines += "$($db.RestoreKey)=jdbc:postgresql://${DbHost}:$Port/$restore"
        $envLines += "$($db.Prefix)_URL=jdbc:postgresql://${DbHost}:$Port/$name"
        $envLines += "$($db.Prefix)_USERNAME=$name"
        $envLines += "$($db.Prefix)_PASSWORD=$password"
    }

    if (Test-Path $envFile) {
        $pending = Join-Path $repoRoot '.env.generated'
        Set-Content -Path $pending -Value $envLines -Encoding ascii
        Write-Warning ".env already exists and was not changed. Role passwords were reset; copy the new values from $pending into .env, then delete $pending."
    } else {
        Set-Content -Path $envFile -Value $envLines -Encoding ascii
        Write-Host "Wrote credentials to $envFile"
    }
} finally {
    Remove-Item Env:\PGPASSWORD -ErrorAction SilentlyContinue
}
