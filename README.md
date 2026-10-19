# Météo

Application Android de météo, sans publicité, inspirée de Meteoblue. Les prévisions viennent de
[Open-Meteo](https://open-meteo.com), gratuit pour un usage personnel.

## Installer sur le téléphone

1. Sur le téléphone, ouvrir :
   **https://github.com/p-bailly/Meteo/releases/latest/download/meteo.apk**
2. Ouvrir le fichier téléchargé. Android demande d'autoriser l'installation depuis le navigateur
   (« sources inconnues ») : accepter pour cette fois.
3. Pour mettre à jour, refaire la même chose : la nouvelle version s'installe par-dessus l'ancienne
   et garde les favoris.

L'APK est recompilé automatiquement par GitHub Actions à chaque modification du code, et publié
seulement depuis la branche par défaut du dépôt (`.github/workflows/apk.yml`).

## Ce que fait l'app

Cinq onglets dans la barre du bas, ou en glissant d'une page à l'autre (sauf sur la carte, où le glissement déplace
la carte) :

- **Aujourd'hui** : le lieu et son altitude, la température en grand, le ciel, le ressenti et les extrêmes du jour ;
  le bloc **Altitude · maintenant** : coupe du relief du lieu vers le plus haut sommet des environs, température
  sur place, à 1 000, 1 500, 2 000, 3 000 m et au sommet, gradient (°C / 100 m), iso 0 °C (tirets) et limite
  pluie-neige (pointillés), ressenti et vent à 2 000 m, risque de verglas ; puis vent, rafales, humidité, pression
  (et sa tendance), indice UV, coucher et lever du soleil ; et les **48 prochaines heures** à faire défiler
  (pictogramme, courbe de température, probabilité et barres de pluie, résumé « pluie 17-20 h · 3,0 mm »).
  Toucher le bloc Altitude ouvre la page **Neige & altitude** (voir plus bas).
- **Prévisions** (14 jours par défaut, ou 7) : alertes **retour de la neige** (jours, limite pluie-neige, cumuls
  à 2 000 m) et **verglas possible** (créneau, routes concernées) ; tableau des jours : mini-maxi, pluie (mm), vent
  maximal (km/h), ensoleillement (heures), neige s'il en tombe, iso 0 °C du jour, et **fenêtres de beau temps**
  (au moins 6 h sèches, peu nuageuses, vent < 20 km/h). Toucher un jour ouvre son **détail heure par heure** (avec la
  prévisibilité, comme sur Meteoblue).
- **Météogramme** : météogramme horaire sur 7 jours, graphique 14 jours avec la prévisibilité, et comparaison de
  8 modèles avec leur moyenne.
- **Neige & altitude** (depuis le bloc Altitude d'Aujourd'hui ; par jour ou par tranche de 3 heures) : coupe du relief vallée → sommets avec
  l'iso 0, la limite pluie-neige, les sommets nommés, la neige attendue à 2 000 et 3 000 m et les pentes enneigées ;
  iso 0 et limite pluie-neige jour par jour, neige à 2 000 m ; température, ressenti, vent et neige au sommet,
  à 2 000 m et sur le lieu.
- **Radar** : les cartes, avec frise des heures et animation :
  - **Pluie** : radar Météo-France et sa prévision (voir ci-dessous pour la clé) ;
  - **Température** à 2 m maintenant : modèle ICON-EU du DWD (sans clé), colorée comme les cases de température de
    l'app, avec les **vrais relevés** des stations en pastilles : stations Météo-France avec la clé (environ 1 800),
    stations de MétéoSuisse et aéroports (METAR) sans clé. Toucher une pastille affiche toutes les mesures de la
    station (humidité, point de rosée, vent, rafales, pression, pluie, neige au sol, visibilité…) et sa température
    des dernières 24 heures (Météo-France et aéroports) ;
  - **Satellite** : Meteosat de 3e génération (EUMETSAT, sans clé), une image toutes les 10 min sur 2 h,
    vraies couleurs le jour, nuages et lumières des villes la nuit ;
  - **Foudre** : imageur d'éclairs de Meteosat (EUMETSAT, sans clé), éclairs par 5 min sur la dernière heure ;
  - **Webcams** autour du lieu : Windy, avec une clé gratuite (api.windy.com) à saisir dans les Réglages ;
    toucher un point affiche l'image et le lien vers la page Windy.
- **Lieux** : recherche (ville, col, station, sommet), **météo à ma position** (GPS du téléphone, sans les services
  Google), favoris **rangés par altitude** avec leur température, leurs extrêmes et une note (ressenti glacial,
  verglas, neige à venir) ; l'iso 0 °C du jour et la plus basse limite pluie-neige de la semaine s'intercalent entre
  les lieux, à leur altitude. « Modifier » retire des favoris ; l'engrenage ouvre les **Réglages** (modèles de la
  comparaison, clés Météo-France et Windy).

Comment la prévision est résumée :

- **Pictogramme d'un jour (ou de 3 heures)** : le temps qui domine, et non le pire moment comme le code journalier
  d'Open-Meteo (une heure d'orage la nuit en faisait un jour d'orage). Pluie, neige, orage ou verglas seulement s'ils
  durent (au moins 2 heures et 1 mm sur une journée), intensité selon le cumul, sinon le ciel moyen de la journée.
- **Pas de grêle** : les codes « orage avec grêle » d'Open-Meteo ne sont fiables qu'en Europe centrale ; ils sont
  affichés comme des orages.
- **En plaine** (aucun relief au-dessus de 1 500 m autour du lieu), la neige est comptée sur le lieu et non à 2 000 m,
  et le verglas n'est cherché que jusqu'au plus haut point des environs.

Comment l'altitude est calculée :

- **Température et vent en altitude** : entre la température à 2 m du lieu et celles des niveaux de pression du
  modèle (925, 850, 700, 600 et 500 hPa, dont Open-Meteo donne l'altitude réelle), par interpolation ; au-delà,
  −0,65 °C par 100 m. Ressenti en altitude : formule du refroidissement éolien d'Environnement Canada.
- **Limite pluie-neige** : 300 m sous l'iso 0 °C. **Neige à une altitude** : la pluie prévue sur le lieu, quand
  elle tombe au-dessus de la limite pluie-neige (1 mm d'eau ≈ 1 cm de neige fraîche).
- **Verglas** : pluie verglaçante, ou routes mouillées par la pluie des 12 dernières heures quand la température
  retombe sous 0,5 °C, sur le lieu ou jusqu'à 1 200 m au-dessus.
- **Coupe du relief** : sommets nommés d'OpenStreetMap (API Overpass, sans clé) et altitudes du terrain
  (modèle Copernicus à 90 m, via l'API d'altitude d'Open-Meteo), téléchargées une seule fois par lieu.

Thème clair et sombre, identité « Glacier » (voir `design/`), cache local (pas de nouvel appel pendant 30 min,
affichage hors ligne).

## Radar Météo-France : la clé

Le radar vient de l'API publique « DonneesPubliquesRadar » de Météo-France : gratuite, mais avec une clé personnelle.

1. Créer un compte sur [portail-api.meteofrance.fr](https://portail-api.meteofrance.fr).
2. S'abonner gratuitement à « DonneesPubliquesRadar » (le radar), à « PrevisionImmediatePrecipitations »
   (sa prévision à 3 h) et à « DonneesPubliquesPaquetObservation » (les relevés des stations sur la carte de
   température).
3. **Après** les abonnements, générer une clé de type « API Key » avec la durée maximale (3 ans) :
   une clé ne donne accès qu'aux API souscrites au moment où elle est créée.
4. Coller la clé dans l'app : écran radar (bouton clé) ou Réglages. Elle reste sur le téléphone.

À savoir :

- Météo-France ne fournit que **la dernière image**, toutes les 5 minutes (lame d'eau, maille de 500 m).
  L'app garde les images reçues pendant 2 heures : l'animation se remplit au fil des consultations,
  et l'écran radar vérifie s'il y a une nouvelle image toutes les 2 minutes tant qu'il est ouvert.
- Chaque nouvelle image pèse environ 2 Mo, soit environ 24 Mo par heure d'écran radar ouvert.
- **Prévision** : après « maintenant », la frise continue dans le futur.
  - Avec l'abonnement gratuit à l'API « PrevisionImmediatePrecipitations » (PIAF) dans la même clé :
    la prévision immédiate officielle de Météo-France (extrapolation radar fusionnée avec le modèle
    AROME-PI), jusqu'à +3 h par pas de 15 min, sur une zone d'environ 450 × 450 km autour du lieu.
  - Sinon, en secours : l'app mesure le déplacement des pluies entre deux images récentes (méthode TREC)
    et le prolonge sur 1 heure. Il faut deux images à 5–30 min d'écart ; les averses qui naissent ou se
    dissipent ne sont pas prévues.
- Fond de carte : [OpenFreeMap](https://openfreemap.org) (données OpenStreetMap), gratuit et sans clé.

## Coût

Aucun. Le radar Météo-France et le fond de carte OpenFreeMap sont gratuits. L'API gratuite d'Open-Meteo
accepte jusqu'à 10 000 appels par jour pour un usage non commercial ; l'app en fait quelques-uns par lieu consulté
(une requête lourde compte pour plusieurs appels), un seul pour la météo de tous les favoris, et réutilise son cache
pendant 30 minutes. La coupe du relief n'est téléchargée qu'une fois par lieu. Si l'app devenait commerciale
(vente, publicité), il faudrait un abonnement Open-Meteo.

## Développement

Kotlin, Jetpack Compose, Gradle (wrapper inclus). Il faut un JDK 17+ et le SDK Android.

```sh
./gradlew testDebugUnitTest   # tests
./gradlew assembleRelease     # APK signé dans app/build/outputs/apk/release/
```

Organisation du code (`app/src/main/java/io/github/pbailly/meteo/`) :

- `data/` : appels Open-Meteo et lecture des réponses (`OpenMeteoApi`), cache (`WeatherRepository`),
  favoris (`PlacesStore`), position (`DeviceLocation`) ; météo en altitude, limite pluie-neige, neige, verglas et
  fenêtres de beau temps (`Altitude`), alertes et notes des lieux (`Outlook`), coupe du relief (`Terrain`, avec
  Overpass pour les sommets).
- `data/maps/` : couches des cartes (`MapLayers`) et relevés des stations (`Observations` : Météo-France,
  MétéoSuisse, METAR, répartition des pastilles selon le zoom).
- `data/radar/` : API radar Météo-France et stockage des images (`MeteoFranceRadar`), lecture des fichiers
  HDF5 (`OdimDecoder`, avec jhdf), projection stéréographique polaire (`PolarStereographic`) et conversion
  en image Web Mercator colorée (`MercatorLookup`, `RainClass`) ; prévision PIAF via WCS (`MeteoFrancePiaf`,
  lecteur `GeoTiff`) et extrapolation de secours (`Nowcast`).
- `ui/` : navigation et barre du bas (`MeteoApp`, `Glacier`), onglets (`TodayScreen`, `DaysScreen`,
  `AltitudeScreen`, `radar/RadarScreen`, `PlacesScreen`), détail d'un jour (`DayDetailScreen`), météogrammes
  (`MeteogramsScreen`, `DaysTab`, `MeteogramTab`, `MultiModelTab`), réglages (`SettingsScreen`), pictogrammes
  dessinés au trait (`WeatherIcon`), outils de graphique (`ChartKit`), carte MapLibre
  (`radar/RadarMap`).
- `ui/theme/` : couleurs (`Theme`) et polices Archivo et Martian Mono (`Type`, fichiers dans `res/font/`).

La clé de signature `app/meteo-perso.jks` est volontairement dans le dépôt pour que chaque APK compilé
puisse mettre à jour le précédent. C'est adapté à un usage personnel, pas à une publication sur le Play Store.

Données : Open-Meteo.com (licence CC BY 4.0) ; radar et relevés : Météo-France (Licence Ouverte Etalab) ;
relevés : MétéoSuisse (données ouvertes), NOAA Aviation Weather Center (METAR, domaine public) ;
relief : Copernicus DEM (via Open-Meteo), sommets © contributeurs OpenStreetMap (ODbL) ;
fond de carte : OpenFreeMap, © OpenStreetMap ; polices : Archivo et Martian Mono (SIL Open Font License 1.1,
`licences/Archivo-OFL.txt` et `licences/MartianMono-OFL.txt`).
