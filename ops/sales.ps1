[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = "Medium")]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern("^https://[^/]+/?$")]
    [string]$ApiUrl,

    [Parameter(Mandatory = $true)]
    [ValidatePattern("^https://")]
    [string]$Audience,

    [Parameter(Mandatory = $true)]
    [ValidatePattern("^[^@\s]+@[^@\s]+\.gserviceaccount\.com$")]
    [string]$ServiceAccount,

    [Parameter(Mandatory = $true)]
    [ValidateSet("Queue", "Funnel", "Update")]
    [string]$Command,

    [ValidateSet("NEW", "CONTACTED", "QUALIFIED", "PROPOSAL", "WON", "LOST")]
    [string]$Status,

    [guid]$LeadId,
    [guid]$OrganizationId,

    [ValidateLength(5, 500)]
    [string]$LostReason
)

$ErrorActionPreference = "Stop"
$baseUrl = $ApiUrl.TrimEnd("/")

$token = & gcloud auth print-identity-token `
    "--impersonate-service-account=$ServiceAccount" `
    "--audiences=$Audience" `
    --include-email 2>$null
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($token)) {
    throw "Could not obtain a Google identity token. Check gcloud login and service-account token-creator IAM."
}

$headers = @{
    Authorization   = "Bearer $($token.Trim())"
    Accept          = "application/json"
    "Cache-Control" = "no-store"
}

switch ($Command) {
    "Queue" {
        $uri = "$baseUrl/internal/sales/pilot-leads"
        if ($Status) {
            $uri += "?status=$([uri]::EscapeDataString($Status))"
        }
        $result = Invoke-RestMethod -Method Get -Uri $uri -Headers $headers
    }
    "Funnel" {
        if ($Status) {
            throw "Status does not apply to Funnel."
        }
        $result = Invoke-RestMethod -Method Get -Uri "$baseUrl/internal/sales/pilot-leads/funnel" -Headers $headers
    }
    "Update" {
        if ($LeadId -eq [guid]::Empty -or [string]::IsNullOrWhiteSpace($Status)) {
            throw "Update requires LeadId and Status."
        }
        if ($Status -eq "WON" -and $OrganizationId -eq [guid]::Empty) {
            throw "WON requires OrganizationId."
        }
        if ($Status -ne "WON" -and $OrganizationId -ne [guid]::Empty) {
            throw "OrganizationId only applies to WON."
        }
        if ($Status -eq "LOST" -and [string]::IsNullOrWhiteSpace($LostReason)) {
            throw "LOST requires LostReason."
        }
        if ($Status -ne "LOST" -and -not [string]::IsNullOrWhiteSpace($LostReason)) {
            throw "LostReason only applies to LOST."
        }
        $body = @{
            status         = $Status
            organizationId = if ($OrganizationId -eq [guid]::Empty) { $null } else { $OrganizationId.ToString() }
            lostReason     = if ([string]::IsNullOrWhiteSpace($LostReason)) { $null } else { $LostReason.Trim() }
        } | ConvertTo-Json -Compress
        if (-not $PSCmdlet.ShouldProcess($LeadId, "Move pilot lead to $Status")) {
            return
        }
        $result = Invoke-RestMethod -Method Patch `
            -Uri "$baseUrl/internal/sales/pilot-leads/$LeadId" `
            -Headers $headers -ContentType "application/json" -Body $body
    }
}

$result | ConvertTo-Json -Depth 8
