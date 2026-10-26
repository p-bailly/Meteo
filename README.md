# Météo

Application Android de météo, sans publicité, inspirée de Meteoblue. Prévisions [Open-Meteo](https://open-meteo.com)
(gratuit pour un usage personnel), courbes sur 7 et 14 jours avec indice de confiance, neige et altitude, cartes
(radar Météo-France, satellite, foudre, température, webcams), widgets, et un dessin aux crayons de couleur du lieu en
fond d'« Aujourd'hui ». La même appli existe en version web (WebAssembly) pour iPhone et ordinateur.

## Installer

- **Android** : ouvrir <https://github.com/p-bailly/Meteo/releases/latest/download/meteo.apk> sur le téléphone, puis le
  fichier téléchargé (autoriser l'installation depuis le navigateur). Mise à jour : Réglages → Application →
  « Rechercher une mise à jour », ou refaire la même chose ; elle s'installe par-dessus et garde les favoris.
- **iPhone (iOS 18.2+) et navigateur** : <https://p-bailly.github.io/Meteo/>. Sur iPhone, dans Safari : Partager →
  Sur l'écran d'accueil. Marche hors ligne ; pas de widgets ni de carte de température.

L'APK et la webapp sont recompilés par GitHub Actions à chaque push et publiés depuis la branche par défaut. Une seule
fois : Settings → Pages → Source « GitHub Actions ».

## Clés d'API (facultatives)

- **Radar Météo-France** (gratuit) : compte sur [portail-api.meteofrance.fr](https://portail-api.meteofrance.fr), s'abonner à
  « DonneesPubliquesRadar », « PrevisionImmediatePrecipitations » et « DonneesPubliquesPaquetObservation », **puis**
  générer une clé « API Key » de 3 ans (une clé n'accède qu'aux API souscrites avant sa création).
- **Webcams** : clé gratuite sur api.windy.com.

À coller dans l'écran Cartes (bouton clé) ou dans les Réglages ; elles restent sur l'appareil. Sur la webapp, ouvrir une
fois `https://p-bailly.github.io/Meteo/#cle=<clé Météo-France>&windy=<clé Windy>` les enregistre d'un coup (lien à ne
donner qu'à des proches).

## Développement

JDK 17+ et SDK Android.

```sh
./gradlew testDebugUnitTest   # tests
./gradlew assembleRelease     # APK signé dans app/build/outputs/apk/release/
./gradlew :web:site           # webapp dans web/build/site
```

Architecture, API utilisées et pièges : [CLAUDE.md](CLAUDE.md). Logos et direction artistique : [design/README.md](design/README.md).

## Données

Open-Meteo.com (CC BY 4.0) ; radar et relevés Météo-France (Licence Ouverte Etalab), MétéoSuisse, NOAA AWC (METAR) ; carte
de température DWD (ICON-EU) ; relief Copernicus DEM ; sommets, noms de lieux (Nominatim) et fond de carte OpenFreeMap :
© contributeurs OpenStreetMap (ODbL) ; polices Archivo et Martian Mono (SIL OFL 1.1, voir `licences/`).
