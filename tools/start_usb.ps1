# Webcam virtuelle en USB (adb forward). L'app doit être ouverte sur le téléphone.
adb forward tcp:27183 tcp:27183 | Out-Null
Set-Location "$PSScriptRoot\..\receiver"
& .\.venv\Scripts\python.exe -m webcam_receiver.step3_virtualcam 127.0.0.1
