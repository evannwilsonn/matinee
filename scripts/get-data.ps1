# Downloads MovieLens latest-small into data/ (not committed; see data/README.md).
$root = Split-Path -Parent $PSScriptRoot
New-Item -ItemType Directory -Force -Path "$root/data" | Out-Null
$zip = "$root/data/ml-latest-small.zip"
Invoke-WebRequest -Uri "https://files.grouplens.org/datasets/movielens/ml-latest-small.zip" -OutFile $zip
Expand-Archive -Force $zip -DestinationPath "$root/data"
Remove-Item $zip
Write-Host "Saved data/ml-latest-small/"
