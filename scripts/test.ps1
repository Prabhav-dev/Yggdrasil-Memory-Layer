$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$javaRoot = Join-Path $projectRoot "java-storage"
$outputDirectory = Join-Path $javaRoot "out"

Push-Location $projectRoot
try {
    cargo test --workspace
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    New-Item -ItemType Directory -Force $outputDirectory | Out-Null
    $sources = Get-ChildItem (Join-Path $javaRoot "src\main\java"), (Join-Path $javaRoot "src\test\java") -Recurse -Filter *.java | ForEach-Object FullName
    & javac -encoding UTF-8 -d $outputDirectory $sources
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    Push-Location $javaRoot
    try {
        & java -cp out Yggdrasil.TcpIntegrationTest
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    }
    finally {
        Pop-Location
    }
}
finally {
    Pop-Location
}