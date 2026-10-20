# Application webcam (choix Wi-Fi / USB, recherche automatique du téléphone).
Set-Location "$PSScriptRoot\..\receiver"
& .\.venv\Scripts\pythonw.exe -m webcam_receiver.app
