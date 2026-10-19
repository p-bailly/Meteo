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

- **Recherche de lieux** (villes, villages, altitude), **favoris** et **météo à ma position**
  (GPS du téléphone, sans les services Google).
- **Page d'une ville** (sur le modèle de Meteoblue) : température en grand, ciel et indice UV, heure de mise
  à jour, boutons Radar et Météogrammes ; bandeau des heures restantes de la journée (heure, pictogramme,
  probabilité de pluie, température) ; puis les 14 jours : pictogrammes jour et nuit, probabilité de pluie,
  maxi/mini dans des cases colorées, prévisibilité (cibles), vent, fourchette de pluie, ensoleillement.
- **Détail d'un jour** (toucher un jour, puis glisser pour passer aux suivants) : lever et coucher du soleil
  et de la Lune, pression, humidité, puis la prévision heure par heure (ou par 3 h) : température, ressenti,
  vent moyen et rafales, pluie, probabilité et prévisibilité.
- **Météogrammes** : météogramme horaire sur 7 jours, graphique 14 jours avec la prévisibilité, et
  comparaison de 8 modèles (Météo-France AROME/ARPEGE, DWD ICON, ECMWF IFS, ECMWF AIFS, NOAA GFS,
  UK Met Office, MeteoSwiss, GEM) avec leur moyenne.
- **Prévisibilité** : calculée sur l'accord entre ces 8 modèles (écart des températures, accord sur la
  pluie), de 0 à 100 %.
- **Radar de pluie Météo-France** (bouton radar en haut) : carte plein écran, légende Bruine → Grêle,
  frise des heures, animation, et prévision du déplacement des pluies sur 1 heure. Voir ci-dessous pour la clé.
- **Réglages** (menu ⋮) : modèles affichés dans MultiModèle, clé Météo-France.
- Thème clair et sombre, identité « Isobare » (voir `design/`), cache local (pas de nouvel appel pendant 30 min, affichage hors ligne).

## Radar Météo-France : la clé

Le radar vient de l'API publique « DonneesPubliquesRadar » de Météo-France : gratuite, mais avec une clé personnelle.

1. Créer un compte sur [portail-api.meteofrance.fr](https://portail-api.meteofrance.fr).
2. S'abonner gratuitement à « DonneesPubliquesRadar » (le radar) et à « PrevisionImmediatePrecipitations »
   (sa prévision à 3 h).
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
accepte jusqu'à 10 000 appels par jour pour un usage non commercial ; l'app en fait 1 à 2 par lieu consulté, et réutilise son cache pendant 30 minutes. Si l'app devenait
commerciale (vente, publicité), il faudrait un abonnement Open-Meteo.

## Développement

Kotlin, Jetpack Compose, Gradle (wrapper inclus). Il faut un JDK 17+ et le SDK Android.

```sh
./gradlew testDebugUnitTest   # tests
./gradlew assembleRelease     # APK signé dans app/build/outputs/apk/release/
```

Organisation du code (`app/src/main/java/io/github/pbailly/meteo/`) :

- `data/` : appels Open-Meteo et lecture des réponses (`OpenMeteoApi`), cache (`WeatherRepository`),
  favoris (`PlacesStore`), position (`DeviceLocation`).
- `data/radar/` : API radar Météo-France et stockage des images (`MeteoFranceRadar`), lecture des fichiers
  HDF5 (`OdimDecoder`, avec jhdf), projection stéréographique polaire (`PolarStereographic`) et conversion
  en image Web Mercator colorée (`MercatorLookup`, `RainClass`) ; prévision PIAF via WCS (`MeteoFrancePiaf`,
  lecteur `GeoTiff`) et extrapolation de secours (`Nowcast`).
- `ui/` : écrans (`PlacesScreen`, `ForecastScreen`, `SettingsScreen`, `radar/RadarScreen`) et onglets (`HourlyTab`, `DaysTab`,
  `MeteogramTab`, `MultiModelTab`), pictogrammes dessinés au trait (`WeatherIcon`), barre du haut et bande de fronts
  (`NavyTopBar`), outils de graphique (`ChartKit`), carte MapLibre (`radar/RadarMap`).
- `ui/theme/` : couleurs (`Theme`) et polices IBM Plex Sans et Mono (`Type`, fichiers dans `res/font/`).

La clé de signature `app/meteo-perso.jks` est volontairement dans le dépôt pour que chaque APK compilé
puisse mettre à jour le précédent. C'est adapté à un usage personnel, pas à une publication sur le Play Store.

Données : Open-Meteo.com (licence CC BY 4.0) ; radar : Météo-France (Licence Ouverte Etalab) ;
fond de carte : OpenFreeMap, © OpenStreetMap ; polices : IBM Plex (SIL Open Font License 1.1,
`licences/IBM-Plex-OFL.txt`).
