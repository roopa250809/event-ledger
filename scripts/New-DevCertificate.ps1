param(
    [string]$Password = "changeit"
)

$ErrorActionPreference = "Stop"
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$certificateDirectory = Join-Path $repositoryRoot "dev-certs"
$keyStore = Join-Path $certificateDirectory "gateway.p12"
New-Item -ItemType Directory -Force -Path $certificateDirectory | Out-Null

& keytool -genkeypair -noprompt `
    -alias event-gateway `
    -keyalg RSA `
    -keysize 2048 `
    -storetype PKCS12 `
    -keystore $keyStore `
    -storepass $Password `
    -keypass $Password `
    -validity 365 `
    -dname "CN=localhost, OU=Development, O=Event Ledger, L=Local, ST=Local, C=US" `
    -ext "SAN=dns:localhost,ip:127.0.0.1"

Write-Output "Created $keyStore (development use only)."
