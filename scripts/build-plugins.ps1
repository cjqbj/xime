# Build all Lua plugin xipk packages.
#
# Usage:
#   .\scripts\build-plugins.ps1
#
# Output: build/plugin-release/*.xipk
#
# Lua plugins are plain file directories (plugins/<name>/ containing
# manifest.yaml), no Gradle build needed. Each directory is zipped
# into a .xipk, mirroring scripts/build-plugins.sh.

$ErrorActionPreference = "Stop"

# Compress-Archive / ZipFile.CreateFromDirectory 在 Windows 上会生成反斜杠
# 路径分隔符，而 Android 侧 ZipFile 按 "/" 解析，导致 resources/ 资源错乱。
# 这里手工构造 zip，条目名统一用 "/"。
function Write-Xipk {
    param(
        [Parameter(Mandatory = $true)][string]$SourceDir,
        [Parameter(Mandatory = $true)][string]$DestFile
    )
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $fs = [System.IO.File]::Open($DestFile, [System.IO.FileMode]::CreateNew)
    $archive = New-Object System.IO.Compression.ZipArchive($fs, [System.IO.Compression.ZipArchiveMode]::Create)
    try {
        $base = (Resolve-Path $SourceDir).Path.TrimEnd('\')
        Get-ChildItem -Path $SourceDir -Recurse -File -Force | ForEach-Object {
            if ($_.Name.StartsWith('.') -or $_.FullName.Substring($base.Length + 1).StartsWith('.')) { return }
            $rel = $_.FullName.Substring($base.Length + 1).Replace('\', '/')
            $entry = $archive.CreateEntry($rel, [System.IO.Compression.CompressionLevel]::Optimal)
            $entryStream = $entry.Open()
            try {
                $bytes = [System.IO.File]::ReadAllBytes($_.FullName)
                $entryStream.Write($bytes, 0, $bytes.Length)
            } finally {
                $entryStream.Dispose()
            }
        }
    } finally {
        $archive.Dispose()
        $fs.Dispose()
    }
}

$PROJECT_DIR = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$OUTPUT_DIR = Join-Path $PROJECT_DIR "build\plugin-release"
$PLUGINS_DIR = Join-Path $PROJECT_DIR "plugins"

Write-Host "=== Building Lua plugin xipk packages ==="
Write-Host "Output dir: $OUTPUT_DIR"

New-Item -ItemType Directory -Force -Path $OUTPUT_DIR | Out-Null

foreach ($pluginDir in Get-ChildItem -Path $PLUGINS_DIR -Directory) {
    $manifest = Join-Path $pluginDir.FullName "manifest.yaml"
    if (-not (Test-Path $manifest)) { continue }

    $name = $pluginDir.Name
    $version = Get-Content -Path $manifest |
        Where-Object { $_ -match '^\s*version:' } |
        Select-Object -First 1 |
        ForEach-Object { ($_ -replace '^\s*version:\s*', '').Trim('"', "'") }
    if (-not $version) { $version = "0.0.0" }

    # 清理该插件旧版本产物，避免残留（版本升级后旧 xipk 不再保留）
    Get-ChildItem -Path $OUTPUT_DIR -Filter "$name-*.xipk" -File -ErrorAction SilentlyContinue |
        Remove-Item -Force
    Get-ChildItem -Path $OUTPUT_DIR -Filter "$name-*.zip" -File -ErrorAction SilentlyContinue |
        Remove-Item -Force

    $out = Join-Path $OUTPUT_DIR "$name-$version.xipk"
    Remove-Item -Path $out -Force -ErrorAction SilentlyContinue

    $hasEntries = Get-ChildItem -Path $pluginDir.FullName -Force |
        Where-Object { -not $_.Name.StartsWith('.') } |
        Select-Object -First 1
    if (-not $hasEntries) { continue }

    Write-Xipk -SourceDir $pluginDir.FullName -DestFile $out
    Write-Host "Lua : $name-$version.xipk"
}

Write-Host ""
Write-Host "=== Done ==="
Get-ChildItem -Path $OUTPUT_DIR -Filter "*.xipk" -File |
    Sort-Object Name |
    ForEach-Object { "{0,10:N1} K  {1}" -f ($_.Length / 1KB), $_.Name }
