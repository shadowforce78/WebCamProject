"""Étape 2 : reçoit le flux H.264 du téléphone et l'affiche dans une fenêtre.

Touches : q = quitter, c = changer de caméra, k = demander une keyframe.
Usage : python -m webcam_receiver.step2_viewer [hôte]   (défaut : 127.0.0.1, via adb forward)
"""
import collections
import socket
import sys
import threading
import time

import av
import cv2

from . import protocol as proto

MAX_PENDING = 4  # au-delà, on saute jusqu'à la dernière keyframe (pas de retard cumulé)


class Reader(threading.Thread):
    """Lit les paquets le plus vite possible pour ne jamais laisser le retard s'accumuler dans le socket."""

    def __init__(self, sock):
        super().__init__(daemon=True)
        self.sock = sock
        self.pending = collections.deque()
        self.lock = threading.Lock()
        self.error = None
        self.bytes_received = 0

    def run(self):
        try:
            while True:
                packet = proto.read_packet(self.sock)
                self.bytes_received += len(packet[3])
                with self.lock:
                    self.pending.append(packet)
        except (OSError, ValueError) as e:
            self.error = e

    def take(self):
        with self.lock:
            packets = list(self.pending)
            self.pending.clear()
        return packets


def drop_stale(packets):
    """Si trop de paquets en attente, repart de la dernière keyframe. Renvoie (paquets, nb jetés)."""
    frames = [p for p in packets if p[0] == proto.T_FRAME]
    if len(frames) <= MAX_PENDING:
        return packets, 0
    last_key = max((i for i, p in enumerate(packets)
                    if p[0] == proto.T_FRAME and p[1] & proto.FLAG_KEYFRAME), default=None)
    if last_key is None:
        return packets, 0
    kept = [p for i, p in enumerate(packets) if i >= last_key or p[0] != proto.T_FRAME]
    return kept, len(packets) - len(kept)


def main():
    host = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1"
    sock = socket.create_connection((host, proto.PORT), timeout=5)
    sock.settimeout(None)
    sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    sock.sendall(proto.hello())
    print(f"Connecté à {host}:{proto.PORT}")

    reader = Reader(sock)
    reader.start()
    decoder = av.CodecContext.create("h264", "r")

    shown = dropped = 0
    last_report = time.monotonic()
    fps = kbps = 0.0
    last_bytes = 0
    image = None

    while reader.error is None:
        packets, n_dropped = drop_stale(reader.take())
        dropped += n_dropped
        for ptype, flags, ts, payload in packets:
            if ptype not in (proto.T_CONFIG, proto.T_FRAME):
                continue
            for pkt in decoder.parse(payload):
                for frame in decoder.decode(pkt):
                    image = frame.to_ndarray(format="bgr24")
                    shown += 1

        if image is not None:
            cv2.putText(image, f"{fps:.0f} fps  {kbps:.0f} kb/s  jetees: {dropped}", (20, 40),
                        cv2.FONT_HERSHEY_SIMPLEX, 1, (0, 255, 0), 2)
            cv2.imshow("Webcam telephone", image)
            image = None
        key = cv2.waitKey(5) & 0xFF
        if key == ord("q"):
            break
        if key == ord("c"):
            sock.sendall(proto.control(proto.C_SWITCH_CAMERA))
        if key == ord("k"):
            sock.sendall(proto.control(proto.C_KEYFRAME))

        now = time.monotonic()
        if now - last_report >= 1:
            fps = shown / (now - last_report)
            kbps = (reader.bytes_received - last_bytes) * 8 / 1000 / (now - last_report)
            shown, last_bytes, last_report = 0, reader.bytes_received, now

    if reader.error:
        print(f"Déconnecté : {reader.error}")
    sock.close()
    cv2.destroyAllWindows()


if __name__ == "__main__":
    main()
