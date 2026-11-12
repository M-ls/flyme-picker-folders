#requires -Version 7.0
$ErrorActionPreference = 'Stop'
$project = $PSScriptRoot
$dependencies = Join-Path $project 'build/dependencies'
New-Item -ItemType Directory -Force -Path $dependencies | Out-Null

foreach ($entry in Get-Content -LiteralPath (Join-Path $project 'dependencies.lock')) {
    $entry = $entry.Trim()
    if ($entry.Length -eq 0 -or $entry.StartsWith('#')) {
        continue
    }

    $name, $expectedHash, $sources = $entry -split '\s+'
    $target = Join-Path $dependencies $name
    if ((Test-Path -LiteralPath $target) -and
            (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash -eq $expectedHash) {
        continue
    }

    $download = Join-Path $dependencies ([System.IO.Path]::GetRandomFileName())
    $verified = $false
    foreach ($source in $sources) {
        try {
            Write-Host "Downloading $name from $source"
            Invoke-WebRequest -Uri $source -OutFile $download -TimeoutSec 120
            if ((Get-FileHash -LiteralPath $download -Algorithm SHA256).Hash -ne $expectedHash) {
                throw "SHA-256 mismatch for $name"
            }
            Move-Item -LiteralPath $download -Destination $target -Force
            $verified = $true
            break
        } catch {
            Write-Warning $_.Exception.Message
        } finally {
            if (Test-Path -LiteralPath $download) {
                Remove-Item -LiteralPath $download -Force
            }
        }
    }
    if (-not $verified) {
        throw "Unable to download and verify $name"
    }
}

$mount = "type=bind,source=$project,target=/project"
docker run --rm --platform linux/amd64 --mount $mount `
    m.daocloud.io/docker.io/library/maven:3.9-eclipse-temurin-8 sh /project/build.sh compile
if ($LASTEXITCODE -ne 0) {
    throw 'Module compilation failed'
}

docker run --rm --platform linux/amd64 --mount $mount --env FLYME_PICKER_KEY_PASSWORD `
    m.daocloud.io/docker.io/eclipse-temurin:21-jre sh /project/build.sh package
if ($LASTEXITCODE -ne 0) {
    throw 'Module packaging or signature verification failed'
}

Write-Host (Join-Path $project 'build/flymepicker.apk')
