param([switch]$Check)

$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
$nodeCandidates = @(
    (Get-Command node -All -ErrorAction SilentlyContinue | ForEach-Object { $_.Source })
    (Join-Path $env:USERPROFILE '.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe')
)
if ($env:NVM_HOME -and (Test-Path -LiteralPath $env:NVM_HOME)) {
    $nodeCandidates += Get-ChildItem -LiteralPath $env:NVM_HOME -Directory |
        Where-Object { $_.Name -match '^v(?:22|24)\.' } |
        Sort-Object Name -Descending |
        ForEach-Object { Join-Path $_.FullName 'node.exe' }
}

$selectedNode = $null
foreach ($candidate in ($nodeCandidates | Select-Object -Unique)) {
    if (-not $candidate -or -not (Test-Path -LiteralPath $candidate)) { continue }
    $nodeVersion = (& $candidate --version).TrimStart('v')
    if ($LASTEXITCODE -ne 0) { continue }
    $parsedVersion = [version]$nodeVersion
    if ($parsedVersion.Major -eq 24 -or ($parsedVersion.Major -eq 22 -and $parsedVersion -ge [version]'22.18.0')) {
        $selectedNode = $candidate
        break
    }
}
if (-not $selectedNode) { throw 'Node.js 22.18+ or 24 is required. Install Node.js 24 and run this script again.' }

$originalPath = $env:Path
try {
    $env:Path = (Split-Path -Parent $selectedNode) + ';' + $originalPath
    Write-Host "Using Node.js $(& $selectedNode --version) ($selectedNode)"
    if (-not (Get-Command pnpm -ErrorAction SilentlyContinue)) { throw 'pnpm 11 is required. Install pnpm and run this script again.' }
    if ($Check) { exit 0 }
    Push-Location -LiteralPath $projectRoot
    try {
        if (-not (Test-Path -LiteralPath (Join-Path $projectRoot 'node_modules/.modules.yaml'))) {
            & pnpm install
            if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
        }
        & pnpm dev
        exit $LASTEXITCODE
    } finally { Pop-Location }
} finally { $env:Path = $originalPath }
