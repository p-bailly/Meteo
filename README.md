# Météo

Application Android de météo, sans publicité, inspirée de Meteoblue. Les prévisions viennent de
[Open-Meteo](https://open-meteo.com), gratuit pour un usage personnel.

## Installer sur le téléphone

1. Sur le téléphone, ouvrir :
   **https://github.com/p-bailly/Meteo/releases/latest/download/meteo.apk**
2. Ouvrir le fichier téléchargé. Android demande d'autoriser l'installation depuis le navigateur
   (« sources inconnues ») : accepter pour cette fois.
3. Pour mettre à jour : Réglages → Application → « Rechercher une mise à jour », puis « Télécharger et
   installer » (la première fois, Android demande d'autoriser Météo à installer des applis). Ou refaire la même
   chose qu'au 1. La nouvelle version s'installe par-dessus l'ancienne et garde les favoris.

L'APK est recompilé automatiquement par GitHub Actions à chaque modification du code, et publié
seulement depuis la branche par défaut du dépôt (`.github/workflows/apk.yml`).

## Ce que fait l'app

Cinq onglets dans la barre du bas, ou en glissant d'une page à l'autre (sauf sur la carte, où le glissement déplace
la carte) :

- **Aujourd'hui**, sur un seul écran sans défiler : le lieu (son nom en entier, qui rapetisse s'il est long), son
  département et son altitude, l'étoile des favoris
  (pleine et orange pour un favori), la température en grand avec le ressenti juste en dessous, le ciel en toutes
  lettres (sans pictogramme), les extrêmes du jour (maxi en orange, mini en bleu) ; en fond, un **dessin aux crayons de couleur** du lieu tel qu'il
  est, puis du temps qu'il fait : le ciel se place entre les écritures, le relief, le village, la rivière ou les champs
  dans la bande libre sous le texte, sans monter derrière le ciel en toutes lettres ni les extrêmes ; le coloriage ne
  touche jamais le texte (chaque écriture est mesurée à l'écran, le crayon s'arrête quelques dp avant, d'une gomme
  adoucie, et le soleil, la lune, les nuages ou le ballon se poussent à côté). Rien n'est inventé :
  sur un sommet (le Mont Blanc), la coupe regarde vers un autre sommet et le relief est en dessous ; en mer ou au milieu
  d'un lac, de l'eau, un voilier et des mouettes ; la ville est posée au niveau du lieu (le sol est aplani sous elle,
  Valence reste dans sa plaine), et le terrain est colorié sur toute la largeur. Quand OpenStreetMap ne répond pas, la
  taille de la ville vient du géocodeur d'Open-Meteo (sa population) ; sans rien, pas de village au hasard, seulement
  ce que le relief fait pousser. D'abord **où l'on est**, d'après la coupe du terrain vers le sommet qui domine le lieu :
  en plaine (champs en parcelles selon la saison, haies), en basse montagne (sommets sous 1 200 m : croupes de feuillus,
  vertes, rousses à l'automne, nues l'hiver), en moyenne montagne (jusqu'à 2 500 m, ou un lieu déjà à 700 m : prés,
  sapins, alpages), en haute montagne (jusqu'à 3 500 m : forêt, roche, neige), en très haute montagne (aiguilles,
  glaciers en toute saison) ou **au sommet** (le relief en dessous, la croix au premier plan) ; la limite des arbres et
  la neige du mois descendent vers le Grand Nord, et les saisons s'inversent dans l'hémisphère sud. Puis **ce qui
  entoure le lieu**, d'après OpenStreetMap : un lac, la mer, une rivière (et son pont), une île (la mer des deux côtés),
  un phare seulement là où il y en a un vrai, un port (quai et bateaux amarrés), une forêt toute proche, des vignes, un
  verger, un château ou des ruines sur une butte, une abbaye, des éoliennes qui tournent avec le vent, la voie ferrée
  et son train qui passe ; la lavande en fleur en Provence l'été, les tournesols du Sud-Ouest ; et le lieu habité : grande ville (immeubles, tour, parfois une grue,
  fenêtres allumées la nuit), petite ville, village (clocher comtois ou à flèche), hameau, ferme isolée, chalet, ou
  refuge au-dessus des arbres. Le relief est en **perspective** (le près s'étale et grandit, le loin se resserre), et une
  **deuxième chaîne**, vue dans une autre direction, se dessine pâle derrière lui (le Massif central depuis Valence).
  Les sommets nommés d'OpenStreetMap sont **écrits au crayon** au-dessus de la crête (« Mont Vouillot »), deux au plus,
  jamais sur le texte de l'écran ni dans les nuages ; quand Overpass ne répond pas, leur nom vient de Photon (le
  géocodeur d'OpenStreetMap, rapide), et l'appli réessaie tant qu'aucun sommet n'est nommé. On bâtit **comme dans la
  région du monde** : église (clocher comtois ou flèche) en Europe et en Amérique du Nord, bulbes orthodoxes en Russie,
  dans les Balkans et en Grèce, églises coloniales à deux clochers en Amérique latine, mosquée et minaret et maisons à
  toit plat du Maroc à l'Indonésie, temples hindous en Inde, stupas dorés en Asie du Sud-Est, pagodes et toits aux
  coins relevés en Asie de l'Est ; dans les déserts chauds (Sahara, Arabie, sud-ouest américain, Australie…), du
  sable et des dunes plutôt que des prés, des palmiers, des dromadaires ; Noël, Pâques et Halloween seulement là où
  on les fête, le 14 Juillet, le muguet et le poisson d'avril seulement en France. Et des monuments du monde : Burj
  Khalifa, la grande mosquée d'Abou Dabi et d'Istanbul, Saint-Basile de Moscou, la tour de Tokyo, le Taj Mahal, les
  pyramides, l'Opéra de Sydney, le Christ de Rio.
  **Chez nous**, au plus juste : le clocher comtois dans toute la Franche-Comté (village, petite ville ou ville), la
  ferme à tuyé sur les plateaux du Haut-Doubs, la ferme bressane à colombages en plaine (Asnans-Beauvoisin), le temple et
  la grande ferme du Jura suisse (La Brévine, son lac des Taillères), l'église au campanile de fer, les pins parasols et
  les cyprès de Provence (La Londe-les-Maures, sa mer, son port, ses vignes et les îles d'Hyères) ; Morteau et le
  château Pertusier, Valence et Saint-Apollinaire face aux ruines de Crussol, Besançon et sa citadelle au-dessus de la
  vieille ville. Les villes européennes de moins de 250 000 habitants sont des vieilles villes aux toits de tuiles. Ce
  qu'on sait de ces lieux (la taille de la ville, la rivière, le lac, la mer, le train) complète OpenStreetMap et
  dessine tout de suite, même sans sa réponse.
  Enfin **le temps**, chaque ciel en trois versions et plus ou moins fort selon la
  prévision : le **soleil et la lune à leur vraie place** (à gauche, au milieu ou à droite selon la direction où l'on
  regarde, plus ou moins haut selon l'heure ; la lune couchée ne se dessine pas), la lune dans sa vraie phase et étoiles (ou la Grande Ourse, ou la Voie lactée), éclaircies, ciel
  couvert, les nuages à leur hauteur d'après la prévision (les nuages bas coiffent les sommets, ou font une mer de
  nuages sous le sommet ; les hauts ne sont qu'un voile ou des cirrus), brouillard (ou brouillard de vallée), bruine, pluie faible à forte (en traits,
  chassée par le vent, ou en gouttes et flaques), pluie verglaçante, averses, neige en petits ou gros flocons ou en
  grains, orage (un ou deux éclairs, ou un cumulonimbus), grêle (les grêlons rebondissent). Le **ciel est colorié**
  d'un bord à l'autre, autour du texte : bleu de beau temps qui pâlit vers l'horizon, gris des jours de pluie, bleu de
  la nuit. Les couleurs sont franches et chatoient : chaque étage du relief croise deux crayons (le vert tendre sur les
  prés, le vert sombre sur les sapins, le roux et le rouge à l'automne), les façades sont crème, ocre ou rose, le
  soleil et la lune sont croisés de jaune. Et ce que l'heure y ajoute : la lumière de l'aube, de l'heure
  dorée (tout le ciel en dégradé, du violet en haut au rose, à l'orange et au jaune sur l'horizon, une lueur autour du
  soleil bas qui passe derrière le relief) et du crépuscule (du bleu de la nuit au rose et à l'orange), les fenêtres qui s'allument peu à peu au crépuscule
  et les réverbères ; la neige sur les montagnes d'après l'isotherme 0 °C de la prévision ; la canicule (soleil rouge,
  air qui tremble au-delà de 32 °C) et le grand froid (givre dans les coins sous −10 °C) ; les aurores boréales quand
  l'activité magnétique (indice Kp de la NOAA) les descend jusqu'au lieu ; les pluies d'étoiles filantes à leurs dates
  (Perséides, Géminides…) ; les arbres en fleurs en avril, les feuilles mortes qui s'envolent à l'automne ; les
  guirlandes et le sapin illuminé en décembre ; le vent qui couche les arbres et la fumée et tend la
  manche à air ; la neige au sol (toits blancs, bonhomme de neige) ; les lacs gelés après des jours sans dégel ; le ciel
  jauni par le sable du Sahara ou voilé par la pollution (qualité de l'air d'Open-Meteo) ; des vaches à l'alpage l'été,
  des chamois dans les rochers, des mouettes au bord de la mer ; le télésiège et les pistes d'une station de ski ; la
  tour Eiffel, Fourvière, Notre-Dame de la Garde, la cathédrale de Strasbourg, le jet d'eau de Genève ou l'Empire State
  Building ; et la **silhouette d'une quarantaine de villes**, à la place de l'église d'une petite ville ou d'un
  village : les cathédrales (Reims, Amiens, Chartres, Rouen, Bourges, Clermont, Cologne…), les beffrois du Nord et de
  Belgique (Lille, Arras, Douai, Calais, Dunkerque, Bruges ; Albi en brique), Saint-Sernin de Toulouse, les remparts de
  Carcassonne, d'Avignon et de Saint-Malo, le Mont-Saint-Michel, Big Ben, la tour de Pise (blanche, bien penchée devant
  le Duomo, sur le pré de la place des Miracles, même avant la réponse d'OpenStreetMap), Saint-Pierre de Rome, la
  Sagrada Família, l'Atomium, les tours de télévision de Berlin et de Toronto. Parfois une
  petite surprise selon le temps ou certains jours de fête — et toucher le dessin en montre une autre version ; un
  **appui long** l'ouvre en grand, en carte postale (« Bons baisers de… », la date, un timbre). La pluie et la neige
  tombent, les nuages glissent, la fumée monte, les éoliennes tournent, le train passe, les bateaux dansent au port, les
  oiseaux et l'avion volent, les aurores ondulent (on peut l'arrêter dans les Réglages, où l'on choisit aussi un dessin
  discret, normal ou coloré). Les combinaisons d'avant ces derniers ajouts : `design/dessins/coloriages.pdf` ;
  vent, rafales, humidité, pression (et sa tendance), **iso 0 °C** actuel (toucher la case ouvre la page Neige &
  altitude), coucher et lever du soleil ; et les **48 prochaines heures** à faire défiler, dans toute la hauteur
  qui reste (pictogramme, courbe de température, probabilité, barres de pluie et millimètres de chaque heure ;
  en grand texte, les heures et les pourcentages ne grandissent presque pas, pour rester lisibles).
- **Prévisions** (14 jours) : pour chaque jour, le pictogramme, la barre mini-maxi (du bleu à l'orange), et l'un
  sous l'autre la pluie (goutte, mm), le vent maximal (flèche dans la direction où il souffle, km/h, en rouge à
  partir de 40), l'ensoleillement (soleil, heures) et l'**indice de confiance** sur 5 (jauge à cinq barres) ; la
  neige attendue s'il en tombe. Toucher un jour ouvre son **détail heure par heure**, présenté de la même façon :
  l'heure, le ciel, la température et le ressenti, puis l'un sous l'autre la pluie et sa probabilité, le vent moyen et
  les rafales, l'indice de confiance.
- **Courbes** (météogrammes) :
  - **7 jours**, heure par heure : température et ressenti, pluie et sa probabilité, nuages, vent moyen et rafales,
    pression ; sur la courbe du vent moyen, une flèche toutes les 6 heures montre où va le vent ; trait orange :
    maintenant ;
  - **14 jours**, en grand, à faire glisser du doigt : pictogramme, indice de confiance,
    mini-maxi, cumul de pluie et vent du jour (flèche de la direction dominante et vitesse maximale) ;
  - **Modèles** : comparaison de 8 modèles avec leur moyenne.

  En très grand texte, les graphiques s'agrandissent avec lui (rangées plus hautes, journées plus larges, dates
  raccourcies si besoin) pour que rien ne se chevauche.
- **Neige & altitude** (depuis la case iso 0 °C d'Aujourd'hui), par jour ou par tranche de 3 heures : une
  coupe du relief de la vallée vers le sommet qui domine le lieu (le Mont Vouillot pour Morteau, le Grand Veymont pour
  Valence) avec la température à chaque altitude, l'iso 0, la limite pluie-neige, les
  sommets nommés, la neige attendue à 2 000 et 3 000 m et les pentes enneigées, puis le ressenti et le vent à
  2 000 m ; ensuite l'iso 0 et la limite pluie-neige jour par jour, avec la neige à 2 000 m.
- **Cartes** : le radar et les autres cartes, avec frise des heures et animation ; le menu posé en haut à droite de la carte choisit la
  couche, le bouton « Webcams » de l'en-tête affiche les webcams ; le lieu est un point noir cerclé de blanc :
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
- **Lieux** : recherche (les lieux les plus importants d'abord : le nom exact, puis les capitales, chefs-lieux et villes les plus peuplées, la France un peu avant le reste du monde), **météo à ma position** (GPS du téléphone, sans les services Google), **choisir sur la
  carte** (toucher un point, ou ailleurs pour le déplacer : il prend le nom de la commune et l'altitude du terrain ;
  l'étoile l'ajoute aux favoris, « Voir la météo » l'ouvre), puis les **favoris**
  (étoile orange) dans l'ordre choisi — appui long sur un lieu puis glisser pour le déplacer — et les **lieux
  consultés récemment**, du plus récent au plus ancien ; chacun avec d'abord son nom (en entier, sur deux lignes s'il
  le faut), puis en petit son altitude et son département, sa température et ses extrêmes. Toucher un lieu trouvé, ou
  « Rechercher » sur le clavier (le premier trouvé), ferme le clavier et ouvre Aujourd'hui. L'étoile de chaque ligne ajoute ou retire un favori. **Comparer** met tous ces lieux côte à côte
  sur 8 jours (pictogramme, maxi et mini, pluie) ; l'engrenage ouvre les **Réglages** : thème (comme le téléphone,
  jour ou nuit), taille du texte (100, 115 ou 130 % : plus grand, certains écrans cachent des détails secondaires),
  dessin d'Aujourd'hui (discret, normal ou coloré, animé ou non), modèles de la comparaison, clés Météo-France et Windy, et la **mise à jour de l'appli** (version installée,
  téléchargement et installation de la dernière version publiée).

**Widgets** de l'écran d'accueil (appui long sur l'écran d'accueil → Widgets → Météo), sur le lieu ouvert dans
l'app, aux couleurs Glacier (clair ou sombre comme les Réglages) ; les toucher ouvre l'app :

- **Maintenant** (2 × 2, de 2 × 1 à plus large) : température, pictogramme et ciel, maxi (orange) et mini (bleu)
  du jour ; en large, le ressenti et l'arrivée de la pluie ;
- **Prévisions** (4 × 2) : le temps actuel puis les prochains jours (pictogramme qui résume la journée, maxi et mini,
  cumul de pluie s'il y a la place), de 3 à 7 jours selon la largeur ; en 4 × 3, une ligne des prochaines heures ;
- **Prochaines heures** (4 × 2) : la phrase qui compte (« Pluie vers 15h · 2,4 mm », « Neige jusqu'à 18h »,
  « Pas de pluie d'ici 24 h ») puis le graphique des 24 prochaines heures (12 en étroit) : pictogrammes,
  courbe de température et barres de pluie ;
- **Dessin** (4 × 2, redimensionnable) : le dessin aux crayons d'Aujourd'hui (le relief et les environs gardés par
  l'app, le temps de l'heure), avec le lieu et la température.

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

Comment l'indice de confiance est calculé :

- **Indice de confiance** (1 à 5) : dispersion des 51 scénarios de la prévision d'ensemble du centre européen
  (ECMWF ENS, via Open-Meteo, rechargée toutes les 3 heures) ; à défaut, des 8 modèles. Pour chaque jour, moitié
  température (écart-type des maxima : sûr sous 0,5 °C, incertain à 4 °C), moitié pluie (scénarios d'accord pour
  « sec » ou « au moins 1 mm », et sur les cumuls s'il pleut) ; 5/5 au-dessus de 80 %, 4/5 de 65 %, 3/5 de 50 %,
  2/5 de 35 %. La confiance baisse donc naturellement avec l'échéance, quand les scénarios s'écartent.

Comment l'altitude est calculée :

- **Température et vent en altitude** : entre la température à 2 m du lieu et celles des niveaux de pression du
  modèle (925, 850, 700, 600 et 500 hPa, dont Open-Meteo donne l'altitude réelle), par interpolation ; au-delà,
  −0,65 °C par 100 m. Ressenti en altitude : formule du refroidissement éolien d'Environnement Canada.
- **Limite pluie-neige** : 300 m sous l'iso 0 °C. **Neige à une altitude** : la pluie prévue sur le lieu, quand
  elle tombe au-dessus de la limite pluie-neige (1 mm d'eau ≈ 1 cm de neige fraîche).
- **Relief et environs du lieu**, pour le dessin d'Aujourd'hui, gardés sur le téléphone ; ceux des **favoris** sont
  préparés en arrière-plan à l'ouverture de l'appli (et dès qu'on ajoute un favori). La première fois, le dessin
  est juste en une seconde : une coupe rapide d'après une grille d'altitudes d'Open-Meteo (100 km de côté, le point qui
  domine le lieu) et la taille de la ville d'après le géocodeur d'Open-Meteo (sa population). OpenStreetMap (Overpass),
  lent (15 à 25 s), complète ensuite, ses deux requêtes en même temps : les sommets nommés, et une requête des environs ;
  un serveur qui ne répond pas en 30 s est laissé pour le suivant, sans réessayer. En attendant, un sol plat, sans
  collines ni village inventés. La ville dessinée grandit avec sa population (plus de maisons, d'étages, d'immeubles). Le lieu habité est le plus grand qui couvre le lieu, une ville s'étendant d'autant plus loin de son centre
  qu'elle est peuplée (Paris sur 8 km, 50 000 habitants sur 2 km, un village sur 700 m) : grande ville à partir de
  50 000 habitants, petite ville à partir de 2 000, village à partir de 150, sinon hameau. Forêt à moins de 1,5 km,
  rivière à moins de 1 km, lac à moins de 2 km, côte à moins de 3 km ; île si le lieu est sur une île de moins de 350 km
  (la Corse, pas la Grande-Bretagne) ; station de ski si des remontées mécaniques ou des pistes sont à moins de 3 km ;
  vignes et vergers à moins de 1,5 km, château et abbaye à moins de 3 km, ruines à moins de 1,5 km, éoliennes à moins
  de 8 km, voie ferrée à moins de 1,5 km, port à moins de 2 km, phare à moins de 5 km. La deuxième chaîne : le point
  qui domine le lieu à au moins 60° de la coupe (300 m plus haut au moins), un appel de plus à l'API d'altitude.
- **Ciel vrai**, pour le dessin : position du soleil et de la lune calculée pour le lieu et l'heure (formules
  astronomiques simplifiées, au degré près) ; aurores visibles à 66° de latitude géomagnétique par temps calme, et
  2,3° plus bas par point d'indice Kp (vers 45° pour Kp 9) ; indice Kp de la NOAA (sans clé), gardé trois heures.
  Neige sur les montagnes : 300 m sous l'isotherme 0 °C (le plus bas des 24 prochaines heures, à 600 m au plus de
  celle du mois ; celui de l'heure quand il tombe quelque chose).
- **Air et neige au sol**, pour le dessin : sable du Sahara à partir de 80 µg/m³ de poussières, air pollué à partir de
  l'indice européen 60 (ou 35 µg/m³ de particules fines), d'après l'API de qualité de l'air d'Open-Meteo (gardée une
  heure) ; neige au sol à partir de 3 cm prévus ; lac gelé quand aujourd'hui et demain restent sous 0 °C après une nuit
  sous −6 °C.
- **Coupe du relief** (aussi pour le dessin d'Aujourd'hui : le modèle de terrain rend les lacs et la mer parfaitement
  plats, ce qui suffit à les reconnaître) : sommets nommés d'OpenStreetMap (API Overpass, sans clé, avec deux serveurs de secours) et
  altitudes du terrain (modèle Copernicus à 90 m, via l'API d'altitude d'Open-Meteo), téléchargées une seule fois
  par lieu. La coupe va vers le sommet qui domine le lieu, cherché jusqu'à 60 km : le plus grand dénivelé rapporté à
  la distance plus 10 km (une colline voisine ne passe pas devant une montagne) ; les sommets traversés sont nommés.

Thème clair et sombre (au choix dans les Réglages), identité « Glacier » (voir `design/`) avec l'orange en couleur d'accent, cache local (pas de
nouvel appel pendant 30 min, affichage hors ligne). Une connexion coupée en plein téléchargement (réseau mobile
instable, erreur « SSL… BAD_DECRYPT ») est retentée deux fois ; si elle échoue encore, l'app affiche un message
simple (« Connexion interrompue, touche ↻ pour réessayer ») au lieu de l'erreur brute.

## Partage avec Nimbus

Nimbus, le launcher de l'utilisateur (paquet `fr.perso.nimbus`), montre sur sa page de déverrouillage la météo du lieu
ouvert dans l'app et le dessin d'Aujourd'hui. Météo les lui donne par un fournisseur de contenu
(`content://io.github.pbailly.meteo.nimbus`), en lecture seule et réservé à Nimbus et à l'app elle-même : toute autre
appli est refusée.

- `now` : le lieu et son département, la température et le ressenti, le ciel en toutes lettres, le maxi et le mini du
  jour, le code météo, jour ou nuit, l'heure de la prévision et si c'est le cache faute de réseau. Ce sont les données
  des widgets : le cache de moins de 30 minutes, sinon un appel à Open-Meteo, attendu 10 secondes au plus.
- `sketch?width=…&height=…&text=…&blank=0` : le dessin en PNG, fond transparent, sur papier clair, dans le style choisi dans
  les Réglages, avec le relief et les environs déjà gardés (rien n'est téléchargé pour lui) ; de 64 à 2048 pixels de
  côté, 1027 × 717 par défaut. `text` dit où Nimbus posera son texte (« gauche,haut,droite,bas » en fractions de
  l'image, séparées par des points-virgules, 24 au plus) : le coloriage s'arrête juste avant, le soleil, la lune, les
  nuages et ce qui vole se mettent à côté. Avec `blank=0` (ce que demande Nimbus), pas de papier blanc autour du
  texte : le ciel passe sous les lettres, et seuls le soleil (rayons compris), la lune, les nuages et ce qui vole
  s'écartent.

À chaque nouvelle prévision, et quand le style du dessin change, Météo prévient Nimbus, qui relit les deux. Rien
d'autre n'est partagé : ni les favoris, ni la position du téléphone.

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
  favoris (`PlacesStore`), position (`DeviceLocation`), nom d'un point (`PlaceNames` : géocodeur du téléphone, à
  défaut Nominatim d'OpenStreetMap), nouvelles tentatives et messages d'erreur réseau
  (`Http`), décompression bzip2 (`Bzip2`), mise à jour de l'appli (`AppUpdate`) ; indice de confiance (`Predictability`) ; météo en altitude, limite pluie-neige et neige (`Altitude`), pictogramme du jour et moment de la pluie
  (`Outlook`), coupe du relief (`Terrain`, avec Overpass pour les sommets).
- `data/maps/` : couches des cartes (`MapLayers`), lecture des fichiers GRIB2 du DWD avec leur compression CCSDS
  (`Grib2`) et relevés des stations (`Observations` : Météo-France, MétéoSuisse, METAR, répartition des pastilles
  selon le zoom).
- `data/radar/` : API radar Météo-France et stockage des images (`MeteoFranceRadar`), lecture des fichiers
  HDF5 (`OdimDecoder`, avec jhdf), projection stéréographique polaire (`PolarStereographic`) et conversion
  en image Web Mercator colorée (`MercatorLookup`, `RainClass`) ; prévision PIAF via WCS (`MeteoFrancePiaf`,
  lecteur `GeoTiff`) et extrapolation de secours (`Nowcast`).
- `ui/` : navigation et barre du bas (`MeteoApp`, `Glacier`), onglets (`TodayScreen`, `DaysScreen`,
  `AltitudeScreen`, `radar/RadarScreen`, `PlacesScreen`), choix d'un lieu sur la carte (`MapPickScreen`), comparaison des lieux (`CompareScreen`), détail d'un jour (`DayDetailScreen`), météogrammes
  (`MeteogramsScreen`, `DaysTab`, `MeteogramTab`, `MultiModelTab`), réglages (`SettingsScreen`), pictogrammes
  dessinés au trait (`WeatherIcon`), dessin au crayon d'Aujourd'hui (`WeatherSketch`, son décor et ses surprises dans `SketchScene` et
  `SketchDecor`, sa carte postale dans `SketchPostcard`), outils de graphique
  (`ChartKit`, dont la jauge de confiance), carte MapLibre
  (`radar/RadarMap`).
- `widget/` : widgets de l'écran d'accueil avec Jetpack Glance (`NowWidget`, `ForecastWidget`, `HoursWidget`, `SketchWidget`),
  chargement et couleurs (`WidgetData`), pictogrammes et graphique dessinés en images (`WidgetDrawing`).
- `share/` : la météo et le dessin donnés à Nimbus (`NimbusProvider`).
- `ui/theme/` : couleurs (`Theme`) et polices Archivo et Martian Mono (`Type`, fichiers dans `res/font/`).

La clé de signature `app/meteo-perso.jks` est volontairement dans le dépôt pour que chaque APK compilé
puisse mettre à jour le précédent. C'est adapté à un usage personnel, pas à une publication sur le Play Store.

Données : Open-Meteo.com (licence CC BY 4.0) ; radar et relevés : Météo-France (Licence Ouverte Etalab) ;
relevés : MétéoSuisse (données ouvertes), NOAA Aviation Weather Center (METAR, domaine public) ;
carte de température : Deutscher Wetterdienst (DWD, données ouvertes, modèle ICON-EU) ;
relief : Copernicus DEM (via Open-Meteo), sommets © contributeurs OpenStreetMap (ODbL) ;
noms des lieux choisis sur la carte ou trouvés par le GPS, sans géocodeur sur le téléphone : Nominatim, © contributeurs OpenStreetMap (ODbL) ;
fond de carte : OpenFreeMap, © OpenStreetMap ; polices : Archivo et Martian Mono (SIL Open Font License 1.1,
`licences/Archivo-OFL.txt` et `licences/MartianMono-OFL.txt`).
