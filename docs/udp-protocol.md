# UDP audio protocol

The wire format used by live-audio broadcast, as implemented in `network/UdpPacketHandler.kt`. This documents the shipped protocol, byte for byte. `docs/architecture.md` has the surrounding pipeline.

## Basics

- UDP over IPv4, big-endian.
- Host sends from port 9090 to each registered client's port 9091. Discovery announcements go to port 9089.
- Maximum packet size is 1400 bytes (safe for a 1500-byte MTU), so the maximum payload is 1372 bytes after the 28-byte header.
- There is no retransmission and no per-packet ACK. Sequence gaps are counted as loss and reported in metrics; the player inserts silence.

## Packet layout

Every packet — audio, control, discovery, heartbeat — uses the same 28-byte header:

| Offset | Size | Field | Notes |
|---|---|---|---|
| 0 | 4 | Magic | `0x53504B52` ("SPKR") |
| 4 | 1 | Version | `0x01` |
| 5 | 1 | Packet type | see below |
| 6 | 2 | Fragment info | see below |
| 8 | 8 | Session ID | first 8 bytes of the session UUID, UTF-8, zero-padded |
| 16 | 4 | Sequence | packet sequence number, lower 32 bits |
| 20 | 4 | Timestamp | microseconds, lower 32 bits |
| 24 | 4 | CRC32 | checksum of the **payload only**, not the header |
| 28 | … | Payload | `len(packet) − 28` bytes |

### Fragment info (offset 6, 2 bytes)

```
bit  15    : last-fragment flag
bits 14–12 : unused
bits 11–6  : total fragment count (max 63)
bits 5–0   : fragment index (max 63)
```

Audio chunks larger than 1372 bytes are split into fragments sharing a sequence number; the receiver reassembles and only hands complete sets to the decoder.

### Packet types

| Type | Name | Payload |
|---|---|---|
| `0x01` | Audio | one AAC frame or fragment |
| `0x02` | Control | command byte, then optional data |
| `0x03` | Discovery | 4-byte port + UTF-8 host name |
| `0x04` | Heartbeat | empty |
| `0x05` | PCM audio | raw PCM, no codec (NO_LATENCY profile) |

### Control commands (payload byte 0 of a type-`0x02` packet)

| Command | Direction | Meaning |
|---|---|---|
| `0x01` CONNECT | client → host | register as listener |
| `0x02` DISCONNECT | client → host | leaving |
| `0x03` VOLUME | client → host | volume report |
| `0x04` MUTE | client → host | mute state |
| `0x05` ACK | both | acknowledge control/registration |
| `0x06` KICK | host → client | you've been removed |
| `0x10`–`0x14` | various | host-transfer handshake (experimental) |

## Connection flow

```
Client                                        Host (9090)
  |                                             |
  |  bind :9091                                 |
  |  CONNECT (type 0x02, cmd 0x01) ------------>|
  |<--------------------------------- ACK (0x05)|
  |                                             |  registers client's ip:9091
  |<== audio packets (0x01/0x05) ===============|  sent to every registered client
  |                                             |
  |  HEARTBEAT (0x04) ------------------------>|  every ~10 s
  |<----------------------------- HEARTBEAT ----|
  |                                             |
  |  DISCONNECT (0x02) -----------------------> |  or KICK (0x06) in the other direction
```

Because only 8 characters of the session UUID fit on the wire, clients match incoming packets by session prefix first and fall back to comparing the source IP — a session-prefix collision between two hosts would otherwise cross-wire the streams.

## Discovery announcements (port 9089)

The host also broadcasts a JSON announcement on every broadcast-capable subnet, roughly every 5 seconds, with the hotspot subnet tried first:

```json
{
  "type": "SPEAKERSHARE_HOST",
  "sessionId": "…",
  "hostName": "John's Phone",
  "hostIp": "192.168.43.1",
  "audioPort": 9090,
  "controlPort": 8080
}
```

Clients that don't see the mDNS service (`_speakershare._tcp`) usually still see these broadcasts within a few seconds.

## Reference implementation

`app/src/main/java/io/github/gauravyad69/speakershare/network/UdpPacketHandler.kt` is the single source of truth for this format — it builds and parses every packet type, and `app/src/test/java/.../UdpPacketHandlerTest.kt` pins the behavior down. If you change the wire format, change this doc in the same commit.
