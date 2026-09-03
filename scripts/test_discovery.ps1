# scripts/test_discovery.ps1
# Standalone PowerShell utility to listen for and decode MeshDrop UDP multicast discovery beacons on LAN.
# Optionally broadcasts a test beacon to the multicast group.

param(
    [string]$MulticastGroup = "239.255.77.80",
    [int]$Port = 5001,
    [int]$ListenSeconds = 15,
    [switch]$Emit,
    [string]$NodeName = "PSTestNode",
    [int]$TcpPort = 5000
)

# Helper function to convert Big-Endian 16-byte array to Guid
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

# Helper function to convert Guid to Big-Endian 16-byte array
function Get-UuidBytes([guid]$guid) {
    $bytes = $guid.ToByteArray()
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

if ($Emit) {
    Write-Host "=========================================" -ForegroundColor Cyan
    Write-Host " Emitting MeshDrop UDP Discovery Beacon" -ForegroundColor Cyan
    Write-Host "=========================================" -ForegroundColor Cyan

    $client = New-Object System.Net.Sockets.UdpClient
    $client.EnableBroadcast = $true
    $groupEp = New-Object System.Net.IPEndPoint ([System.Net.IPAddress]::Parse($MulticastGroup), $Port)

    $magic = [byte[]](0x4D, 0x44, 0x52, 0x50) # 'MDRP'
    $version = [byte]0x01
    $msgType = [byte]0x06 # TYPE_BEACON
    $nodeGuid = [guid]::NewGuid()
    $nodeIdBytes = Get-UuidBytes $nodeGuid
    
    $tcpPortBytes = [BitConverter]::GetBytes([int16]$TcpPort)
    if ([BitConverter]::IsLittleEndian) { [Array]::Reverse($tcpPortBytes) }

    $nameBytes = [System.Text.Encoding]::UTF8.GetBytes($NodeName)
    $nameLenBytes = [BitConverter]::GetBytes([int16]$nameBytes.Length)
    if ([BitConverter]::IsLittleEndian) { [Array]::Reverse($nameLenBytes) }

    $packet = New-Object byte[] (26 + $nameBytes.Length)
    [Array]::Copy($magic, 0, $packet, 0, 4)
    $packet[4] = $version
    $packet[5] = $msgType
    [Array]::Copy($nodeIdBytes, 0, $packet, 6, 16)
    [Array]::Copy($tcpPortBytes, 0, $packet, 22, 2)
    [Array]::Copy($nameLenBytes, 0, $packet, 24, 2)
    [Array]::Copy($nameBytes, 0, $packet, 26, $nameBytes.Length)

    $client.Send($packet, $packet.Length, $groupEp) | Out-Null
    $client.Close()

    Write-Host "Beacon broadcasted:" -ForegroundColor Green
    Write-Host "  Node ID:     $nodeGuid" -ForegroundColor White
    Write-Host "  Name:        $NodeName" -ForegroundColor White
    Write-Host "  TCP Port:    $TcpPort" -ForegroundColor White
    Write-Host "  Target:      ${MulticastGroup}:${Port}" -ForegroundColor DarkGray
    exit 0
}

Write-Host "=========================================" -ForegroundColor Cyan
Write-Host " MeshDrop UDP Discovery Listener" -ForegroundColor Cyan
Write-Host "=========================================" -ForegroundColor Cyan
Write-Host "Listening on Multicast Group ${MulticastGroup}:${Port} for ${ListenSeconds}s..." -ForegroundColor Yellow

$udpClient = New-Object System.Net.Sockets.UdpClient
$udpClient.Client.SetSocketOption([System.Net.Sockets.SocketOptionLevel]::Socket, [System.Net.Sockets.SocketOptionName]::ReuseAddress, $true)
$localEp = New-Object System.Net.IPEndPoint ([System.Net.IPAddress]::Any, $Port)
$udpClient.Client.Bind($localEp)

$multicastIp = [System.Net.IPAddress]::Parse($MulticastGroup)
$udpClient.JoinMulticastGroup($multicastIp)
$udpClient.Client.ReceiveTimeout = 2000

$startTime = [System.Diagnostics.Stopwatch]::StartNew()
$count = 0

try {
    while ($startTime.Elapsed.TotalSeconds -lt $ListenSeconds) {
        $senderEp = New-Object System.Net.IPEndPoint ([System.Net.IPAddress]::Any, 0)
        try {
            $data = $udpClient.Receive([ref]$senderEp)
            if ($data.Length -ge 26) {
                # Validate Magic
                if ($data[0] -eq 0x4D -and $data[1] -eq 0x44 -and $data[2] -eq 0x52 -and $data[3] -eq 0x50) {
                    $ver = $data[4]
                    $type = $data[5]
                    $nodeId = Get-GuidFromUuidBytes $data 6
                    
                    $tcpPort = ([int]$data[22] -shl 8) -bor [int]$data[23]
                    $nameLen = ([int]$data[24] -shl 8) -bor [int]$data[25]
                    
                    $name = if ($nameLen -gt 0 -and ($data.Length -ge 26 + $nameLen)) {
                        [System.Text.Encoding]::UTF8.GetString($data, 26, $nameLen)
                    } else { "<empty>" }

                    $count++
                    Write-Host ""
                    Write-Host "[DISCOVERY BEACON #$count]" -ForegroundColor Green
                    Write-Host "  Sender IP:    $($senderEp.Address)" -ForegroundColor White
                    Write-Host "  Node ID:      $nodeId" -ForegroundColor White
                    Write-Host "  Display Name: $name" -ForegroundColor White
                    Write-Host "  TCP Port:     $tcpPort" -ForegroundColor White
                    Write-Host "  Version:      $ver (Type=0x$($type.ToString('X2')))" -ForegroundColor DarkGray
                    Write-Host "  Raw Length:   $($data.Length) bytes" -ForegroundColor DarkGray
                } else {
                    Write-Host "Ignored packet with invalid magic from $($senderEp.Address)" -ForegroundColor DarkYellow
                }
            }
        } catch [System.Net.Sockets.SocketException] {
            # Timeout tick, continue
        }
    }
} finally {
    try { $udpClient.DropMulticastGroup($multicastIp) } catch {}
    $udpClient.Close()
}

Write-Host ""
Write-Host "Discovery listening finished. Received $count beacon(s)." -ForegroundColor Yellow
