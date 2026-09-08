param(
    [Parameter(Mandatory = $true, Position = 0)]
    [ValidateSet('db-up', 'db-down', 'test', 'test-e2e', 'test-e2e-full', 'build', 'smoke')]
    [string]$Task
)

$ErrorActionPreference = 'Stop'
$Workspace = $PSScriptRoot

function Invoke-BackendVerify {
    Push-Location (Join-Path $Workspace 'backend')
    try {
        & .\mvnw.cmd --batch-mode --no-transfer-progress verify
        if ($LASTEXITCODE -ne 0) { throw 'Backend verification failed.' }
    }
    finally { Pop-Location }
}

function Invoke-FrontendQuality {
    Push-Location (Join-Path $Workspace 'frontend')
    try {
        & npm.cmd run format:check
        if ($LASTEXITCODE -ne 0) { throw 'Frontend formatting check failed.' }
        & npm.cmd run lint
        if ($LASTEXITCODE -ne 0) { throw 'Frontend lint failed.' }
        & npm.cmd run typecheck
        if ($LASTEXITCODE -ne 0) { throw 'Frontend type-check failed.' }
        & npm.cmd test
        if ($LASTEXITCODE -ne 0) { throw 'Frontend tests failed.' }
    }
    finally { Pop-Location }
}

switch ($Task) {
    'test-e2e-full' {
        Push-Location (Join-Path $Workspace 'backend')
        try {
            & .\mvnw.cmd --batch-mode --no-transfer-progress -Pbrowser-e2e test-compile failsafe:integration-test failsafe:verify
            if ($LASTEXITCODE -ne 0) { throw 'Authenticated browser journey failed; see backend/target/browser-journey.log.' }
        }
        finally { Pop-Location }
    }
    'test-e2e' {
        Push-Location (Join-Path $Workspace 'frontend')
        try {
            & npm.cmd run test:e2e
            if ($LASTEXITCODE -ne 0) { throw 'Browser smoke verification failed.' }
        }
        finally { Pop-Location }
    }
    'db-up' {
        docker compose --project-directory $Workspace up -d postgres
        if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL failed to start.' }
    }
    'db-down' {
        docker compose --project-directory $Workspace down
        if ($LASTEXITCODE -ne 0) { throw 'Local stack failed to stop.' }
    }
    'test' {
        Invoke-BackendVerify
        Invoke-FrontendQuality
    }
    'build' {
        Invoke-BackendVerify
        Invoke-FrontendQuality
        Push-Location (Join-Path $Workspace 'frontend')
        try {
            & npm.cmd run build
            if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed.' }
        }
        finally { Pop-Location }
    }
    'smoke' {
        docker compose --project-directory $Workspace exec -T postgres pg_isready -U vid2knowledge -d vid2knowledge
        if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL is not ready.' }
    }
}
