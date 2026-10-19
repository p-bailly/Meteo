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

L'APK est recompilé automatiquement par GitHub Actions à chaque modification du code
(`.github/workflows/apk.yml`).

## Ce que fait l'app

- **Recherche de lieux** (villes, villages, altitude), **favoris** et **météo à ma position**
  (GPS du téléphone, sans les services Google).
- **Conditions actuelles** : température, ressenti, vent, rafales, humidité, pression, lever et coucher du soleil.
- **14 jours** : graphique des maxi/mini et des pluies, puis une ligne par jour ; toucher un jour
  affiche le détail toutes les 3 heures.
- **Météogramme** sur 7 jours, heure par heure : pictogrammes, température et ressenti, pluie et neige
  avec probabilité, nuages bas/moyens/hauts, vent et rafales avec direction, pression, nuits grisées.
  Toucher le graphique affiche les valeurs de l'heure.
- **MultiModèle** : comparaison de 8 modèles (Météo-France AROME/ARPEGE, DWD ICON, ECMWF IFS,
  ECMWF AIFS, NOAA GFS, UK Met Office, MeteoSwiss, GEM) pour la température, la pluie cumulée et le vent,
  avec leur moyenne et un tableau par jour.
- Thème clair et sombre, cache local (pas de nouvel appel pendant 30 min, affichage hors ligne).

## Coût

Aucun. L'API gratuite d'Open-Meteo accepte jusqu'à 10 000 appels par jour pour un usage non commercial ;
l'app en fait 1 à 2 par lieu consulté, et réutilise son cache pendant 30 minutes. Si l'app devenait
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
- `ui/` : écrans (`PlacesScreen`, `ForecastScreen`) et onglets (`DaysTab`, `MeteogramTab`, `MultiModelTab`),
  pictogrammes dessinés (`WeatherIcon`), outils de graphique (`ChartKit`).

La clé de signature `app/meteo-perso.jks` est volontairement dans le dépôt pour que chaque APK compilé
puisse mettre à jour le précédent. C'est adapté à un usage personnel, pas à une publication sur le Play Store.

Données : Open-Meteo.com, licence CC BY 4.0.
