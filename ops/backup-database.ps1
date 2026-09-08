[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$OutputDirectory,

    [Parameter(Mandatory = $true)]
    [ValidatePattern("^age1[0-9a-z]+$")]
    [string]$AgeRecipient,

    [ValidatePattern("^https://[^/]+/?$")]
    [string]$R2Endpoint,
    [ValidatePattern("^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$")]
    [string]$R2Bucket
)

$ErrorActionPreference = "Stop"
foreach ($name in "PGHOST", "PGPORT", "PGDATABASE", "PGUSER", "PGPASSWORD") {
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name))) {
        throw "$name must be supplied through the process environment, never as a command-line argument."
    }
}
foreach ($tool in "pg_dump", "age") {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) { throw "$tool is required." }
}
if ([bool]$R2Endpoint -ne [bool]$R2Bucket) {
    throw "R2Endpoint and R2Bucket must be supplied together."
}
if ($R2Bucket -and -not (Get-Command aws -ErrorAction SilentlyContinue)) {
    throw "aws CLI is required for R2 upload."
}

$outputRoot = [IO.Path]::GetFullPath($OutputDirectory)
[IO.Directory]::CreateDirectory($outputRoot) | Out-Null
$stamp = (Get-Date).ToUniversalTime().ToString("yyyyMMddTHHmmssZ")
$baseName = "vid2knowledge-$stamp-$([guid]::NewGuid().ToString('N').Substring(0, 8))"
$tempDump = Join-Path ([IO.Path]::GetTempPath()) "$baseName.dump"
$encrypted = Join-Path $outputRoot "$baseName.dump.age"
$manifestPath = Join-Path $outputRoot "$baseName.manifest.json"

try {
    & pg_dump --format=custom --compress=9 --no-owner --no-privileges "--file=$tempDump"
    if ($LASTEXITCODE -ne 0) { throw "pg_dump failed with exit code $LASTEXITCODE." }
    & age --recipient $AgeRecipient --output $encrypted $tempDump
    if ($LASTEXITCODE -ne 0) { throw "age encryption failed with exit code $LASTEXITCODE." }

    $manifest = [ordered]@{
        format          = "postgres-custom-age-v1"
        createdAt       = (Get-Date).ToUniversalTime().ToString("o")
        database        = $env:PGDATABASE
        encryptedFile   = [IO.Path]::GetFileName($encrypted)
        encryptedBytes  = (Get-Item -LiteralPath $encrypted).Length
        sha256          = (Get-FileHash -Algorithm SHA256 -LiteralPath $encrypted).Hash.ToLowerInvariant()
        uploadedToR2    = [bool]$R2Bucket
    }
    $manifest | ConvertTo-Json | Set-Content -LiteralPath $manifestPath -Encoding utf8NoBOM

    if ($R2Bucket) {
        $endpoint = $R2Endpoint.TrimEnd("/")
        & aws s3 cp $encrypted "s3://$R2Bucket/database-backups/$([IO.Path]::GetFileName($encrypted))" `
            --endpoint-url $endpoint --only-show-errors
        if ($LASTEXITCODE -ne 0) { throw "Encrypted backup upload failed." }
        & aws s3 cp $manifestPath "s3://$R2Bucket/database-backups/$([IO.Path]::GetFileName($manifestPath))" `
            --endpoint-url $endpoint --only-show-errors
        if ($LASTEXITCODE -ne 0) { throw "Backup manifest upload failed." }
    }
    $manifest | ConvertTo-Json
}
finally {
    if (Test-Path -LiteralPath $tempDump) {
        Remove-Item -LiteralPath $tempDump -Force
    }
}
