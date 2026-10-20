# WebCamProject — téléphone Android en webcam PC

Voir `RECAP.md` pour le plan complet et la feuille de route.

## Plateforme confirmée
- Téléphone : Android 16 (SDK 36), modèle 2406APNFAG, visible via `adb`
- PC : Windows 11 (build 26200), Python 3.13, ffmpeg et adb installés
- Réseau : PC et téléphone sur le même Wi-Fi (192.168.1.x) ; USB aussi disponible

## Structure
```
android/     App Android (Kotlin, Camera2 + MediaCodec)   — étapes 1, 4, 5
receiver/    Récepteur PC (Python, PyAV, pyvirtualcam)      — étapes 0, 2, 3
docs/        Spécification du protocole réseau
tools/       Scripts utilitaires (adb reverse, relecture .h264)
RECAP.md     Plan de départ
```

## Démarrage : étape 0
```
cd receiver
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
python -m webcam_receiver.step0_testpattern
```
Prérequis : OBS Studio installé (fournit le pilote de caméra virtuelle).

## Étape 2 : streaming USB vers une fenêtre
1. Installer et lancer l'app sur le téléphone (`android\gradlew.bat assembleDebug` puis `adb install -r`).
2. `tools\adb_forward.ps1`
3. `cd receiver` puis `.venv\Scripts\python -m webcam_receiver.step2_viewer`
   Touches : `c` change de caméra, `k` keyframe, `q` quitte.
   En Wi-Fi : `python -m webcam_receiver.step2_viewer 192.168.1.113` (sans adb forward).

## Fonctions de l'app
- Appuyer sur l'aperçu : mise au point + exposition à l'endroit touché (retour à l'autofocus continu après 3 s).
- Bouton « Image » : curseurs luminosité (compensation d'exposition) et contraste (courbe de tons), mémorisés entre les lancements.
