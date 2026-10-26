# CLAUDE.md

Appli Android Kotlin/Jetpack Compose (`:app`) et webapp Kotlin/Wasm (`:web`) qui recompile le même code. Package
`io.github.pbailly.meteo`. Interface, commentaires, tests et messages de commit en français.

## Commandes
- `./gradlew testDebugUnitTest` : tests JUnit 4 (`app/src/test`). Fixtures binaires dans `src/test/resources/` (bzip2, GRIB2,
  GeoTIFF, ODIM/HDF5, `terrain/profiles.json`), régénérables par les `make_fixture*.py`. Pas de lint ni de formateur.
- `./gradlew assembleRelease` : `app/build/outputs/apk/release/app-release.apk` (la CI le renomme `meteo.apk`).
- `./gradlew :web:site` : webapp dans `web/build/site`, à servir en HTTP (`python3 -m http.server`). Node, Yarn et
  Binaryen viennent des dépôts ivy de `settings.gradle.kts` (`FAIL_ON_PROJECT_REPOS` : aucun dépôt dans un sous-projet ;
  `build.gradle.kts` neutralise `downloadBaseUrl` du plugin Kotlin).
- Coloriages (relecture du dessin d'Aujourd'hui, à refaire après toute modification de `Sketch*.kt`) : voir `design/README.md`
  (Java 21, Python avec reportlab et Pillow, skiko Linux x64 seulement).
- CI : JDK 21 Temurin (le code vise Java 17). `apk.yml` : tests + APK, release `apk-<run>` marquée « latest » créée avant de
  supprimer les anciennes (il y a toujours un `meteo.apk`) ; hors branche par défaut, l'APK reste un artefact. `web.yml` : Pages.
  `versionCode` = `GITHUB_RUN_NUMBER` (1 en local), `versionName` = `0.1.<n>`. `BuildConfig.GIT_SHA` sert à `AppUpdate` pour
  comparer à la dernière release (dépôt `p-bailly/Meteo` codé en dur).

## Architecture
- `data/` réseau, caches et calculs purs ; `data/maps/` couches carte, GRIB2, stations ; `data/radar/` radar, PIAF, nowcast ;
  `ui/` écrans, `ui/radar/` carte, `ui/theme/` ; `widget/` Jetpack Glance ; `share/` fournisseur Nimbus. Les dépôts reçoivent
  une fonction de téléchargement `suspend` (tests sans réseau ; `fetch` côté web).
- JSON lu par `JsonElement` (pas de réflexion). Release minifiée par R8 mais **non obfusquée** (rapport de plantage lisible,
  `CrashLog`). `proguard-rules.pro` garde jhdf (filtres chargés dynamiquement) et le constructeur de la base Room de
  WorkManager (sans lui l'appli plantait au démarrage).
- **Webapp** : `web/build.gradle.kts` liste (`shared`) les fichiers de `app/` repris tels quels. Un fichier partagé ne peut
  utiliser que des API `java.*`, `android.*`, `androidx.*` imitées dans `web/src/wasmJsMain/kotlin/` ; ce qui n'est pas
  portable a un équivalent web du même nom (`Http*`, `DeviceLocation`, `PlaceNames`, `OdimHdf`, `NimbusProvider`, `RadarMap`,
  `Widgets`…). Fichiers du cache en IndexedDB, images encodées par Skia, radar HDF5 lu par h5wasm (`hdf.mjs`, ~4 Mo, chargé
  à la première image) au lieu de jhdf, carte MapLibre GL JS (`map.mjs`) avec `controlsOverMap = false` (rien ne s'affiche
  par-dessus). `sw.js` précache `FILES` : y ajouter tout nouveau fichier statique, sinon il manque hors ligne ; sa version est
  le SHA du commit (`@VERSION@`). Les clés du lien `#cle=…&windy=…` sont lues par `Main.kt` puis effacées de l'adresse.
- La clé de signature `app/meteo-perso.jks` est **versionnée exprès** (mots de passe dans `app/build.gradle.kts`) pour que tout
  APK mette à jour le précédent. Usage perso, pas Play Store.
- Identité « Glacier » : couleurs dans `ui/theme/Theme.kt` (`HeaderBlue`), polices Archivo et Martian Mono dans `res/font/`,
  barre du bas et en-têtes dans `ui/Glacier.kt`. Décisions et pistes de logo : `design/README.md`.

## Réseau, quotas et sources
- **Open-Meteo** : gratuit, 10 000 appels/jour, non commercial (abonnement requis si l'appli devenait commerciale). Une requête
  lourde compte pour plusieurs appels ; une seule pour les favoris (résumé). Altitude inconnue renvoyée comme -9999
  (Tromsø) : `validElevation`. Les codes « orage avec grêle » (96, 99) ne sont fiables qu'en Europe centrale : affichés
  comme orages, pas de grêle.
- **Open-Meteo surchargé** (heures pleines, plusieurs jours en octobre 2026) : `hedged()` (`Hedge.kt`) redemande en parallèle après
  3 s puis 5 s et garde la première réponse ; `retryOnNetworkError` (`Http.kt`) relance après 0,5 s une coupure et 2 s une
  réponse 502/503/504 (3 essais) ; timeouts 8 s (connexion) / 20 s (lecture), 30 s pour Overpass (`slowHttpGet`, qui passe au
  serveur suivant sans réessayer). Prévision « légère » d'abord, niveaux de pression (10× plus lents, utiles à Neige &
  altitude) à part. Sans réseau ou en échec : dernière prévision gardée, même ancienne ; une de moins de 24 h est affichée aussitôt.
- Caches (`WeatherRepository`) : prévision 30 min, multi-modèles 1 h, ensembles 3 h, historique ERA5 des années finies pour
  toujours et des jours récents 6 h. Qualité de l'air 1 h, Kp NOAA 3 h, images radar 2 h (~2 Mo chacune).
- **Historique ERA5** : la première demande d'un lieu (>30 000 jours) coûte 2 000 à 4 500 appels ; automatique pour les
  favoris seulement. Maille 9–25 km : extrêmes adoucis par rapport à une station.
- **Météo-France** (clé « API Key », créée *après* les abonnements, 3 ans) : le radar (lame d'eau, 500 m, HDF5/ODIM) ne donne que
  la dernière image toutes les 5 min, l'app les archive 2 h. PIAF (WCS, +3 h par 15 min, ~450 km autour du lieu) : l'unité est
  lue dans la description du service, valeur de remplissage ou échelle absurde écartées. Sans PIAF, extrapolation TREC sur
  1 h (`Nowcast`, deux images à 5–30 min d'écart). Relevés `DPPaquetObs` (~1 800 stations) ; sans clé : MétéoSuisse et METAR.
- **DWD** ICON-EU température : GRIB2 `.bz2` d'`opendata.dwd.de` (~1 Mo, `Grib2` + `Bzip2` maison, compression CCSDS), plus
  fiable que son serveur de cartes ; refuse les navigateurs, d'où l'absence de la couche sur le web. **EUMETSAT** (satellite
  MTG, foudre) : tuiles WMS sans clé (`WmsLayers`). **Windy** webcams : clé gratuite, URL d'image valables ~10 min.
  Fond de carte OpenFreeMap. L'attribution de la source s'affiche en bas à droite de la carte (obligatoire pour EUMETSAT, DWD, Windy).
- **OpenStreetMap** : Overpass (4 serveurs en secours, `Overpass.SERVERS`), lent (15 à 25 s) ; sommets nommés via Photon si
  Overpass ne répond pas ; nom d'un point via le géocodeur du téléphone, sinon Nominatim.

## Règles de calcul (choix à conserver)
- **Indice de confiance 1–5** (`Predictability`) : part des scénarios qui donnent le même temps que le médian, sur 5 ensembles
  (ECMWF IFS et AIFS, GEFS, ICON, GEM, ~194 scénarios), chaque centre pesant autant. Cinq ensembles et non le seul ECMWF, qui
  donnait 5/5 à J+6 quand les autres centres divergeaient. Seuils 80/60/40/20 % comme Meteoblue ; repli sur les 8 modèles.
- **Pictogramme d'un jour** (`Outlook.representativeCode`) : le temps qui domine, pas le pire moment (le code journalier
  d'Open-Meteo faisait d'une heure d'orage nocturne un jour d'orage). En plaine (relief < 1 500 m), la neige se compte sur le lieu.
- **Altitude** (`Altitude`) : interpolation entre le 2 m et les niveaux 925/850/700/600/500 hPa, puis −0,65 °C/100 m ; limite
  pluie-neige 300 m sous l'iso 0 °C ; 1 mm d'eau ≈ 1 cm de neige ; ressenti par le refroidissement éolien d'Environnement Canada.
- Seuils du dessin (distances OSM, populations, poussières, aurores/Kp, gel…) : dans `SurroundingsQuery`, `AirQuality`,
  `SketchScene` (`SketchSkies`). Ne pas les recopier dans la doc.

## Dessin d'Aujourd'hui (`WeatherSketch`, `SketchScene`, `SketchDraw`, `SketchDecor`, `SketchPostcard`)
- Règle : rien d'inventé. Coupe du relief (`Terrain` : 100 altitudes Copernicus 90 m via Open-Meteo, sommet qui domine jusqu'à
  60 km) + environs OSM (`SurroundingsQuery`), gardés sur le téléphone, préparés en arrière-plan pour les favoris. Premier
  affichage en ~1 s avec la grille d'altitudes et la population du géocodeur Open-Meteo ; Overpass complète ensuite. Sans
  données : sol plat, pas de village au hasard.
- Le dessin ne doit jamais toucher le texte de l'écran (zones mesurées à l'écran, ou `text` côté Nimbus) : l'outil
  `design/dessins` le vérifie. Décor adapté à la région du monde, aux saisons, à l'hémisphère et aux fêtes locales.

## Partage avec Nimbus (launcher `fr.perso.nimbus`) et widgets
- `NimbusProvider`, `content://io.github.pbailly.meteo.nimbus`, lecture seule, refuse tout appelant autre que Nimbus et l'app.
  `now` : lieu, département, températures, ciel, extrêmes, code météo, jour/nuit, heure, `stale` (colonnes dans `COLUMNS`,
  ordre convenu avec Nimbus) ; cache < 30 min sinon appel Open-Meteo attendu 10 s au plus. `sketch?width&height&text&blank` :
  PNG transparent, 64–2048 px (défaut 1027×717), `text` = au plus 24 zones « gauche,haut,droite,bas » en fractions de
  l'image ; `blank=0` : pas de papier blanc autour du texte. Rien n'est téléchargé pour le dessin. `notifyChange` prévient
  Nimbus à chaque nouvelle prévision et changement de style. Ne rien partager d'autre (ni favoris, ni position).
- Widgets (Maintenant, Prévisions, Prochaines heures, Dessin) : `updatePeriodMillis` 30 min (minimum Android), réutilisent le
  cache de l'app, donc pas d'appel Open-Meteo en plus si l'app a été ouverte. Une heure est pluvieuse dès 0,2 mm (`Outlook.WET_HOUR_MM`).
