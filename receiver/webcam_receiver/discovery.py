"""Recherche automatique du téléphone : Wi-Fi (broadcast UDP, puis scan TCP du sous-réseau) ou USB (adb)."""
import concurrent.futures
import ipaddress
import os
import shutil
import socket
import subprocess
import time

from . import protocol as proto

ADB_FALLBACKS = (r"C:\Android\SDK\platform-tools\adb.exe",
                 os.path.expandvars(r"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"))
NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)


def local_networks() -> list[ipaddress.IPv4Network]:
    """Sous-réseaux /24 des interfaces IPv4 du PC (hors loopback et lien local)."""
    ips = set()
    try:
        ips.update(i[4][0] for i in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET))
    except OSError:
        pass
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("192.0.2.1", 9))  # aucun paquet envoyé, sert à connaître l'interface par défaut
            ips.add(s.getsockname()[0])
    except OSError:
        pass
    nets = []
    for ip in sorted(ips):
        addr = ipaddress.IPv4Address(ip)
        if addr.is_loopback or addr.is_link_local:
            continue
        nets.append(ipaddress.IPv4Network(f"{ip}/24", strict=False))
    return nets


def _udp_discover(nets, timeout: float) -> dict[str, str]:
    found = {}
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        s.settimeout(0.2)
        targets = ["255.255.255.255"] + [str(n.broadcast_address) for n in nets]
        deadline = time.monotonic() + timeout
        last_send = 0.0
        while time.monotonic() < deadline:
            if time.monotonic() - last_send > 0.4:  # on répète : un broadcast Wi-Fi peut se perdre
                for t in targets:
                    try:
                        s.sendto(b"WEBCAM?", (t, proto.DISCOVERY_PORT))
                    except OSError:
                        pass
                last_send = time.monotonic()
            try:
                data, (ip, _) = s.recvfrom(128)
            except (socket.timeout, OSError):
                continue
            if data.startswith(b"WEBCAM!"):
                found[ip] = data[7:].decode(errors="replace").split("|", 1)[-1]
    return found


def _port_open(ip: str, timeout: float = 0.4) -> bool:
    try:
        with socket.create_connection((ip, proto.PORT), timeout=timeout):
            return True
    except OSError:
        return False


def _tcp_scan(nets) -> dict[str, str]:
    hosts = [str(h) for n in nets for h in n.hosts()]
    with concurrent.futures.ThreadPoolExecutor(max_workers=128) as pool:
        results = list(pool.map(_port_open, hosts))
    return {h: "" for h, ok in zip(hosts, results) if ok}


def find_wifi(timeout: float = 1.5) -> list[tuple[str, str]]:
    """[(ip, nom)] des téléphones trouvés. Broadcast UDP d'abord (rapide), scan TCP en secours
    (marche aussi avec une ancienne version de l'app qui ne répond pas au broadcast)."""
    nets = local_networks()[:4]
    found = _udp_discover(nets, timeout)
    if not found:
        found = _tcp_scan(nets)
    return sorted(found.items())


def find_adb() -> str | None:
    return shutil.which("adb") or next((p for p in ADB_FALLBACKS if os.path.isfile(p)), None)


def _adb(adb: str, *args: str) -> str:
    return subprocess.run([adb, *args], capture_output=True, text=True, timeout=10,
                          creationflags=NO_WINDOW).stdout


def find_usb() -> tuple[str | None, str]:
    """Prépare le tunnel adb. Renvoie (hôte, message) ; hôte = None si aucun téléphone prêt."""
    adb = find_adb()
    if adb is None:
        return None, "adb introuvable (installe platform-tools et ajoute-le au PATH)"
    try:
        lines = _adb(adb, "devices").splitlines()[1:]
        devices = [l.split()[0] for l in lines if l.split()[1:2] == ["device"]]
        unauthorized = [l for l in lines if l.split()[1:2] == ["unauthorized"]]
        if not devices:
            if unauthorized:
                return None, "téléphone non autorisé : accepte le débogage USB sur l'écran"
            return None, "aucun téléphone en USB (câble de données + débogage USB)"
        _adb(adb, "-s", devices[0], "forward", f"tcp:{proto.PORT}", f"tcp:{proto.PORT}")
    except (OSError, subprocess.SubprocessError) as e:
        return None, f"erreur adb : {e}"
    return "127.0.0.1", f"USB ({devices[0]})"
