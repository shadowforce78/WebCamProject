# Protocole réseau v1

Flux TCP, big-endian. Le téléphone est le **serveur** (port 27183) ; le PC se connecte,
soit via `adb forward` (USB), soit directement à l'IP Wi-Fi du téléphone.
Un seul client à la fois : une nouvelle connexion remplace l'ancienne.

## En-tête de paquet (16 octets)
| Champ     | Taille | Rôle |
|-----------|--------|------|
| Magic     | 2      | `0xCA 0xFE`, détecte une désynchronisation |
| Type      | 1      | 0 = hello, 1 = config codec (SPS/PPS), 2 = frame vidéo, 3 = contrôle |
| Flags     | 1      | bit 0 = keyframe |
| Timestamp | 8      | microsecondes (horloge du téléphone) |
| Taille    | 4      | longueur du payload |

Suivi du payload (`Taille` octets).

## Hello (PC -> téléphone)
Payload : version (1 octet, =1), largeur (2), hauteur (2), fps (1).
La résolution demandée est ignorée en v1 (fixe : 1280x720 @ 30).

## Séquence
1. Le PC envoie le hello.
2. Le téléphone envoie la config codec (SPS/PPS, type 1).
3. Le téléphone force une keyframe, puis envoie les frames (type 2) en continu.
   Les frames avant la première keyframe sont jetées.

## Contrôle (PC -> téléphone, type 3)
Payload : 1 octet de commande.
- `1` demander une keyframe
- `2` changer de caméra (arrière <-> avant), sans couper la connexion

## Règle de latence
Aucun buffer : si le client est trop lent (file pleine), le téléphone jette les frames
jusqu'à la prochaine keyframe. Côté PC, le lecteur saute à la dernière keyframe si
plus de 4 frames sont en attente.
