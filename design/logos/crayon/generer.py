"""Génère les logos « au crayon » : pictogrammes météo classiques, trait repassé deux fois et un peu tremblé,
hachures, comme le dessin de fond d'Aujourd'hui (ui/WeatherSketch.kt). Lancer : python3 generer.py"""
import math
import os
import random

HERE = os.path.dirname(os.path.abspath(__file__))

PAPIER = "#F6F1E6"
GRAPHITE = "#2E3644"
JAUNE = "#F0B429"
ORANGE = "#E07A2E"
BLEU = "#3E7CB8"
BLEU_CLAIR = "#8BB8DE"
GLACIER = "#1C6497"
NUIT = "#0F2133"
BLANC = "#FFFFFF"


class Crayon:
    def __init__(self, seed):
        self.rnd = random.Random(seed)
        self.parts = []
        self.defs = []
        self.n = 0

    def j(self, a):
        return (self.rnd.random() * 2 - 1) * a

    def trait(self, pts, color, width=2.6, close=False, passes=2, wobble=0.35):
        for p in range(passes):
            q = [(x + self.j(wobble), y + self.j(wobble)) for x, y in pts]
            d = "M" + " L".join(f"{x:.1f} {y:.1f}" for x, y in q) + (" Z" if close else "")
            op = 1 if p == 0 else 0.55
            w = width if p == 0 else width * 0.7
            self.parts.append(f'<path d="{d}" fill="none" stroke="{color}" stroke-opacity="{op}" '
                              f'stroke-width="{w:.2f}" stroke-linecap="round" stroke-linejoin="round"/>')

    def ligne(self, a, b, color, width=2.6, passes=2):
        pts = [(a[0] + (b[0] - a[0]) * t / 5, a[1] + (b[1] - a[1]) * t / 5) for t in range(6)]
        self.trait(pts, color, width, passes=passes, wobble=0.5)

    def remplir(self, pts, color):
        d = "M" + " L".join(f"{x:.1f} {y:.1f}" for x, y in pts) + " Z"
        self.parts.append(f'<path d="{d}" fill="{color}"/>')

    def hachures(self, pts, color, spacing=4.0, angle=-35, width=1.5):
        self.n += 1
        cid = f"h{self.n}"
        d = "M" + " L".join(f"{x:.1f} {y:.1f}" for x, y in pts) + " Z"
        self.defs.append(f'<clipPath id="{cid}"><path d="{d}"/></clipPath>')
        xs = [p[0] for p in pts]
        ys = [p[1] for p in pts]
        cx, cy = (min(xs) + max(xs)) / 2, (min(ys) + max(ys)) / 2
        r = math.hypot(max(xs) - min(xs), max(ys) - min(ys)) / 2 + 2
        a = math.radians(angle)
        ux, uy = math.cos(a), math.sin(a)
        lines = []
        k = -r
        while k <= r:
            ox, oy = cx - uy * k, cy + ux * k
            p1 = (ox - ux * r + self.j(1), oy - uy * r + self.j(1))
            p2 = (ox + ux * r + self.j(1), oy + uy * r + self.j(1))
            lines.append(f"M{p1[0]:.1f} {p1[1]:.1f} L{p2[0]:.1f} {p2[1]:.1f}")
            k += spacing + self.j(0.6)
        self.parts.append(f'<path d="{" ".join(lines)}" stroke="{color}" stroke-width="{width}" '
                          f'stroke-linecap="round" stroke-opacity="0.85" clip-path="url(#{cid})"/>')

    def svg(self, fond, label):
        return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" role="img" aria-label="{label}">\n'
                f'  <defs>{"".join(self.defs)}</defs>\n  <rect width="108" height="108" fill="{fond}"/>\n  '
                + "\n  ".join(self.parts) + "\n</svg>\n")


def cercle(cx, cy, r, n=36):
    return [(cx + r * math.cos(2 * math.pi * k / n), cy + r * math.sin(2 * math.pi * k / n)) for k in range(n)]


def nuage_pts(bosses, base):
    """Contour d'un nuage : le haut suit les bosses (cercles), le bas est plat, arrondi aux deux bouts."""
    x0 = min(cx - r for cx, cy, r in bosses)
    x1 = max(cx + r for cx, cy, r in bosses)
    top, bot = [], []
    steps = 30
    for i in range(steps + 1):
        x = x0 + (x1 - x0) * i / steps
        ups = [cy - math.sqrt(r * r - (x - cx) ** 2) for cx, cy, r in bosses if abs(x - cx) <= r]
        downs = [cy + math.sqrt(r * r - (x - cx) ** 2) for cx, cy, r in bosses if abs(x - cx) <= r]
        top.append((x, min(ups)))
        bot.append((x, min(base, max(downs))))
    return top + bot[::-1]


def soleil(c, cx, cy, r, trait, remplissage, rayons=True, r1=None, r2=None):
    pts = cercle(cx, cy, r)
    c.remplir(pts, remplissage[0])
    c.hachures(pts, remplissage[1], spacing=3.6, angle=-40)
    c.trait(pts, trait, close=True)
    if rayons:
        r1 = r1 or r + 5
        r2 = r2 or r + 11
        for k in range(10):
            a = 2 * math.pi * k / 10 - math.pi / 2
            c.ligne((cx + r1 * math.cos(a), cy + r1 * math.sin(a)), (cx + r2 * math.cos(a), cy + r2 * math.sin(a)), trait)


def nuage(c, bosses, base, trait, fond, ombre):
    pts = nuage_pts(bosses, base)
    c.remplir(pts, fond)
    # Ombre hachurée sous le nuage, comme au crayon.
    ys = [p[1] for p in pts]
    lim = min(ys) + (max(ys) - min(ys)) * 0.55
    bas = [(x, max(y, lim)) for x, y in pts]
    c.hachures(bas, ombre, spacing=3.4, angle=-30, width=1.3)
    c.trait(pts, trait, close=True)


def pluie(c, gouttes, couleur):
    for x, y in gouttes:
        c.ligne((x, y), (x - 2.5, y + 7), couleur, width=2.8)


LOGOS = {}


def logo(nom, label):
    def deco(f):
        LOGOS[nom] = (f, label)
        return f
    return deco


@logo("c1-soleil", "Météo, au crayon : un soleil")
def _(c):
    soleil(c, 54, 54, 12, GRAPHITE, (JAUNE, ORANGE), r1=18, r2=26)
    return PAPIER


@logo("c2-eclaircies", "Météo, au crayon : soleil derrière un nuage")
def _(c):
    soleil(c, 62, 45, 9, GRAPHITE, (JAUNE, ORANGE), r1=13, r2=18)
    nuage(c, [(38, 62, 8), (50, 55, 11), (65, 61, 9)], 70, GRAPHITE, PAPIER, BLEU_CLAIR)
    return PAPIER


@logo("c3-pluie", "Météo, au crayon : un nuage qui donne de la pluie")
def _(c):
    nuage(c, [(36, 50, 9), (50, 42, 12), (66, 49, 10)], 59, GRAPHITE, PAPIER, BLEU_CLAIR)
    pluie(c, [(41, 65), (53, 65), (65, 65), (47, 75), (59, 75)], BLEU)
    return PAPIER


@logo("c4-giboulee", "Météo, au crayon : soleil, nuage et averse")
def _(c):
    soleil(c, 62, 41, 8, GRAPHITE, (JAUNE, ORANGE), r1=12, r2=16)
    nuage(c, [(37, 56, 7), (49, 50, 10), (63, 55, 8)], 63, GRAPHITE, PAPIER, BLEU_CLAIR)
    pluie(c, [(42, 68), (53, 68), (64, 68), (48, 77), (59, 77)], BLEU)
    return PAPIER


@logo("c5-montagne", "Météo, au crayon : soleil et nuage au-dessus d'un sommet enneigé")
def _(c):
    soleil(c, 65, 38, 7, GRAPHITE, (JAUNE, ORANGE), r1=11, r2=15)
    mont = [(28, 74), (42, 56), (48, 62), (57, 47), (80, 74)]
    c.remplir(mont, PAPIER)
    # Versant à l'ombre hachuré, sous la neige.
    c.hachures([(57, 47), (80, 74), (60, 74), (62, 56)], GRAPHITE, spacing=3.6, angle=-60, width=1.1)
    c.hachures([(42, 56), (48, 62), (44, 74), (36, 74), (40, 60)], GRAPHITE, spacing=3.6, angle=-60, width=1.1)
    c.trait(mont, GRAPHITE)
    c.trait([(51, 57), (54, 55), (57, 58), (60, 54), (64, 56)], GRAPHITE, width=2)
    nuage(c, [(35, 46, 5), (42, 42, 7), (50, 46, 5)], 50, GRAPHITE, PAPIER, BLEU_CLAIR)
    return PAPIER


def croissant(cx, cy, r, ix, iy, ir, n=24):
    """Lune en croissant : le disque (cx, cy, r) privé du disque (ix, iy, ir), contour calculé point par point."""
    def inside(x, y, ox, oy, orr):
        return (x - ox) ** 2 + (y - oy) ** 2 < orr ** 2
    # Intersections des deux cercles.
    d = math.hypot(ix - cx, iy - cy)
    a = (r * r - ir * ir + d * d) / (2 * d)
    h = math.sqrt(r * r - a * a)
    mx, my = cx + a * (ix - cx) / d, cy + a * (iy - cy) / d
    p1 = (mx + h * (iy - cy) / d, my - h * (ix - cx) / d)
    p2 = (mx - h * (iy - cy) / d, my + h * (ix - cx) / d)
    ang = lambda p, ox, oy: math.atan2(p[1] - oy, p[0] - ox)
    def arc(ox, oy, orr, a0, a1, keep):
        for sgn in (1, -1):
            span = (a1 - a0) % (2 * math.pi) if sgn == 1 else -((a0 - a1) % (2 * math.pi))
            mid = a0 + span / 2
            if keep(ox + orr * math.cos(mid), oy + orr * math.sin(mid)):
                return [(ox + orr * math.cos(a0 + span * k / n), oy + orr * math.sin(a0 + span * k / n)) for k in range(n + 1)]
    out = arc(cx, cy, r, ang(p1, cx, cy), ang(p2, cx, cy), lambda x, y: not inside(x, y, ix, iy, ir))
    inn = arc(ix, iy, ir, ang(p2, ix, iy), ang(p1, ix, iy), lambda x, y: inside(x, y, cx, cy, r))
    return out + inn[1:-1]


@logo("c6-nuit", "Météo, au crayon : lune, étoiles et nuage")
def _(c):
    lune = croissant(56, 45, 14, 63, 39, 11)
    c.remplir(lune, "#FFF4D6")
    c.hachures(lune, JAUNE, spacing=3.2, angle=-40)
    c.trait(lune, BLANC, close=True)
    for x, y, s in [(34, 36, 3.5), (44, 27, 2.5), (78, 52, 3)]:
        c.ligne((x - s, y), (x + s, y), BLANC, width=2.2)
        c.ligne((x, y - s), (x, y + s), BLANC, width=2.2)
    nuage(c, [(39, 65, 8), (51, 59, 11), (66, 64, 9)], 73, BLANC, "#2A4566", "#6C93BD")
    return NUIT


@logo("c7-craie", "Météo, à la craie sur fond glacier : soleil derrière un nuage")
def _(c):
    soleil(c, 62, 45, 9, BLANC, (JAUNE, ORANGE), r1=13, r2=18)
    nuage(c, [(38, 62, 8), (50, 55, 11), (65, 61, 9)], 70, BLANC, GLACIER, "#9BCBE9")
    return GLACIER


if __name__ == "__main__":
    for i, (nom, (f, label)) in enumerate(LOGOS.items()):
        c = Crayon(i * 13 + 5)
        fond = f(c)
        with open(os.path.join(HERE, nom + ".svg"), "w") as out:
            out.write(c.svg(fond, label))
