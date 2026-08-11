param(
    [string]$Subject = "event-ledger-demo",
    [string]$Scopes = "events.read events.write accounts.read ops",
    [string]$Issuer = "event-ledger-local",
    [string]$Audience = "event-ledger-api",
    [string]$SecretBase64 = "ZXZlbnQtbGVkZ2VyLWRldmVsb3BtZW50LXNlY3JldC1jaGFuZ2UtbWU=",
    [int]$ValidMinutes = 60
)

$ErrorActionPreference = "Stop"

function ConvertTo-Base64Url([byte[]]$Bytes) {
    return [Convert]::ToBase64String($Bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

$now = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
$header = '{"alg":"HS256","typ":"JWT"}'
$payload = @{
    iss = $Issuer
    aud = @($Audience)
    sub = $Subject
    scope = $Scopes
    iat = $now
    exp = $now + ($ValidMinutes * 60)
} | ConvertTo-Json -Compress

$encodedHeader = ConvertTo-Base64Url ([Text.Encoding]::UTF8.GetBytes($header))
$encodedPayload = ConvertTo-Base64Url ([Text.Encoding]::UTF8.GetBytes($payload))
$unsignedToken = "$encodedHeader.$encodedPayload"
$hmac = [Security.Cryptography.HMACSHA256]::new([Convert]::FromBase64String($SecretBase64))
try {
    $signature = ConvertTo-Base64Url ($hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes($unsignedToken)))
} finally {
    $hmac.Dispose()
}

Write-Output "$unsignedToken.$signature"
