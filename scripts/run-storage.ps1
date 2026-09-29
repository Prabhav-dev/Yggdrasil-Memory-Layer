param(
    [int]$Port = 8765,
    [string]$DataDirectory = ".\data"
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$javaRoot = Join-Path $projectRoot "java-storage"
$outputDirectory = Join-Path $javaRoot "out"

if (-not $env:YGGDRASIL_ROOT_KEY_HEX -or $env:YGGDRASIL_ROOT_KEY_HEX -notmatch "^(?i)[0-9a-f]{32}$") {
    throw "Set YGGDRASIL_ROOT_KEY_HEX to 32 hexadecimal characters before starting storage."
}

if (-not [System.IO.Path]::IsPathRooted($DataDirectory)) {
    $DataDirectory = Join-Path $projectRoot $DataDirectory
}

New-Item -ItemType Directory -Force $outputDirectory | Out-Null
New-Item -ItemType Directory -Force $DataDirectory | Out-Null
$sources = Get-ChildItem (Join-Path $javaRoot "src\main\java") -Recurse -Filter *.java | ForEach-Object FullName
& javac -encoding UTF-8 -d $outputDirectory $sources
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

& java -cp $outputDirectory Yggdrasil.MemoryStorageServer $Port $DataDirectory
exit $LASTEXITCODE