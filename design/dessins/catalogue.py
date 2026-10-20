"""Assemble les dessins de Dessins.kt en un PDF : une page de titre, puis les combinaisons par section, deux par ligne.

    python3 catalogue.py build/dessins coloriages.pdf

Demande reportlab et Pillow (pip install reportlab pillow).
"""
import sys
from pathlib import Path

from PIL import Image
from reportlab.lib.colors import HexColor
from reportlab.lib.pagesizes import A4
from reportlab.pdfgen import canvas

INK = HexColor("#0F2133")
SOFT = HexColor("#5A6B7C")
ACCENT = HexColor("#E8711C")
MARGIN = 36
GAP = 14
COLUMNS = 2
IMAGE_RATIO = 287 / 411


def main(source: str, target: str) -> None:
    folder = Path(source)
    rows = [line.split("\t") for line in (folder / "catalogue.tsv").read_text(encoding="utf-8").splitlines() if line]
    width, height = A4
    pdf = canvas.Canvas(target, pagesize=A4)
    pdf.setTitle("Météo – les coloriages d'Aujourd'hui")

    # Page de titre : ce qui décide du dessin.
    pdf.setFillColor(INK)
    pdf.setFont("Helvetica-Bold", 26)
    pdf.drawString(MARGIN, height - 110, "Les coloriages d'Aujourd'hui")
    pdf.setFont("Helvetica", 13)
    pdf.setFillColor(SOFT)
    pdf.drawString(MARGIN, height - 134, f"{len(rows)} combinaisons dessinées avec le code de l'appli")
    lines = [
        ("1. Le relief", "plaine, basse montagne (sommets sous 1 200 m), moyenne montagne (jusqu'à 2 500 m, ou un lieu "
         "déjà à 700 m), haute montagne (jusqu'à 3 500 m), très haute montagne, ou au sommet. Il décide des couleurs : "
         "champs et prés, feuillus, sapins jusqu'à la limite des arbres, alpages, roche, glaciers, neige du mois."),
        ("2. L'eau", "un lac, la mer, une rivière (avec son pont au village), une île (la mer des deux côtés, parfois un phare)."),
        ("3. Le lieu habité", "grande ville (immeubles, tour, grue, fenêtres allumées la nuit), petite ville, village "
         "(clocher comtois ou à flèche), hameau, ferme isolée, chalet, refuge au-dessus des arbres."),
        ("4. La forêt", "une forêt toute proche dessine un bois serré : feuillus en plaine, sapins en montagne."),
        ("5 à 8. Le temps", "chaque ciel en trois versions (une autre chaque jour, et à chaque fois qu'on touche le dessin), "
         "plus ou moins fort selon le code météo : bruine, pluie faible à forte, verglaçante, averses, neige en petits ou "
         "gros flocons, en grains, orage simple, double ou sous un cumulonimbus, brouillard de vallée, mer de nuages."),
        ("6 bis. Les nuages", "à leur hauteur d'après la prévision de l'heure : les nuages bas coiffent les sommets (ou "
         "font une mer de nuages sous le sommet), les moyens restent à leur place, les hauts ne sont qu'un voile ou des cirrus."),
        ("9. Les surprises", "fêtes et petits clins d'œil, comme avant."),
        ("10. La lumière", "aube et crépuscule violets, heure dorée au lever et au coucher : le soleil bas passe derrière "
         "le relief, à gauche le matin, à droite le soir."),
        ("11. Le vent, l'air", "arbres et fumée penchés, manche à air tendue, pluie chassée ; neige au sol (toits blancs, "
         "bonhomme), lacs gelés par un froid durable, sable du Sahara (ciel jaune), pollution (lointain voilé)."),
        ("12. Bêtes et lieux", "vaches à l'alpage, chamois, mouettes ; télésiège et pistes des stations de ski ; tour "
         "Eiffel, Fourvière, Notre-Dame de la Garde, cathédrale de Strasbourg, jet d'eau de Genève."),
        ("13. Les couleurs", "discret, normal ou coloré (Réglages) ; dans l'appli, la pluie et la neige tombent et les "
         "nuages glissent (animation, réglable)."),
        ("14. Thème sombre", "et les cas où l'appli ne connaît pas encore les environs, ou pas le relief."),
    ]
    y = height - 190
    indent = MARGIN + 110
    for title, text in lines:
        pdf.setFillColor(ACCENT)
        pdf.setFont("Helvetica-Bold", 11)
        pdf.drawString(MARGIN, y, title)
        pdf.setFillColor(INK)
        pdf.setFont("Helvetica", 10.5)
        line = ""
        for word in text.split(" "):
            candidate = (line + " " + word).strip()
            if pdf.stringWidth(candidate, "Helvetica", 10.5) > width - MARGIN - indent:
                pdf.drawString(indent, y, line)
                y -= 15
                line = word
            else:
                line = candidate
        pdf.drawString(indent, y, line)
        y -= 22
    pdf.setFillColor(SOFT)
    pdf.setFont("Helvetica-Oblique", 9.5)
    pdf.drawString(MARGIN, y - 14, "Le texte de l'écran (lieu, température, ciel) est simulé par-dessus, pour juger de la lisibilité.")
    pdf.drawString(MARGIN, y - 28, "Sous chaque dessin : la légende, puis ce que l'appli a choisi (relief · décor · version du ciel).")
    pdf.showPage()

    cell = (width - 2 * MARGIN - GAP * (COLUMNS - 1)) / COLUMNS
    image_h = cell * IMAGE_RATIO
    block = image_h + 34
    section = None
    y = height - MARGIN
    column = 0
    for section_name, image, caption, detail in rows:
        if section_name != section:
            if column:
                y -= block
                column = 0
            if y - 30 - block < MARGIN:
                pdf.showPage()
                y = height - MARGIN
            pdf.setFillColor(INK)
            pdf.setFont("Helvetica-Bold", 15)
            pdf.drawString(MARGIN, y - 16, section_name)
            y -= 30
            section = section_name
        if column == 0 and y - block < MARGIN:
            pdf.showPage()
            y = height - MARGIN
        x = MARGIN + column * (cell + GAP)
        # En JPEG : un PDF quatre fois plus léger, sans différence visible à cette taille.
        jpeg = folder / (Path(image).stem + ".jpg")
        picture = Image.open(folder / image).convert("RGB")
        picture.resize((820, round(820 * picture.height / picture.width)), Image.LANCZOS).save(jpeg, quality=85)
        pdf.drawImage(str(jpeg), x, y - image_h, width=cell, height=image_h)
        pdf.setStrokeColor(HexColor("#D5DEE6"))
        pdf.rect(x, y - image_h, cell, image_h, stroke=1, fill=0)
        pdf.setFillColor(INK)
        pdf.setFont("Helvetica", 8.5)
        pdf.drawString(x, y - image_h - 11, caption[:70])
        pdf.setFillColor(SOFT)
        pdf.setFont("Helvetica", 7.5)
        pdf.drawString(x, y - image_h - 22, detail[:80])
        column += 1
        if column == COLUMNS:
            column = 0
            y -= block
    pdf.save()


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
