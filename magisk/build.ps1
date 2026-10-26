$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$source = Join-Path $PSScriptRoot 'tubing-app-trigger'
$dist = Join-Path $projectRoot 'dist'
New-Item -ItemType Directory -Force -Path $dist | Out-Null
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$moduleVersion = ((Get-Content -LiteralPath (Join-Path $source 'module.prop') | Where-Object { $_ -match '^version=' }) -replace '^version=', '').Trim()
if ($moduleVersion -notmatch '^\d+\.\d+\.\d+$') { throw 'Invalid module version' }
$output = Join-Path $dist "tubing-app-trigger-v$moduleVersion.zip"
$stream = [System.IO.File]::Open($output, [System.IO.FileMode]::Create)
$archive = New-Object System.IO.Compression.ZipArchive($stream, [System.IO.Compression.ZipArchiveMode]::Create)
try {
    Get-ChildItem -LiteralPath $source -File | Sort-Object Name | ForEach-Object {
        $entry = $archive.CreateEntry($_.Name)
        $writer = New-Object System.IO.StreamWriter($entry.Open(), (New-Object System.Text.UTF8Encoding($false)))
        try { $writer.Write(([System.IO.File]::ReadAllText($_.FullName) -replace "`r`n", "`n")) }
        finally { $writer.Dispose() }
    }
} finally { $archive.Dispose(); $stream.Dispose() }
Write-Output $output
