"""Étape 3 : flux du téléphone -> webcam virtuelle (Discord, Teams, OBS...).

Usage : python -m webcam_receiver.step3_virtualcam [hôte]   (défaut : 127.0.0.1, via adb forward)
Ctrl+C pour arrêter. Se reconnecte tout seul si le téléphone est débranché.
"""
import socket
import sys
import time

import av
import cv2
import numpy as np
import pyvirtualcam

from . import protocol as proto
from .step2_viewer import Reader, drop_stale

WIDTH, HEIGHT, FPS = 1280, 720, 30


def fallback_image(text: str) -> np.ndarray:
    img = np.zeros((HEIGHT, WIDTH, 3), dtype=np.uint8)
    img[:] = (30, 30, 30)
    size, _ = cv2.getTextSize(text, cv2.FONT_HERSHEY_SIMPLEX, 1.6, 3)
    cv2.putText(img, text, ((WIDTH - size[0]) // 2, (HEIGHT + size[1]) // 2),
                cv2.FONT_HERSHEY_SIMPLEX, 1.6, (200, 200, 200), 3)
    return img


def connect(host: str):
    try:
        sock = socket.create_connection((host, proto.PORT), timeout=1)
    except OSError:
        return None
    sock.settimeout(None)
    sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    sock.sendall(proto.hello())
    return sock


def main():
    host = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1"
    waiting = fallback_image("Telephone deconnecte")
    frame = waiting
    sock = reader = decoder = None
    last_try = 0.0

    with pyvirtualcam.Camera(WIDTH, HEIGHT, FPS, fmt=pyvirtualcam.PixelFormat.RGB) as cam:
        print(f"Caméra virtuelle : {cam.device} ({WIDTH}x{HEIGHT}@{FPS}) ; téléphone : {host}:{proto.PORT}")
        while True:
            if reader is None and time.monotonic() - last_try > 1:
                last_try = time.monotonic()
                sock = connect(host)
                if sock:
                    reader = Reader(sock)
                    reader.start()
                    decoder = av.CodecContext.create("h264", "r")
                    print("Téléphone connecté")

            if reader is not None:
                packets, _ = drop_stale(reader.take())
                for ptype, _flags, _ts, payload in packets:
                    if ptype not in (proto.T_CONFIG, proto.T_FRAME):
                        continue
                    for pkt in decoder.parse(payload):
                        for decoded in decoder.decode(pkt):
                            frame = decoded.to_ndarray(format="rgb24")
                if reader.error is not None:
                    print(f"Déconnecté : {reader.error}")
                    sock.close()
                    sock = reader = None
                    frame = waiting

            cam.send(frame)
            cam.sleep_until_next_frame()


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        pass
