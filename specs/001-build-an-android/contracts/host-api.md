# Host Discovery and Control API Contract

## Base URL
`http://{host_ip}:8080/api/v1`

## Authentication
- No authentication required (local network only)
- Host IP validation for control operations

## Endpoints

### 1. Service Discovery
**GET** `/discovery/info`

Get basic host information for service discovery.

**Response 200**:
```json
{
  "sessionId": "550e8400-e29b-41d4-a716-446655440000",
  "hostName": "John's Phone",
  "audioSource": "MICROPHONE",
  "quality": {
    "bitrate": 128,
    "sampleRate": 44100,
    "encoding": "AAC"
  },
  "isAcceptingClients": true,
  "connectedClients": 3,
  "maxClients": 0,
  "transport": ["WEBRTC", "UDP"]
}
```

**Errors**:
- `503 Service Unavailable`: Host not currently broadcasting

### 2. Client Connection Request
**POST** `/clients/connect`

Request to connect to the host session.

**Request Body**:
```json
{
  "clientId": "client-uuid-here",
  "clientName": "Jane's Phone",
  "preferredTransport": "WEBRTC",
  "capabilities": ["AAC_DECODE", "OPUS_DECODE"]
}
```

**Response 200**:
```json
{
  "status": "ACCEPTED",
  "assignedTransport": "WEBRTC",
  "streamEndpoint": {
    "webrtc": {
      "signalingUrl": "ws://192.168.1.100:8081/webrtc",
      "iceServers": []
    }
  },
  "clientId": "client-uuid-here"
}
```

**Response 429**:
```json
{
  "status": "REJECTED",
  "reason": "MAX_CLIENTS_REACHED",
  "maxClients": 10
}
```

**Errors**:
- `400 Bad Request`: Invalid client data
- `429 Too Many Requests`: Max clients reached
- `503 Service Unavailable`: Host not accepting connections

### 3. Client Disconnection
**POST** `/clients/{clientId}/disconnect`

Disconnect a client (can be called by client or host).

**Response 200**:
```json
{
  "status": "DISCONNECTED",
  "reason": "CLIENT_REQUEST"
}
```

**Errors**:
- `404 Not Found`: Client not found
- `403 Forbidden`: Only client or host can disconnect

### 4. Get Connected Clients
**GET** `/clients`

Get list of all connected clients (host only).

**Response 200**:
```json
{
  "clients": [
    {
      "clientId": "client-1-uuid",
      "clientName": "Jane's Phone", 
      "ipAddress": "192.168.1.101",
      "connectionTime": 1726588800000,
      "status": "CONNECTED",
      "networkMetrics": {
        "latency": 45,
        "packetLoss": 0.1,
        "bandwidth": 16000
      }
    }
  ],
  "totalClients": 1
}
```

### 5. Kick Client
**POST** `/clients/{clientId}/kick`

Remove a client from the session (host only).

**Response 200**:
```json
{
  "status": "KICKED",
  "clientId": "client-1-uuid",
  "reason": "HOST_REMOVED"
}
```

**Errors**:
- `404 Not Found`: Client not found
- `403 Forbidden`: Only host can kick clients

### 6. Update Host Settings
**PUT** `/host/settings`

Update host broadcasting settings during active session.

**Request Body**:
```json
{
  "audioSource": "SYSTEM_AUDIO",
  "quality": {
    "bitrate": 192,
    "sampleRate": 44100,
    "encoding": "AAC"
  },
  "isAcceptingClients": false
}
```

**Response 200**:
```json
{
  "status": "UPDATED",
  "settings": {
    "audioSource": "SYSTEM_AUDIO",
    "quality": {
      "bitrate": 192,
      "sampleRate": 44100, 
      "encoding": "AAC"
    },
    "isAcceptingClients": false
  }
}
```

**Errors**:
- `400 Bad Request`: Invalid settings
- `503 Service Unavailable`: Host not active

### 7. Session Status
**GET** `/session/status`

Get current session status and metrics.

**Response 200**:
```json
{
  "sessionId": "550e8400-e29b-41d4-a716-446655440000",
  "isActive": true,
  "startTime": 1726588800000,
  "uptime": 120000,
  "totalClients": 3,
  "audioMetrics": {
    "bytesTransmitted": 1048576,
    "averageLatency": 85,
    "peakLatency": 150,
    "bufferUnderruns": 0
  }
}
```

## Error Response Format

All error responses follow this format:

```json
{
  "error": {
    "code": "ERROR_CODE",
    "message": "Human readable error message",
    "details": {
      "field": "Additional error details"
    }
  },
  "timestamp": 1726588800000
}
```

## Common Error Codes
- `INVALID_REQUEST`: Malformed request data
- `CLIENT_NOT_FOUND`: Specified client doesn't exist
- `SESSION_INACTIVE`: Host session not active
- `MAX_CLIENTS_REACHED`: Connection limit exceeded
- `NETWORK_ERROR`: Network communication failure
- `AUDIO_ERROR`: Audio processing failure
- `PERMISSION_DENIED`: Insufficient permissions

## Rate Limiting
- Discovery endpoint: 10 requests/minute per IP
- Control endpoints: 60 requests/minute per IP
- Connection requests: 5 requests/minute per IP

## WebSocket Extensions

### WebRTC Signaling
**Endpoint**: `ws://{host_ip}:8081/webrtc/{clientId}`

Used for WebRTC offer/answer exchange and ICE candidates.

**Message Types**:
- `offer`: WebRTC offer from host
- `answer`: WebRTC answer from client  
- `ice-candidate`: ICE candidate exchange
- `connection-state`: Connection status updates

### Real-time Control
**Endpoint**: `ws://{host_ip}:8082/control/{clientId}`

Used for real-time control messages during streaming.

**Message Types**:
- `audio-source-changed`: Host switched audio source
- `quality-changed`: Audio quality updated
- `client-kicked`: Client was removed
- `session-ended`: Host ended session
