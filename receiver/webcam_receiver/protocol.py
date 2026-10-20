"""Format des paquets, voir docs/protocol.md."""
import struct

MAGIC = b"\xca\xfe"
HEADER = struct.Struct(">2sBBQI")  # 16 octets
PORT = 27183
DISCOVERY_PORT = 27184  # UDP : le PC broadcast "WEBCAM?", le téléphone répond "WEBCAM!<version>|<modèle>"

T_HELLO, T_CONFIG, T_FRAME, T_CONTROL = range(4)
FLAG_KEYFRAME = 1
PROTOCOL_VERSION = 1

C_KEYFRAME = 1
C_SWITCH_CAMERA = 2


def pack(ptype: int, flags: int, ts_us: int, payload: bytes) -> bytes:
    return HEADER.pack(MAGIC, ptype, flags, ts_us, len(payload)) + payload


def hello(width: int = 1280, height: int = 720, fps: int = 30) -> bytes:
    return pack(T_HELLO, 0, 0, struct.pack(">BHHB", PROTOCOL_VERSION, width, height, fps))


def control(command: int) -> bytes:
    return pack(T_CONTROL, 0, 0, bytes([command]))


def read_exact(sock, n: int) -> bytes:
    buf = bytearray()
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("connexion fermée")
        buf += chunk
    return bytes(buf)


def read_packet(sock):
    magic, ptype, flags, ts, size = HEADER.unpack(read_exact(sock, HEADER.size))
    if magic != MAGIC:
        raise ValueError("désynchronisation : magic invalide")
    return ptype, flags, ts, read_exact(sock, size)
