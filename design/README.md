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

## Changer de direction

1. Convertir le SVG choisi en vector drawable pour `app/src/main/res/drawable/ic_launcher_foreground.xml`
   (sans le rectangle de fond), et le calque de `monochrome/` pour `<monochrome>`.
2. Mettre la couleur de fond dans `launcher_background` (`res/values/colors.xml`).
3. Reporter la palette dans `ui/theme/Theme.kt` (schémas Material clair et sombre, `HeaderBlue`) et,
   si besoin, le style des pictogrammes dans `ui/WeatherIcon.kt`.
