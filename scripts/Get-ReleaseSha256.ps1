param(
    [Parameter(Mandatory = $true)]
    [string]$Path
)

$ErrorActionPreference = 'Stop'
$file = (Resolve-Path -LiteralPath $Path).ProviderPath
$stream = [System.IO.File]::OpenRead($file)
try {
    $sha256 = [System.Security.Cryptography.SHA256]::Create()
    try {
        [System.BitConverter]::ToString($sha256.ComputeHash($stream)).Replace('-', '').ToLowerInvariant()
    } finally {
        $sha256.Dispose()
    }
} finally {
    $stream.Dispose()
}
