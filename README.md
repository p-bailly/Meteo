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

- **Aujourd'hui**, sur un seul écran sans défiler : le lieu, son département et son altitude, l'étoile des favoris
  (pleine et orange pour un favori), la température en grand avec le ressenti juste en dessous, le ciel, les
  extrêmes du jour (maxi en orange, mini en bleu) et leur **écart aux normales** de saison (« +2° vs normales »,
  moyennes 1991-2020) ; en fond, un **dessin au crayon** du temps qu'il fait (soleil, lune et étoiles, nuages, pluie,
  neige, orage ou brouillard au-dessus des montagnes) ; de la mi-octobre à la mi-mars, une ligne **Neige & altitude**
  (avec l'iso 0 °C actuel) qui ouvre la page du même nom ; vent, rafales, humidité, pression (et sa tendance),
  indice UV, coucher et lever du soleil ; et les **48 prochaines heures** à faire défiler, dans toute la hauteur
  qui reste (pictogramme, courbe de température, probabilité et barres de pluie).
- **Prévisions** (14 jours) : pour chaque jour, le pictogramme, la barre mini-maxi (du bleu à l'orange), et l'un
  sous l'autre la pluie (goutte, mm), le vent maximal (flèche dans la direction où il souffle, km/h, en rouge à
  partir de 40), l'ensoleillement (soleil, heures) et l'**indice de confiance** sur 5 (jauge à cinq barres) ; la
  neige attendue s'il en tombe. Toucher un jour ouvre son **détail heure par heure**.
- **Météogramme** :
  - **7 jours**, heure par heure : température et ressenti, pluie et sa probabilité, nuages, vent moyen et rafales,
    pression ; sur la courbe du vent moyen, une flèche toutes les 6 heures montre où va le vent ; trait orange :
    maintenant ;
  - **14 jours**, en grand, à faire glisser du doigt : pictogramme, indice de confiance,
    mini-maxi, cumul de pluie et vent du jour (flèche de la direction dominante et vitesse maximale) ;
  - **Modèles** : comparaison de 8 modèles avec leur moyenne.
- **Neige & altitude** (depuis la ligne du même nom dans Aujourd'hui), par jour ou par tranche de 3 heures : une
  coupe du relief vallée → sommets avec la température à chaque altitude, l'iso 0, la limite pluie-neige, les
  sommets nommés, la neige attendue à 2 000 et 3 000 m et les pentes enneigées, puis le ressenti et le vent à
  2 000 m ; ensuite l'iso 0 et la limite pluie-neige jour par jour, avec la neige à 2 000 m.
- **Radar** : les cartes, avec frise des heures et animation :
  - **Pluie** : radar Météo-France et sa prévision (voir ci-dessous pour la clé) ;
  - **Température** à 2 m maintenant : modèle ICON-EU du DWD (sans clé, fichier GRIB2 d'environ 1 Mo du serveur de
    données ouvertes du DWD, plus fiable que son serveur de cartes), colorée comme les cases de température de
    l'app, avec les **vrais relevés** des stations en pastilles : stations Météo-France avec la clé (environ 1 800),
    stations de MétéoSuisse et aéroports (METAR) sans clé. Toucher une pastille affiche toutes les mesures de la
    station (humidité, point de rosée, vent, rafales, pression, pluie, neige au sol, visibilité…) et sa température
    des dernières 24 heures (Météo-France et aéroports) ;
  - **Satellite** : Meteosat de 3e génération (EUMETSAT, sans clé), une image toutes les 10 min sur 2 h,
    vraies couleurs le jour, nuages et lumières des villes la nuit ;
  - **Foudre** : imageur d'éclairs de Meteosat (EUMETSAT, sans clé), éclairs par 5 min sur la dernière heure ;
  - **Webcams** autour du lieu : Windy, avec une clé gratuite (api.windy.com) à saisir dans les Réglages ;
    toucher un point affiche l'image sur toute la largeur et le lien vers la page Windy.
- **Lieux** : recherche, **météo à ma position** (GPS du téléphone, sans les services Google), puis les **favoris**
  (étoile orange) dans l'ordre choisi — appui long sur un lieu puis glisser pour le déplacer — et les **lieux
  consultés récemment**, du plus récent au plus ancien ; chacun avec son département, son altitude, sa température
  et ses extrêmes. L'étoile de chaque ligne ajoute ou retire un favori. **Comparer** met tous ces lieux côte à côte
  sur 8 jours (pictogramme, maxi et mini, pluie) ; l'engrenage ouvre les **Réglages** : thème (comme le téléphone,
  jour ou nuit), taille du texte (100, 115 ou 130 % : plus grand, certains écrans cachent des détails secondaires),
  modèles de la comparaison, clés Météo-France et Windy.

**Widgets** de l'écran d'accueil (appui long sur l'écran d'accueil → Widgets → Météo), sur le lieu ouvert dans
l'app, aux couleurs Glacier (clair ou sombre comme les Réglages) ; les toucher ouvre l'app :

- **Maintenant** (2 × 2, de 2 × 1 à plus large) : température, pictogramme et ciel, maxi (orange) et mini (bleu)
  du jour ; en large, le ressenti et l'arrivée de la pluie ;
- **Prévisions** (4 × 2) : le temps actuel puis les prochains jours (pictogramme qui résume la journée, maxi et mini,
  cumul de pluie s'il y a la place), de 3 à 7 jours selon la largeur ; en 4 × 3, une ligne des prochaines heures ;
- **Prochaines heures** (4 × 2) : la phrase qui compte (« Pluie vers 15h · 2,4 mm », « Neige jusqu'à 18h »,
  « Pas de pluie d'ici 24 h ») puis le graphique des 24 prochaines heures (12 en étroit) : pictogrammes,
  courbe de température et barres de pluie.

Ils se mettent à jour toutes les 30 minutes (le minimum d'Android) et à chaque téléchargement dans l'app ; ils
réutilisent son cache, donc n'ajoutent un appel à Open-Meteo que si l'app n'a pas été ouverte entre-temps. Une heure
compte comme pluvieuse à partir de 0,2 mm.

Comment la prévision est résumée :

- **Pictogramme d'un jour (ou de 3 heures)** : le temps qui domine, et non le pire moment comme le code journalier
  d'Open-Meteo (une heure d'orage la nuit en faisait un jour d'orage). Pluie, neige, orage ou verglas seulement s'ils
  durent (au moins 2 heures et 1 mm sur une journée), intensité selon le cumul, sinon le ciel moyen de la journée.
- **Pas de grêle** : les codes « orage avec grêle » d'Open-Meteo ne sont fiables qu'en Europe centrale ; ils sont
  affichés comme des orages.
- **En plaine** (aucun relief au-dessus de 1 500 m autour du lieu), la neige est comptée sur le lieu et non à
  2 000 m.

Comment l'indice de confiance et les normales sont calculés :

- **Indice de confiance** (1 à 5) : dispersion des 51 scénarios de la prévision d'ensemble du centre européen
  (ECMWF ENS, via Open-Meteo, rechargée toutes les 3 heures) ; à défaut, des 8 modèles. Pour chaque jour, moitié
  température (écart-type des maxima : sûr sous 0,5 °C, incertain à 4 °C), moitié pluie (scénarios d'accord pour
  « sec » ou « au moins 1 mm », et sur les cumuls s'il pleut) ; 5/5 au-dessus de 80 %, 4/5 de 65 %, 3/5 de 50 %,
  2/5 de 35 %. La confiance baisse donc naturellement avec l'échéance, quand les scénarios s'écartent.
- **Normales** : températures maximales et minimales 1991-2020 (réanalyse ERA5 d'Open-Meteo, ramenée à l'altitude
  du lieu) sur deux semaines autour de la date, une requête par année (30 en tout, téléchargées une fois par lieu et
  par semaine, pour ménager le quota gratuit d'Open-Meteo). L'écart compare la moyenne du maxi et du mini du jour.

Comment l'altitude est calculée :

- **Température et vent en altitude** : entre la température à 2 m du lieu et celles des niveaux de pression du
  modèle (925, 850, 700, 600 et 500 hPa, dont Open-Meteo donne l'altitude réelle), par interpolation ; au-delà,
  −0,65 °C par 100 m. Ressenti en altitude : formule du refroidissement éolien d'Environnement Canada.
- **Limite pluie-neige** : 300 m sous l'iso 0 °C. **Neige à une altitude** : la pluie prévue sur le lieu, quand
  elle tombe au-dessus de la limite pluie-neige (1 mm d'eau ≈ 1 cm de neige fraîche).
- **Coupe du relief** : sommets nommés d'OpenStreetMap (API Overpass, sans clé) et altitudes du terrain
  (modèle Copernicus à 90 m, via l'API d'altitude d'Open-Meteo), téléchargées une seule fois par lieu.

Thème clair et sombre (au choix dans les Réglages), identité « Glacier » (voir `design/`) avec l'orange en couleur d'accent, cache local (pas de
nouvel appel pendant 30 min, affichage hors ligne). Une connexion coupée en plein téléchargement (réseau mobile
instable, erreur « SSL… BAD_DECRYPT ») est retentée deux fois ; si elle échoue encore, l'app affiche un message
simple (« Connexion interrompue, touche ↻ pour réessayer ») au lieu de l'erreur brute.

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
    AROME-PI), jusqu'à +3 h par pas de 15 min, sur une zone d'environ 450 × 450 km autour du lieu. L'unité des
    valeurs est lue dans la description du service ; une valeur de remplissage ou une échelle invraisemblable
    (toute la zone en « grêle ») est écartée.
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
  favoris (`PlacesStore`), position (`DeviceLocation`), nouvelles tentatives et messages d'erreur réseau
  (`Http`), décompression bzip2 (`Bzip2`) ; indice de confiance (`Predictability`), normales de saison
  (`Climate`) ; météo en altitude, limite pluie-neige et neige (`Altitude`), pictogramme du jour et moment de la pluie
  (`Outlook`), coupe du relief (`Terrain`, avec Overpass pour les sommets).
- `data/maps/` : couches des cartes (`MapLayers`), lecture des fichiers GRIB2 du DWD avec leur compression CCSDS
  (`Grib2`) et relevés des stations (`Observations` : Météo-France, MétéoSuisse, METAR, répartition des pastilles
  selon le zoom).
- `data/radar/` : API radar Météo-France et stockage des images (`MeteoFranceRadar`), lecture des fichiers
  HDF5 (`OdimDecoder`, avec jhdf), projection stéréographique polaire (`PolarStereographic`) et conversion
  en image Web Mercator colorée (`MercatorLookup`, `RainClass`) ; prévision PIAF via WCS (`MeteoFrancePiaf`,
  lecteur `GeoTiff`) et extrapolation de secours (`Nowcast`).
- `ui/` : navigation et barre du bas (`MeteoApp`, `Glacier`), onglets (`TodayScreen`, `DaysScreen`,
  `AltitudeScreen`, `radar/RadarScreen`, `PlacesScreen`), comparaison des lieux (`CompareScreen`), détail d'un jour (`DayDetailScreen`), météogrammes
  (`MeteogramsScreen`, `DaysTab`, `MeteogramTab`, `MultiModelTab`), réglages (`SettingsScreen`), pictogrammes
  dessinés au trait (`WeatherIcon`), dessin au crayon d'Aujourd'hui (`WeatherSketch`), outils de graphique
  (`ChartKit`, dont la jauge de confiance), carte MapLibre
  (`radar/RadarMap`).
- `widget/` : widgets de l'écran d'accueil avec Jetpack Glance (`NowWidget`, `ForecastWidget`, `HoursWidget`),
  chargement et couleurs (`WidgetData`), pictogrammes et graphique dessinés en images (`WidgetDrawing`).
- `ui/theme/` : couleurs (`Theme`) et polices Archivo et Martian Mono (`Type`, fichiers dans `res/font/`).

La clé de signature `app/meteo-perso.jks` est volontairement dans le dépôt pour que chaque APK compilé
puisse mettre à jour le précédent. C'est adapté à un usage personnel, pas à une publication sur le Play Store.

Données : Open-Meteo.com (licence CC BY 4.0) ; radar et relevés : Météo-France (Licence Ouverte Etalab) ;
relevés : MétéoSuisse (données ouvertes), NOAA Aviation Weather Center (METAR, domaine public) ;
carte de température : Deutscher Wetterdienst (DWD, données ouvertes, modèle ICON-EU) ;
relief : Copernicus DEM (via Open-Meteo), sommets © contributeurs OpenStreetMap (ODbL) ;
fond de carte : OpenFreeMap, © OpenStreetMap ; polices : Archivo et Martian Mono (SIL Open Font License 1.1,
`licences/Archivo-OFL.txt` et `licences/MartianMono-OFL.txt`).
