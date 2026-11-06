param(
    [Parameter(Mandatory = $true)][string]$Python,
    [string]$CheckoutDirectory
)
$ErrorActionPreference = 'Stop'
if (-not $CheckoutDirectory) {
    $CheckoutDirectory = Join-Path $PSScriptRoot '../../.tools/ub-svelte'
}
$revisions = [ordered]@{
    crapper = '6fd92c4c9bcc56efb64a2f8743d5a2993bcfd42d'
    dryer = '66ff6d21a42c04afcad89c78a80066176d1294b0'
    mutator = 'a5a0cb803b66e0cdb8bf471b3b08af701d424a41'
}
New-Item -ItemType Directory -Force $CheckoutDirectory | Out-Null
$packages = @()
foreach ($name in $revisions.Keys) {
    $checkout = Join-Path $CheckoutDirectory $name
    if (-not (Test-Path -LiteralPath $checkout)) {
        git clone "https://github.com/unclebob/$name.git" $checkout
        if ($LASTEXITCODE -ne 0) { throw "Clone failed: $name" }
        git -C $checkout checkout --detach $revisions[$name]
        if ($LASTEXITCODE -ne 0) { throw "Checkout failed: $name" }
    }
    $revision = git -C $checkout rev-parse HEAD
    if ($LASTEXITCODE -ne 0 -or $revision -ne $revisions[$name]) {
        throw "Unexpected revision in $checkout; use a separate checkout directory."
    }
    $patch = Join-Path $PSScriptRoot "patches/$name.patch"
    $savedErrorPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $reverseOutput = git -C $checkout apply --reverse --check $patch 2>&1
    $reverseStatus = $LASTEXITCODE
    $ErrorActionPreference = $savedErrorPreference
    if ($reverseStatus -ne 0) {
        git -C $checkout apply --check $patch
        if ($LASTEXITCODE -ne 0) { throw "Patch does not apply cleanly: $name" }
        git -C $checkout apply $patch
        if ($LASTEXITCODE -ne 0) { throw "Patch failed: $name" }
    }
    $packages += $checkout
}
uv pip install --python $Python --reinstall @packages
if ($LASTEXITCODE -ne 0) { throw 'UB tool installation failed.' }
