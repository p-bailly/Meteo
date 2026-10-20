# Logos et directions artistiques

Cinq propositions pour remplacer l'ancienne icône (soleil derrière un nuage sur bleu `#256ABF`).
La direction **Glacier** (variante D1 de la piste Alpin, `logos/alpin-variantes/d1-glacier.svg`) est appliquée à
l'app : icône (sommet enneigé au-dessus de l'iso 0 °C, balise rouge), palette neige, glace, glacier, ardoise et nuit,
polices Archivo et Martian Mono, pictogrammes au trait recolorés, barre du haut claire et bloc « Altitude ».
Isobare, appliquée avant elle, et les autres directions restent des pistes.

Les logos de `logos/` sont dessinés sur la grille de l'icône adaptative Android (108 × 108, motif
contenu dans la zone sûre de 66). `logos/monochrome/` contient le calque monochrome utilisé par les
icônes à thème d'Android 13+ (seul l'alpha compte).

| | Direction | Logo | Palette | Typographies |
|---|---|---|---|---|
| 01 | **Isobare** : technique, synoptique, précis | Un M tracé comme un front météo (triangles froids, demi-cercles chauds) | Marine `#0C304E`, encre `#0B1B2B`, papier `#F4F6F8`, front froid `#3FA9F5`, front chaud `#FF5A4E`, grille `#C9D3DD` | IBM Plex Sans, IBM Plex Mono |
| 02 | **Girouette** : patrimoine, émail, clocher | Girouette pointée nord-est sur une plaque émaillée, lettres N E S O | Bleu émail `#1F4A73`, blanc émail `#F4EFE3`, laiton `#D8A846`, ardoise `#2F3A45`, brique `#B5462F`, zinc `#A7B1BA` | Barlow Condensed, Barlow |
| 03 | **Radar** : nocturne, données, temps réel | Balayage radar et écho de pluie en pixels | Nuit `#06090D`, surface `#0E151C`, puis l'échelle du radar : `#7AE1E8` `#00C8D8` `#2D7BEA` `#BE46EB` `#DE5E47` `#E4D541` | Chivo, Chivo Mono |
| 04 | **Accent** : joyeux, typographique, grand public | Un « é » : l'œil de la lettre est un soleil levant, l'accent une goutte | Ciel `#1E6FE8`, soleil `#FFC21A`, nuage `#FFFFFF`, corail `#FF7A59`, nuit `#13233F`, brume `#DDE9FB` | Fredoka, Nunito |
| 05 | **Degré** : minimal, suisse, chiffres | Un M suivi du signe degré rouge | Noir `#121212`, papier `#FAFAF8`, rouge degré `#E8473F`, bleu pluie `#2A78D6`, gris `#8A8984`, filet `#E2E1DC` | Archivo (étroit pour les chiffres) |

## En bref

- **Isobare** prolonge l'identité actuelle (bandeau marine façon Meteoblue) et parle aux amateurs de
  météogrammes et de modèles. C'est le changement le plus sûr. Les symboles de front ne sont compris
  que par ceux qui lisent les cartes météo, mais le M reste lisible jusqu'à 24 dp.
- **Girouette** donne une personnalité française et chaleureuse (plaques de rue émaillées, girouettes
  de clocher). Risque : à petite taille, on peut la confondre avec une icône de boussole.
- **Radar** fait du radar Météo-France la signature de l'app et pousse vers un thème sombre. Les
  couleurs sont celles de la légende radar existante (`RainClass`). Très marqué, moins adapté au
  thème clair.
- **Accent** est la plus accessible et la plus mémorisable. Elle s'éloigne de l'esprit « outil de
  passionné » de Meteoblue.
- **Degré** est la plus sobre : les chiffres d'abord, un seul rouge (déjà la couleur de température
  des graphiques). Le M° est très lisible à petite taille, mais il ne dit pas « météo » au premier coup
  d'œil.

## Nouvelles pistes (`logos/nouvelles-pistes/`)

Six logos de plus, dessinés dans la palette Glacier déjà appliquée à l'app (fond `#1C6497`, glace `#9BCBE9`,
blanc, nuit `#0F2133`, balise rouge `#C8202A`). Choisir l'un d'eux ne change donc que l'icône, pas le thème.
Les calques monochromes restent à faire pour celui qui sera retenu.

| | Piste | Logo | Points forts | Risques |
|---|---|---|---|---|
| 06 | **Météogramme** | Courbe de température au-dessus des barres de pluie, point rouge « maintenant » | Montre la fonction phare de l'app, esprit Meteoblue | Peut évoquer une app de bourse ou de statistiques |
| 07 | **Abri** | L'abri à persiennes blanc des stations météo, sur ses pieds | Original, dit « station météo » à qui en a vu une | Moins parlant pour qui n'en a jamais vu ; détails fins à 24 dp |
| 08 | **Courbes** | Courbes de niveau d'un sommet, balise rouge au point haut | Prolonge le thème montagne et altitude de Glacier | Évoque d'abord la randonnée ou la topographie |
| 09 | **Hublot** | Une fenêtre ronde, moitié jour (soleil), moitié nuit (lune, étoiles) | Très lisible en petit, symétrique, mémorisable | Assez générique (thème clair/sombre, horloge) |
| 10 | **Barbule** | Pointé de station synoptique : ciel à 4/8 couvert, vent en barbules et fanion | Clin d'œil pointu pour les passionnés de cartes | Illisible pour le grand public |
| 11 | **Pluviomètre** | Une goutte tombe dans un pluviomètre gradué, niveau en rouge | Simple, concret, met la pluie au centre | Moins de place pour le soleil et la température |

## Au crayon (`logos/crayon/`)

Les pictogrammes météo classiques (soleil, nuage, pluie, lune), dessinés comme le fond d'Aujourd'hui
(`ui/WeatherSketch.kt`) : chaque trait repassé deux fois avec un léger tremblé, ombres hachurées, crayons de couleur
jaune et bleu sur papier crème `#F6F1E6`, graphite `#2E3644`. Les SVG sont générés par `generer.py` (tremblé tiré
au hasard mais reproductible) : modifier le script puis le relancer.

| | Logo | Remarque |
|---|---|---|
| C1 | **Soleil** | Le plus simple et le plus lisible en petit |
| C2 | **Éclaircies** | Soleil derrière un nuage : l'icône météo par excellence |
| C3 | **Pluie** | Nuage et averse, plus mélancolique |
| C4 | **Giboulée** | Soleil, nuage et pluie : tous les temps à la fois, un peu chargé à 24 dp |
| C5 | **Montagne** | Rappelle le dessin de fond et le thème Glacier (sommet enneigé) |
| C6 | **Nuit** | Lune, étoiles et nuage à la craie sur fond nuit |
| C7 | **Craie** | Éclaircies à la craie blanche sur le bleu glacier actuel : garde la couleur de l'icône |

Les hachures et le tremblé passent en vector drawable Android (ce ne sont que des chemins), mais les fichiers sont
plus lourds que ceux des autres pistes.

## Changer de direction

1. Convertir le SVG choisi en vector drawable pour `app/src/main/res/drawable/ic_launcher_foreground.xml`
   (sans le rectangle de fond), et le calque de `monochrome/` pour `<monochrome>`.
2. Mettre la couleur de fond dans `launcher_background` (`res/values/colors.xml`).
3. Reporter la palette dans `ui/theme/Theme.kt` (schémas Material clair et sombre, `HeaderBlue`) et,
   si besoin, le style des pictogrammes dans `ui/WeatherIcon.kt`.
