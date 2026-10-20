# Webcam virtuelle en Wi-Fi. Usage : tools\start_wifi.ps1 [ip-du-telephone]
# L'app doit être ouverte sur le téléphone (même réseau que le PC).
param([string]$PhoneIp = "192.168.1.113")
Set-Location "$PSScriptRoot\..\receiver"
& .\.venv\Scripts\python.exe -m webcam_receiver.step3_virtualcam $PhoneIp
