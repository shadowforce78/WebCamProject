# Étape 2 : tunnel USB. Le PC se connecte à localhost:27183, adb relaie vers l'app sur le téléphone.
adb forward tcp:27183 tcp:27183
adb forward --list
