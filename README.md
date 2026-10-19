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
- **Conditions actuelles** : température, ressenti, vent, rafales, humidité, pression, lever et coucher du soleil.
- **Prochaines heures** : bandeau bleu marine façon Meteoblue (heure, pictogramme, probabilité de pluie, température).
- **Heures** : une ligne par heure sur 7 jours, avec la probabilité de pluie en barre, le cumul et le vent.
- **14 jours** : graphique des maxi/mini et des pluies, puis une ligne par jour ; toucher un jour
  affiche le détail toutes les 3 heures.
- **Météogramme** sur 7 jours, heure par heure : pictogrammes, température et ressenti, pluie et neige
  avec probabilité, nuages bas/moyens/hauts, vent et rafales avec direction, pression, nuits grisées.
  Toucher le graphique affiche les valeurs de l'heure.
- **MultiModèle** : comparaison de 8 modèles (Météo-France AROME/ARPEGE, DWD ICON, ECMWF IFS,
  ECMWF AIFS, NOAA GFS, UK Met Office, MeteoSwiss, GEM) pour la température, la pluie cumulée et le vent,
  avec leur moyenne et un tableau par jour.
- **Radar de pluie Météo-France** (bouton radar en haut) : carte plein écran, légende Bruine → Grêle,
  frise des heures et animation. Voir ci-dessous pour la clé.
- Thème clair et sombre, cache local (pas de nouvel appel pendant 30 min, affichage hors ligne).

## Radar Météo-France : la clé

Le radar vient de l'API publique « DonneesPubliquesRadar » de Météo-France : gratuite, mais avec une clé personnelle.

1. Créer un compte sur [portail-api.meteofrance.fr](https://portail-api.meteofrance.fr).
2. Ouvrir l'API « Radar » (DonneesPubliquesRadar) et cliquer « Souscrire gratuitement ».
3. Dans « Mes API », générer une clé de type « API Key » avec la durée maximale (3 ans).
4. Dans l'app, ouvrir le radar et coller la clé (elle reste sur le téléphone ; bouton clé pour la changer).

À savoir :

- Météo-France ne fournit que **la dernière image**, toutes les 5 minutes (lame d'eau, maille de 500 m).
  L'app garde les images reçues pendant 2 heures : l'animation se remplit au fil des consultations,
  et l'écran radar vérifie s'il y a une nouvelle image toutes les 2 minutes tant qu'il est ouvert.
- Chaque nouvelle image pèse environ 2 Mo, soit environ 24 Mo par heure d'écran radar ouvert.
- Pas encore d'images de prévision (les heures futures de Meteoblue) : elles viendraient d'une autre API
  Météo-France (prévision immédiate AROME-PI), pas encore intégrée.
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
  en image Web Mercator colorée (`MercatorLookup`, `RainClass`).
- `ui/` : écrans (`PlacesScreen`, `ForecastScreen`, `radar/RadarScreen`) et onglets (`HourlyTab`, `DaysTab`,
  `MeteogramTab`, `MultiModelTab`), pictogrammes dessinés (`WeatherIcon`), outils de graphique (`ChartKit`),
  carte MapLibre (`radar/RadarMap`).

La clé de signature `app/meteo-perso.jks` est volontairement dans le dépôt pour que chaque APK compilé
puisse mettre à jour le précédent. C'est adapté à un usage personnel, pas à une publication sur le Play Store.

Données : Open-Meteo.com (licence CC BY 4.0) ; radar : Météo-France (Licence Ouverte Etalab) ;
fond de carte : OpenFreeMap, © OpenStreetMap.
