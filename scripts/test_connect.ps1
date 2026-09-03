# scripts/test_connect.ps1
# Test client that connects to a running MeshDrop node, exchanges binary protocol packets
# with NodeIdentity payloads (HELLO -> HELLO_RESPONSE, PING -> PONG), and keeps connection alive.

param(
    [string]$TargetHost = "127.0.0.1",
    [int]$Port = 5000,
    [int]$DurationSeconds = 3
)

Write-Host "Connecting to MeshDrop node at ${TargetHost}:${Port}..." -ForegroundColor Yellow

$client = New-Object System.Net.Sockets.TcpClient
$client.Connect($TargetHost, $Port)
$stream = $client.GetStream()
$stream.ReadTimeout = 5000

Write-Host "Connected!" -ForegroundColor Green

# Function to read exactly N bytes from stream
function Read-ExactBytes($stream, $count) {
    $buffer = New-Object byte[] $count
    $totalRead = 0
    while ($totalRead -lt $count) {
        $read = $stream.Read($buffer, $totalRead, $count - $totalRead)
        if ($read -le 0) { throw "Unexpected EOF while reading stream" }
        $totalRead += $read
    }
    return $buffer
}

# Function to convert Guid to Big-Endian 16-byte array (UUID format)
function Get-UuidBytes([guid]$guid) {
    $bytes = $guid.ToByteArray() # .NET mixed-endian
    # Convert .NET Guid byte layout to RFC 4122 Big-Endian UUID
    $uuidBytes = New-Object byte[] 16
    $uuidBytes[0] = $bytes[3]
    $uuidBytes[1] = $bytes[2]
    $uuidBytes[2] = $bytes[1]
    $uuidBytes[3] = $bytes[0]
    $uuidBytes[4] = $bytes[5]
    $uuidBytes[5] = $bytes[4]
    $uuidBytes[6] = $bytes[7]
    $uuidBytes[7] = $bytes[6]
    [Array]::Copy($bytes, 8, $uuidBytes, 8, 8)
    return $uuidBytes
}

# Function to convert Big-Endian 16-byte array to Guid
function Get-GuidFromUuidBytes([byte[]]$uuidBytes, [int]$offset = 0) {
    $netBytes = New-Object byte[] 16
    $netBytes[0] = $uuidBytes[$offset + 3]
    $netBytes[1] = $uuidBytes[$offset + 2]
    $netBytes[2] = $uuidBytes[$offset + 1]
    $netBytes[3] = $uuidBytes[$offset + 0]
    $netBytes[4] = $uuidBytes[$offset + 5]
    $netBytes[5] = $uuidBytes[$offset + 4]
    $netBytes[6] = $uuidBytes[$offset + 7]
    $netBytes[7] = $uuidBytes[$offset + 6]
    [Array]::Copy($uuidBytes, $offset + 8, $netBytes, 8, 8)
    return New-Object Guid (,$netBytes)
}

# Function to send a binary packet
function Send-MeshDropPacket($stream, [byte]$typeCode, [byte[]]$payload, [guid]$reqId = [guid]::NewGuid()) {
    $magic = [byte[]](0x4D, 0x44, 0x52, 0x50) # 'MDRP'
    $version = [byte]0x01
    $flags = [byte[]](0x00, 0x00)
    
    $payloadLen = if ($payload -ne $null) { $payload.Length } else { 0 }
    $lenBytes = [BitConverter]::GetBytes([int]$payloadLen)
    if ([BitConverter]::IsLittleEndian) { [Array]::Reverse($lenBytes) }

    $guidBytes = Get-UuidBytes $reqId

    $header = New-Object byte[] 28
    [Array]::Copy($magic, 0, $header, 0, 4)
    $header[4] = $version
    $header[5] = $typeCode
    [Array]::Copy($flags, 0, $header, 6, 2)
    [Array]::Copy($lenBytes, 0, $header, 8, 4)
    [Array]::Copy($guidBytes, 0, $header, 12, 16)

    $stream.Write($header, 0, 28)
    if ($payloadLen -gt 0) {
        $stream.Write($payload, 0, $payloadLen)
    }
    $stream.Flush()
}

# Function to read a binary packet
function Read-MeshDropPacket($stream) {
    $header = Read-ExactBytes $stream 28
    
    $typeCode = $header[5]
    $lenBytes = New-Object byte[] 4
    [Array]::Copy($header, 8, $lenBytes, 0, 4)
    if ([BitConverter]::IsLittleEndian) { [Array]::Reverse($lenBytes) }
    $payloadLen = [BitConverter]::ToInt32($lenBytes, 0)

    $reqId = Get-GuidFromUuidBytes $header 12

    $payload = if ($payloadLen -gt 0) { Read-ExactBytes $stream $payloadLen } else { New-Object byte[] 0 }

    return [PSCustomObject]@{
        Type = $typeCode
        Length = $payloadLen
        RequestId = $reqId
        Payload = $payload
    }
}

# Function to encode NodeIdentity payload
function Encode-NodeIdentity([guid]$nodeId, [string]$displayName) {
    $nameBytes = [System.Text.Encoding]::UTF8.GetBytes($displayName)
    $nameLen = [int16]$nameBytes.Length
    $nameLenBytes = [BitConverter]::GetBytes($nameLen)
    if ([BitConverter]::IsLittleEndian) { [Array]::Reverse($nameLenBytes) }

    $idBytes = Get-UuidBytes $nodeId
    $payload = New-Object byte[] (16 + 2 + $nameBytes.Length)
    [Array]::Copy($idBytes, 0, $payload, 0, 16)
    [Array]::Copy($nameLenBytes, 0, $payload, 16, 2)
    [Array]::Copy($nameBytes, 0, $payload, 18, $nameBytes.Length)
    return $payload
}

# Function to decode NodeIdentity payload
function Decode-NodeIdentity([byte[]]$payload) {
    $remoteId = Get-GuidFromUuidBytes $payload 0

    $lenBytes = New-Object byte[] 2
    [Array]::Copy($payload, 16, $lenBytes, 0, 2)
    if ([BitConverter]::IsLittleEndian) { [Array]::Reverse($lenBytes) }
    $nameLen = [BitConverter]::ToInt16($lenBytes, 0)

    $name = [System.Text.Encoding]::UTF8.GetString($payload, 18, $nameLen)
    return [PSCustomObject]@{
        NodeId = $remoteId
        DisplayName = $name
    }
}

# 1. Read initial HELLO packet from server
$greeting = Read-MeshDropPacket $stream
$remoteIdentity = Decode-NodeIdentity $greeting.Payload
Write-Host "Received HELLO from server: $($remoteIdentity.DisplayName) ($($remoteIdentity.NodeId))" -ForegroundColor Cyan

# 2. Send HELLO_RESPONSE back with client identity
$myId = [guid]::NewGuid()
$myPayload = Encode-NodeIdentity $myId "PowerShellClient"
Send-MeshDropPacket $stream 0x02 $myPayload $greeting.RequestId
Write-Host "Sent HELLO_RESPONSE (PowerShellClient ($myId))" -ForegroundColor Green
Write-Host "Connection is now READY!" -ForegroundColor Green

# 3. Send periodic PING packets and read PONG responses
Write-Host "Sending PING packets for ${DurationSeconds}s..." -ForegroundColor Yellow
for ($i = 1; $i -le $DurationSeconds; $i++) {
    Start-Sleep -Seconds 1
    $pingId = [guid]::NewGuid()
    Send-MeshDropPacket $stream 0x03 $null $pingId
    Write-Host "  Sent PING $i (ReqId=$pingId)" -ForegroundColor DarkGray

    $pong = Read-MeshDropPacket $stream
    Write-Host "  Received PONG $i (Type=0x$($pong.Type.ToString('X2')))" -ForegroundColor DarkGreen
}

# 4. Send a MESSAGE packet
$msgBytes = [System.Text.Encoding]::UTF8.GetBytes("Hello from authenticated PowerShell peer!")
Send-MeshDropPacket $stream 0x05 $msgBytes
Write-Host "Sent MESSAGE packet" -ForegroundColor Green

Start-Sleep -Milliseconds 200
$client.Close()
Write-Host "Connection closed cleanly. Handshake and messaging test complete." -ForegroundColor Green
