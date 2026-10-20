"""Application PC : choix Wi-Fi / USB, recherche automatique du téléphone, webcam virtuelle.

Usage : python -m webcam_receiver.app
"""
import queue
import socket
import threading
import time
import tkinter as tk
from tkinter import ttk

import av
import numpy as np
import pyvirtualcam

from . import discovery
from . import protocol as proto
from .step2_viewer import Reader, drop_stale
from .step3_virtualcam import FPS, HEIGHT, WIDTH, fallback_image

RETRY_S = 2.0


class Streamer(threading.Thread):
    """Cherche le téléphone selon le mode, se connecte, pousse les frames vers la webcam virtuelle.
    Si la connexion tombe, il cherche à nouveau (l'IP Wi-Fi a pu changer)."""

    def __init__(self, mode: str, events: queue.Queue):
        super().__init__(daemon=True)
        self.mode = mode
        self.events = events
        self.stop_event = threading.Event()

    def say(self, kind: str, text: str):
        self.events.put((kind, text))

    def locate(self):
        """Renvoie (hôte, description) ou None."""
        if self.mode == "usb":
            host, msg = discovery.find_usb()
            if host is None:
                self.say("status", f"USB : {msg}")
                return None
            return host, msg
        self.say("status", "Wi-Fi : recherche du téléphone…")
        found = discovery.find_wifi()
        if not found:
            self.say("status", "Wi-Fi : aucun téléphone trouvé (app ouverte ? même réseau ?)")
            return None
        ip, name = found[0]
        return ip, f"Wi-Fi {ip}" + (f" ({name})" if name else "")

    def connect(self, host: str):
        try:
            sock = socket.create_connection((host, proto.PORT), timeout=2)
        except OSError:
            return None
        sock.settimeout(None)
        sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        sock.sendall(proto.hello())
        return sock

    def run(self):
        waiting = fallback_image("Telephone deconnecte")
        frame = waiting
        sock = reader = decoder = None
        last_try = 0.0
        try:
            with pyvirtualcam.Camera(WIDTH, HEIGHT, FPS, fmt=pyvirtualcam.PixelFormat.RGB) as cam:
                self.say("status", f"Caméra virtuelle prête ({cam.device})")
                while not self.stop_event.is_set():
                    if reader is None and time.monotonic() - last_try > RETRY_S:
                        found = self.locate()
                        if found:
                            host, desc = found
                            sock = self.connect(host)
                            if sock:
                                reader = Reader(sock)
                                reader.start()
                                decoder = av.CodecContext.create("h264", "r")
                                self.say("connected", f"Connecté · {desc}")
                            else:
                                self.say("status", f"{desc} : connexion refusée (app ouverte sur le téléphone ?)")
                        last_try = time.monotonic()

                    if reader is not None:
                        packets, _ = drop_stale(reader.take())
                        for ptype, _f, _ts, payload in packets:
                            if ptype not in (proto.T_CONFIG, proto.T_FRAME):
                                continue
                            for pkt in decoder.parse(payload):
                                for decoded in decoder.decode(pkt):
                                    frame = decoded.to_ndarray(format="rgb24")
                        if reader.error is not None:
                            sock.close()
                            sock = reader = None
                            frame = waiting
                            self.say("status", "Téléphone déconnecté, nouvelle recherche…")

                    cam.send(np.ascontiguousarray(frame))
                    cam.sleep_until_next_frame()
        except Exception as e:  # driver de webcam virtuelle absent, etc.
            self.say("error", f"Erreur : {e}")
        finally:
            if sock:
                sock.close()
            self.say("stopped", "Arrêté")


class App(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("Webcam téléphone")
        self.resizable(False, False)
        self.events: queue.Queue = queue.Queue()
        self.streamer: Streamer | None = None

        pad = {"padx": 16, "pady": 6}
        ttk.Label(self, text="Connexion", font=("Segoe UI", 11, "bold")).pack(anchor="w", **pad)
        self.mode = tk.StringVar(value="wifi")
        for value, label in (("wifi", "Wi-Fi (même réseau que le PC)"), ("usb", "Filaire (câble USB)")):
            ttk.Radiobutton(self, text=label, value=value, variable=self.mode).pack(anchor="w", padx=28)

        self.button = ttk.Button(self, text="Démarrer", command=self.toggle)
        self.button.pack(fill="x", padx=16, pady=(14, 6))
        self.status = tk.StringVar(value="Prêt. Ouvre l'app sur le téléphone, puis démarre.")
        ttk.Label(self, textvariable=self.status, wraplength=320, foreground="#444").pack(anchor="w", **pad)

        self.protocol("WM_DELETE_WINDOW", self.on_close)
        self.after(100, self.poll)

    def toggle(self):
        if self.streamer is None:
            self.streamer = Streamer(self.mode.get(), self.events)
            self.streamer.start()
            self.button.config(text="Arrêter")
            for child in self.winfo_children():
                if isinstance(child, ttk.Radiobutton):
                    child.state(["disabled"])
        else:
            self.streamer.stop_event.set()
            self.button.state(["disabled"])

    def poll(self):
        try:
            while True:
                kind, text = self.events.get_nowait()
                if kind != "stopped" or not self.status.get().startswith("Erreur"):
                    self.status.set(text)  # garde le message d'erreur affiché après l'arrêt
                if kind == "stopped":
                    self.streamer = None
                    self.button.config(text="Démarrer")
                    self.button.state(["!disabled"])
                    for child in self.winfo_children():
                        if isinstance(child, ttk.Radiobutton):
                            child.state(["!disabled"])
        except queue.Empty:
            pass
        self.after(100, self.poll)

    def on_close(self):
        if self.streamer is not None:
            self.streamer.stop_event.set()
            self.streamer.join(timeout=2)
        self.destroy()


if __name__ == "__main__":
    App().mainloop()
