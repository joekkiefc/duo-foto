# Duoshot

Native Android app om 2 foto's samen te voegen tot 1 (bv. voor- en achterkant
van een Pokémon-kaart). Zit als WebView-app in elkaar: UI + canvas-combine
gebeuren in vanilla HTML/JS in `app/src/main/assets/www/`, save-naar-galerij
via een JavaScript ↔ Kotlin bridge (`DuoshotBridge.saveJpeg`).

## APK downloaden

GitHub Actions bouwt bij elke push naar `main` automatisch een debug-APK.
Ga naar **Actions → laatste run → Artifacts → `duoshot-debug-apk`**, unzip
en installeer op je Android-telefoon (sta "Installatie van onbekende
bronnen" toe).

## Development

Zonder Android Studio is er niks lokaal te bouwen (behalve de HTML aanpassen).
Voor de HTML/JS/CSS zelf: open `app/src/main/assets/www/index.html` in Chrome
(alle behalve `saveJpeg` werkt daar identiek — download valt terug op een
`<a download>`-link).

Native structuur is minimaal:
- `MainActivity.kt` — WebView setup + camera-permissie + save-bridge
- `AndroidManifest.xml` — camera + storage permissies, portrait
- `assets/www/index.html` — de complete tool
