param(
    [Parameter(Mandatory = $true)][string]$KeyFile,
    [Parameter(Mandatory = $true)][string]$CertFile,
    [Parameter(Mandatory = $true)][string]$SdkRoot,
    [string]$InputApk = (Join-Path $PSScriptRoot '../app/build/outputs/apk/debug/app-debug.apk'),
    [string]$OutputApk = (Join-Path $PSScriptRoot '../app/build/outputs/apk/debug/app-debug-signed.apk')
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

try {
    $repoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
    $keyPath = [System.IO.Path]::GetFullPath($KeyFile)
    $certPath = [System.IO.Path]::GetFullPath($CertFile)
    $inputPath = [System.IO.Path]::GetFullPath($InputApk)
    $outputPath = [System.IO.Path]::GetFullPath($OutputApk)
    $comparison = if ($IsWindows) { [StringComparison]::OrdinalIgnoreCase } else { [StringComparison]::Ordinal }
    $repoPrefix = $repoRoot.TrimEnd([System.IO.Path]::DirectorySeparatorChar, [System.IO.Path]::AltDirectorySeparatorChar) + [System.IO.Path]::DirectorySeparatorChar
    if ($keyPath.StartsWith($repoPrefix, $comparison)) { throw 'KeyFile must be outside the repository.' }
    if ([string]::Equals($outputPath, $inputPath, $comparison)) { throw 'OutputApk must differ from InputApk.' }
    if ([string]::Equals($outputPath, $keyPath, $comparison) -or [string]::Equals($outputPath, $certPath, $comparison)) { throw 'OutputApk must not overwrite signing material.' }

    $jarPath = Join-Path $SdkRoot 'build-tools/35.0.0/lib/apksigner.jar'
    $fingerprintPath = Join-Path $repoRoot 'signing-certificate.sha256'
    foreach ($path in @($keyPath, $certPath, $inputPath, $jarPath, $fingerprintPath)) {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Required file not found: $path" }
    }
    $java = Get-Command java -CommandType Application -ErrorAction Stop | Select-Object -First 1
    $expected = (Get-Content -LiteralPath $fingerprintPath -Raw).Trim().ToLowerInvariant()
    if ($expected -notmatch '^[0-9a-f]{64}$') { throw 'Invalid expected certificate fingerprint.' }

    $outputDirectory = [System.IO.Path]::GetDirectoryName($outputPath)
    if (-not (Test-Path -LiteralPath $outputDirectory -PathType Container)) {
        New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
    }
    # Close stdin after an empty line so an unsupported encrypted key cannot prompt indefinitely.
    '' | & $java.Source -jar $jarPath sign --key $keyPath --cert $certPath --out $outputPath $inputPath
    if ($LASTEXITCODE -ne 0) { throw "apksigner sign failed (exit $LASTEXITCODE)." }
    $verification = & $java.Source -jar $jarPath verify --print-certs $outputPath
    if ($LASTEXITCODE -ne 0) { throw "apksigner verify failed (exit $LASTEXITCODE)." }
    $hashes = @($verification | ForEach-Object {
        if ($_ -match '^Signer #\d+ certificate SHA-256 digest:\s*([0-9a-fA-F:]+)\s*$') {
            $Matches[1].Replace(':', '').ToLowerInvariant()
        }
    })
    if ($hashes.Count -ne 1 -or $hashes[0] -notmatch '^[0-9a-f]{64}$') { throw 'Expected exactly one parsed signer SHA-256 fingerprint.' }
    if ($hashes[0] -ne $expected) { throw 'Signer certificate fingerprint does not match signing-certificate.sha256.' }
    Write-Output "Signed APK: $outputPath"
    Write-Output "Signer certificate SHA-256: $($hashes[0])"
} catch {
    Write-Error $_
    exit 1
}
