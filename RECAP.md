# Webcam téléphone — récap projet

1er octobre 2026 · Adam

Version en ligne (éditable) : https://claude.ai/code/artifact/89b07d63-57be-4045-9292-bf1e105cba11

## Contexte et objectif

Objectif : utiliser le téléphone comme webcam PC, sans watermark, avec un logiciel entièrement maison.

- La webcam du PC est morte ; un support imprimé en 3D tient déjà le téléphone.
- DroidCam et ses équivalents imposent un watermark, d'où le choix de tout développer soi-même.
- Critère de réussite v1 : le flux du téléphone apparaît comme une caméra dans Discord, Teams et OBS, en 720p30, avec moins de 200 ms de latence perçue.
- Hors périmètre v1 : l'audio, le multi-téléphone, les effets vidéo.

## Hypothèses et choix techniques

Le plan suppose un téléphone Android et un PC Windows ; c'est à confirmer, car un iPhone ou un PC sous Linux change deux briques (voir la fin du tableau).

| Brique | Choix v1 (prototype) | Choix v2 (propre) | Pourquoi |
| --- | --- | --- | --- |
| App téléphone | Kotlin + CameraX | Kotlin + Camera2 | CameraX est plus simple ; Camera2 donne le contrôle fin (exposition, focus, fps) |
| Encodage | MediaCodec H.264 matériel | Idem, débit réglable | L'encodage matériel ne chauffe presque pas le téléphone et compresse environ 50 fois mieux que du JPEG par frame |
| Transport | TCP via `adb reverse` (USB) | TCP USB + UDP Wi-Fi | L'USB évite la découverte réseau et le pare-feu, et alimente le téléphone |
| Réception PC | Python + PyAV | C++ ou Rust + FFmpeg (libavcodec) | Python pour valider vite, natif pour la latence et un .exe distribuable |
| Webcam virtuelle | `pyvirtualcam` (backend caméra OBS) | DirectShow via softcam, puis `MFCreateVirtualCamera` (Windows 11) | Du plus simple au plus intégré |
| Si iPhone | Swift + AVFoundation + VideoToolbox | — | Transport USB via usbmuxd, plus pénible qu'ADB |
| Si Linux | v4l2loopback | — | La webcam virtuelle devient triviale |

## Architecture

La webcam virtuelle est la brique à risque du pipeline.

```
 TÉLÉPHONE ANDROID
 ┌──────────────────┐    ┌──────────────────┐    ┌──────────────────┐
 │ Caméra           │ →  │ Encodeur H.264   │ →  │ Envoi            │
 │ CameraX          │    │ MediaCodec       │    │ en-tête + TCP    │
 └──────────────────┘    └──────────────────┘    └────────┬─────────┘
                                                          │ USB (adb reverse)
                                                          │ Wi-Fi en option
 PC WINDOWS                                               ▼
 ┌──────────────────┐    ┌──────────────────┐    ┌──────────────────┐
 │ WEBCAM VIRTUELLE │ ←  │ Décodeur         │ ←  │ Réception        │
 │ pyvirtualcam     │    │ PyAV / FFmpeg    │    │ découpage paquets│
 └────────┬─────────┘    └──────────────────┘    └──────────────────┘
          ▼
   Discord · Teams · OBS
```

Le téléphone capture, encode et envoie ; le PC reçoit, décode et expose le flux comme une caméra. Les flèches suivent la vidéo ; les messages de contrôle (keyframe, résolution) passent en sens inverse sur la même connexion.

## Feuille de route

On commence par la brique la plus risquée, la webcam virtuelle, puis on remonte le pipeline. Chaque étape a un critère « c'est fini quand… » : tant qu'il n'est pas atteint, on ne passe pas à la suivante.

1. **Étape 0 — Webcam virtuelle avec fausses images (PC seul)**
   - Générer une mire animée (dégradé + compteur de frames) et l'envoyer dans la webcam virtuelle.
   - Fini quand la mire apparaît comme caméra dans Discord ou Teams, à 30 fps stables.
2. **Étape 1 — App Android : aperçu + encodage dans un fichier**
   - Permission caméra, aperçu à l'écran, encodeur H.264 MediaCodec branché sur la surface de la caméra.
   - Écrire le flux H.264 brut (Annex B) dans un fichier.
   - Fini quand le fichier se relit avec `ffplay` sans artefacts ni rotation bizarre.
3. **Étape 2 — Streaming USB vers une fenêtre PC**
   - Serveur TCP sur le téléphone ou sur le PC, tunnel `adb reverse` ou `adb forward`.
   - Côté PC : réception, découpage des paquets, décodage, affichage dans une simple fenêtre.
   - Fini quand on voit sa main bouger en direct avec un retard à peine perceptible, après 10 minutes sans dérive.
4. **Étape 3 — Brancher le décodeur sur la webcam virtuelle**
   - Remplacer la mire de l'étape 0 par les frames décodées et convertir le format de couleur si besoin.
   - Fini quand tu fais un vrai appel Discord avec le téléphone comme caméra.
5. **Étape 4 — Robustesse**
   - Reconnexion automatique (câble débranché, app relancée), keyframe à chaque nouvelle connexion, foreground service.
   - Fini quand on peut débrancher et rebrancher le câble 5 fois sans relancer quoi que ce soit.
6. **Étape 5 — Confort**
   - Choix de la résolution et des fps, caméra avant/arrière, verrouillage focus et exposition, écran du téléphone éteint pendant la diffusion.
7. **Étape 6 — Wi-Fi (optionnel)**
   - Découverte du téléphone (mDNS ou IP saisie à la main), puis UDP avec numéros de séquence.
8. **Étape 7 — Version « propre » (optionnel)**
   - Réécriture du récepteur en natif, webcam virtuelle sans dépendance à OBS, installeur, petite interface dans la barre des tâches.

## Protocole réseau

Un flux TCP n'a pas de notion de « message » : il faut définir son propre en-tête dès le début, sinon le débogage devient impossible.

Format proposé pour chaque paquet, en big-endian :

| Champ | Taille | Rôle |
| --- | --- | --- |
| Magic | 2 octets | Valeur fixe pour détecter une désynchronisation |
| Type | 1 octet | Config codec (SPS/PPS), frame vidéo, message de contrôle |
| Flags | 1 octet | Keyframe oui/non |
| Timestamp | 8 octets | En microsecondes, sert à mesurer la latence et à jeter les frames trop vieilles |
| Taille | 4 octets | Longueur du payload qui suit |
| Payload | variable | Données H.264 ou message de contrôle |

Séquence à la connexion :

1. Le client envoie un « hello » avec la version du protocole et la résolution souhaitée.
2. Le téléphone renvoie la config codec (SPS/PPS) stockée.
3. Le téléphone force une keyframe, puis envoie les frames en continu.
4. Canal de contrôle dans l'autre sens : changer de caméra, de résolution, demander une keyframe.

Prévois un champ de version dès la v1 : si le protocole change, l'app et le récepteur sauront qu'ils ne se comprennent plus.

## Pièges connus

La plupart des projets de ce genre se cassent les dents sur la latence et sur la webcam virtuelle. Voici les pièges classés par brique.

| Brique | Piège | Parade |
| --- | --- | --- |
| Android | La caméra est coupée quand l'écran s'éteint ou que l'app passe en arrière-plan | Foreground service de type `camera`, lancé pendant que l'app est visible (obligatoire depuis Android 14) |
| Android | Le capteur est tourné de 90°, et la caméra frontale est en miroir | Choisir un seul endroit pour la rotation, idéalement côté téléphone |
| Android | Mise au point et exposition qui « pompent » en permanence | Verrouiller AF/AE une fois l'image stable (étape 5) |
| Android | Le téléphone chauffe et finit par baisser les fps | 720p30 par défaut, débit modéré, téléphone branché, écran éteint pendant la diffusion |
| Encodage | Le décodeur ne démarre jamais | Garder les buffers `BUFFER_FLAG_CODEC_CONFIG` (SPS/PPS) et les renvoyer à chaque connexion |
| Encodage | Image grise ou cassée en début de connexion | Forcer une keyframe avec `PARAMETER_KEY_REQUEST_SYNC_FRAME` à chaque nouveau client |
| Encodage | Latence qui grimpe | Pas de B-frames (profil baseline ou constrained), intervalle de keyframes de 1 à 2 s |
| Transport | Retard qui s'accumule (2 s après 10 minutes) | Aucun buffer côté réception : si on est en retard, on jette les frames au lieu de les empiler |
| Transport | Avec TCP en Wi-Fi, un paquet perdu bloque tout le flux | Rester en USB pour la v1 ; UDP plus tard |
| Transport | `adb` absent ou téléphone non autorisé | Détecter le cas et afficher un message clair (débogage USB, autorisation RSA) |
| Décodage PC | CPU à 100 % | Décodage matériel (D3D11VA ou DXVA2 via FFmpeg) dès que possible |
| Décodage PC | Conversion de couleur coûteuse (NV12 vers RGB) | Choisir un format accepté nativement des deux côtés, NV12 de préférence |
| Webcam virtuelle | La résolution est figée à l'enregistrement de la caméra | Une seule résolution fixe en v1 (1280×720) |
| Webcam virtuelle | Certaines applis ne voient pas la caméra (Teams, navigateurs) | Tester tôt dans plusieurs applis ; DirectShow et Media Foundation ne sont pas vus par les mêmes logiciels |
| Webcam virtuelle | Image noire quand aucun téléphone n'est connecté | Afficher une image de repli (« téléphone déconnecté ») plutôt qu'un flux vide |

## Plan B et ressources

Plan B immédiat : `scrcpy` (version 2.2 ou plus, téléphone sous Android 12 minimum) diffuse déjà la caméra du téléphone avec l'option `--video-source=camera`. Avec la caméra virtuelle d'OBS, ça dépanne sans watermark en attendant ta version.

À lire et à étudier :

- [scrcpy](https://github.com/Genymobile/scrcpy) : la référence pour les étapes 1 et 2 (MediaCodec, ADB, protocole). Lis son code, ne le copie pas.
- [Documentation CameraX](https://developer.android.com/media/camera/camerax) et [MediaCodec](https://developer.android.com/reference/android/media/MediaCodec).
- [PyAV](https://pyav.org) pour décoder en Python, et [pyvirtualcam](https://github.com/letmaik/pyvirtualcam) pour la webcam virtuelle du prototype.
- [softcam](https://github.com/tshino/softcam) : webcam virtuelle DirectShow en C++, pour la version propre.
- [MFCreateVirtualCamera](https://learn.microsoft.com/en-us/windows/win32/api/mfvirtualcamera/nf-mfvirtualcamera-mfcreatevirtualcamera) : l'API Windows 11, le boss final.
- Outils de débogage : `ffplay` pour relire un .h264, `adb logcat` côté téléphone, Wireshark si le protocole fait des siennes.

## Checklist de suivi

- [ ] Confirmer la plateforme : Android ou iPhone, version de Windows
- [ ] Étape 0 : la mire est visible dans Discord ou Teams
- [ ] Étape 1 : le fichier .h264 se relit proprement dans `ffplay`
- [ ] Étape 2 : flux en direct dans une fenêtre PC, sans dérive sur 10 minutes
- [ ] Étape 3 : premier vrai appel avec le téléphone comme webcam
- [ ] Étape 4 : 5 débranchements/rebranchements sans rien relancer
- [ ] Étape 5 : réglages résolution, caméra, focus/exposition
- [ ] Étape 6 : mode Wi-Fi
- [ ] Étape 7 : récepteur natif + webcam virtuelle sans OBS + installeur
