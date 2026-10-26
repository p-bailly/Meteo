# Logos et directions artistiques

Direction appliquée à l'app : **Glacier** (variante D1 de la piste Alpin, `logos/alpin-variantes/d1-glacier.svg`) :
neige, glace, glacier `#1C6497`, ardoise, nuit `#0F2133` et balise rouge `#C8202A`, polices Archivo et Martian Mono,
pictogrammes au trait, barre du haut claire, bloc « Altitude ». Les couleurs sont dans `ui/theme/Theme.kt`.
L'icône est le logo au crayon **C5 Montagne** (`logos/crayon/c5-montagne.svg`), qui a remplacé celle de Glacier.

Les SVG de `logos/` suivent la grille de l'icône adaptative Android (108 × 108, motif dans la zone sûre de 66) ;
`logos/monochrome/` est le calque des icônes à thème d'Android 13+ (seul l'alpha compte).

## Pistes non retenues (SVG conservés)

| Piste | Idée | Risque |
|---|---|---|
| 01 **Isobare** (appliquée avant Glacier) | M tracé comme un front météo, marine et bleu/rouge de front | symboles compris des seuls lecteurs de cartes |
| 02 **Girouette** | girouette sur plaque émaillée, laiton, esprit patrimoine | confondue avec une boussole en petit |
| 03 **Radar** | balayage radar aux couleurs de `RainClass`, thème nuit | très marquée, peu adaptée au thème clair |
| 04 **Accent** | « é » dont l'œil est un soleil et l'accent une goutte | loin de l'esprit « outil de passionné » |
| 05 **Degré** | M suivi d'un ° rouge, sobre | ne dit pas « météo » au premier coup d'œil |
| 06 Météogramme | courbe de température sur barres de pluie | évoque une app de bourse |
| 07 Abri | abri à persiennes des stations météo | détails fins à 24 dp |
| 08 Courbes | courbes de niveau d'un sommet, balise rouge | évoque la randonnée |
| 09 Hublot | fenêtre ronde, moitié jour, moitié nuit | assez générique |
| 10 Barbule | pointé synoptique (ciel 4/8, vent en barbules) | illisible pour le grand public |
| 11 Pluviomètre | goutte dans un pluviomètre gradué | peu de place pour soleil et température |

01–05 sont dans `logos/` (variantes d'Isobare dans `isobare-variantes/`) ; 06–11 dans `logos/nouvelles-pistes/`, en palette
Glacier, sans calque monochrome (à faire pour celui qui serait retenu).

## Au crayon (`logos/crayon/`)

C1 soleil, C2 éclaircies, C3 pluie, C4 giboulée, **C5 montagne**, C6 nuit, C7 craie sur bleu glacier. Dessinés comme le fond
d'Aujourd'hui (`ui/WeatherSketch.kt`) : trait repassé deux fois avec un léger tremblé, hachures, crayons jaune et bleu sur papier
crème `#F6F1E6`, graphite `#2E3644`. `python3 generer.py` (tremblé aléatoire mais reproductible) écrit les SVG et, pour le logo
`ICONE` (aujourd'hui C5), les calques `res/drawable/ic_launcher_foreground.xml` et `ic_launcher_monochrome.xml` : ne pas les
modifier à la main.

## Changer de direction

1. Convertir le SVG choisi en vector drawable pour `ic_launcher_foreground.xml` (sans le rectangle de fond) et son calque de
   `monochrome/` pour `<monochrome>`.
2. Mettre la couleur de fond dans `launcher_background` (`res/values/colors.xml`).
3. Reporter la palette dans `ui/theme/Theme.kt` (schémas Material clair et sombre, `HeaderBlue`) et, si besoin, dans `ui/WeatherIcon.kt`.

## Coloriages d'Aujourd'hui (`dessins/`)

`dessins/coloriages.pdf` montre le dessin du haut d'Aujourd'hui dans une centaine de combinaisons (reliefs, eau, lieux habités,
forêt, saisons, ciels et intensités, orages, surprises, thème sombre) sous un faux texte d'écran ; l'outil signale tout dessin qui
toucherait une lettre (« Touche le texte »). Les dessins sont ceux du code de l'appli (`SketchScene.kt`, `SketchDraw.kt`,
`SketchDecor.kt`) compilé pour ordinateur ; les reliefs sont les coupes des tests (`app/src/test/resources/terrain/profiles.json`).
Il écrit aussi dans `build/dessins/nimbus/` des dessins autour du texte de la page de déverrouillage de Nimbus. À refaire après une
modification (Java 21, Python 3 avec reportlab et Pillow) ; les combinaisons sont dans `dessins/src/Dessins.kt` (`catalog`) :

```
./gradlew -p design/dessins run
python3 design/dessins/catalogue.py design/dessins/build/dessins design/dessins/coloriages.pdf
```
