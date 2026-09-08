[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = "High")]
param(
    [Parameter(Mandatory = $true)]
    [ValidateScript({ Test-Path -LiteralPath $_ -PathType Leaf })]
    [string]$EncryptedBackup,

    [Parameter(Mandatory = $true)]
    [ValidateScript({ Test-Path -LiteralPath $_ -PathType Leaf })]
    [string]$AgeIdentityFile,

    [Parameter(Mandatory = $true)]
    [ValidateSet("isolated-restore-drill")]
    [string]$Environment,

    [Parameter(Mandatory = $true)]
    [string]$EvidenceDirectory,

    [ValidateRange(1, 9999)]
    [int]$ExpectedMigration = 34
)

$ErrorActionPreference = "Stop"
foreach ($name in "PGHOST", "PGPORT", "PGDATABASE", "PGUSER", "PGPASSWORD") {
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name))) {
        throw "$name must be supplied through the process environment, never as a command-line argument."
    }
}
foreach ($tool in "psql", "pg_restore", "age") {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) { throw "$tool is required." }
}

$publicTables = (& psql --no-psqlrc --tuples-only --no-align --set ON_ERROR_STOP=1 `
    --command "SELECT count(*) FROM pg_catalog.pg_tables WHERE schemaname = 'public';").Trim()
if ($LASTEXITCODE -ne 0) { throw "Could not inspect the restore target." }
if ($publicTables -ne "0") {
    throw "Restore target is not empty ($publicTables public tables). Create a new isolated database."
}
if (-not $PSCmdlet.ShouldProcess("$env:PGHOST/$env:PGDATABASE", "Restore encrypted production backup")) {
    return
}

$started = Get-Date
$tempDump = Join-Path ([IO.Path]::GetTempPath()) "v2k-restore-$([guid]::NewGuid().ToString('N')).dump"
try {
    & age --decrypt --identity $AgeIdentityFile --output $tempDump $EncryptedBackup
    if ($LASTEXITCODE -ne 0) { throw "Backup decryption failed." }
    & pg_restore --list $tempDump | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Backup archive validation failed." }
    & pg_restore --exit-on-error --no-owner --no-privileges --dbname $env:PGDATABASE $tempDump
    if ($LASTEXITCODE -ne 0) { throw "Database restore failed." }

    $latestMigration = (& psql --no-psqlrc --tuples-only --no-align --set ON_ERROR_STOP=1 `
        --command "SELECT max(version::integer) FROM flyway_schema_history WHERE success;").Trim()
    if ($LASTEXITCODE -ne 0 -or $latestMigration -ne "$ExpectedMigration") {
        throw "Restored schema version $latestMigration does not equal expected V$ExpectedMigration."
    }
    $countsJson = (& psql --no-psqlrc --tuples-only --no-align --set ON_ERROR_STOP=1 --command `
        "SELECT json_build_object('organizations',(SELECT count(*) FROM organizations),'payments',(SELECT count(*) FROM payments),'packages',(SELECT count(*) FROM learning_packages));").Trim()
    if ($LASTEXITCODE -ne 0) { throw "Restore smoke queries failed." }

    $evidenceRoot = [IO.Path]::GetFullPath($EvidenceDirectory)
    [IO.Directory]::CreateDirectory($evidenceRoot) | Out-Null
    $evidence = [ordered]@{
        drillCompletedAt = (Get-Date).ToUniversalTime().ToString("o")
        environment      = $Environment
        backupSha256     = (Get-FileHash -Algorithm SHA256 -LiteralPath $EncryptedBackup).Hash.ToLowerInvariant()
        migration        = "V$latestMigration"
        counts           = $countsJson | ConvertFrom-Json
        durationSeconds  = [math]::Round(((Get-Date) - $started).TotalSeconds, 2)
    }
    $evidencePath = Join-Path $evidenceRoot "restore-drill-$((Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ')).json"
    $evidence | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $evidencePath -Encoding utf8NoBOM
    $evidence | ConvertTo-Json -Depth 4
}
finally {
    if (Test-Path -LiteralPath $tempDump) {
        Remove-Item -LiteralPath $tempDump -Force
    }
}
