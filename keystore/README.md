# Keystore

`debug.keystore` staat **bewust in Git** en wordt in `app/build.gradle.kts`
als signingConfig gebruikt voor zowel `debug` als `release`. Het is dezelfde
sleutel als in hos-overlay.

Reden: zonder vaste keystore genereert elke GitHub Actions-runner zijn eigen
sleutel. De APK krijgt dan per build een andere handtekening en Android
weigert de installatie over de vorige versie heen ("App niet geïnstalleerd").

Wachtwoorden zijn de Android-standaard (`android`, alias `androiddebugkey`).
Geen geheim: debug-sleutel voor een app die alleen intern wordt verspreid.

**Regel bij elke release:** hoog `versionCode` én `versionName` op in
`app/build.gradle.kts`. Zonder bump ziet Android "zelfde versie" en weigert
de update stil.

Let op: Duoshot v1.0.0 was met een andere (CI-gegenereerde) sleutel
ondertekend. Wie v1 nog heeft staan, moet die eerst verwijderen.
