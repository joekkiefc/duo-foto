# Duoshot

Android-app (Kotlin, CameraX) voor House of Sneijder om in één keer een
Cardmarket-foto van een kaart te maken: voorkant en achterkant achter elkaar
schieten, de app snijdt beide bij tot de kaart-geleider en zet ze naast
elkaar (voorkant links, achterkant rechts, witte achtergrond) in één JPEG
van ongeveer 3500×2400 px.

Flow: app openen → tik (voorkant) → tik (achterkant) → klaar. De foto staat
in de galerij onder `Pictures/Duoshot/kaart-JJJJMMDD-UUMMSS.jpg` en kan met
**Delen** direct naar de Cardmarket-app of browser.

## APK

GitHub Actions bouwt bij elke push naar `main` een debug-APK
(`.github/workflows/build-apk.yml`): **Actions → laatste run → Artifacts →
`duoshot-debug-apk`**. Unzip en installeer op de telefoon ("Installatie van
onbekende bronnen" toestaan). De APK wordt gesigneerd met de vaste
`keystore/debug.keystore`, zodat een nieuwe versie over de oude heen
geïnstalleerd kan worden. Bij elke release `versionCode` en `versionName`
ophogen in `app/build.gradle.kts`.

## Bouwen en nakijken

De Pi kan geen APK bouwen (aapt2/d8 bestaan niet voor arm64), maar de
Kotlin-bronnen kunnen wél nagekeken worden vóór een push:

```bash
bash /home/pi/.openclaw/workspace-hos/tools/duoshot-check/check.sh
```

Die tool gebruikt kotlinc 1.9.24 met android.jar, de androidx-jars en de
CameraX 1.3.4 classes.jars. Geeft "OK" of de compilerfouten. De echte build
doet de CI.

## Structuur

- `MainActivity.kt` — camera (CameraX, ViewPort voor WYSIWYG), twee opnames,
  opslaan in MediaStore, resultaatscherm met Delen / Volgende kaart
- `CardGuideView.kt` — kaart-geleider (63:88) over de zoeker
- `KaartBeeld.kt` — bijsnijden tot de geleider, samenvoegen, verkleinen
- `res/layout/activity_main.xml` — de vier lagen: camera, bezig, resultaat,
  toestemming
