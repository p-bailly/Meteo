"""Fabrique un petit fichier ODIM_H5 au format de la lame d'eau radar Météo-France (500 m),
mais sur une grille réduite (434 × 434 cases de 4 km) pour les tests.

Structure reprise d'un vrai fichier T_IPRN20_C_LFPW_*.h5 : projection stéréographique polaire,
données uint16 compressées, gain 0,01 (cumul sur 5 min en mm), nodata 65535, undetect 65534.

Usage : python make_fixture.py lame_d_eau_test.h5  (nécessite h5py et numpy)
"""
import sys

import h5py
import numpy as np

SIZE, SCALE = 434, 4000.0
PROJDEF = ("+proj=stere +lat_0=90 +lon_0=0 +lat_ts=45 +ellps=WGS84 "
           "+x_0=619652.07 +y_0=5262818.34 +datum=WGS84")

data = np.full((SIZE, SIZE), 65534, dtype=np.uint16)  # pas de pluie détectée
data[:, :20] = 65535                                    # hors couverture (bord ouest)
# Averse au-dessus de Lyon (45,76° N, 4,84° E) : case (ligne 222, colonne 248) sur cette grille.
data[220:225, 246:251] = 50    # 0,50 mm en 5 min = 6 mm/h
data[222, 248] = 900           # 9 mm en 5 min = 108 mm/h (grêle)

with h5py.File(sys.argv[1], "w") as f:
    s = np.bytes_
    f.create_group("what").attrs.update({
        "object": s("COMP"), "version": s("H5rad 2.3"), "date": s("20261019"), "time": s("161000"),
        "source": s("ORG:85"),
    })
    f.create_group("where").attrs.update({
        "projdef": s(PROJDEF), "xsize": np.int64(SIZE), "ysize": np.int64(SIZE),
        "xscale": SCALE, "yscale": SCALE,
        "UL_lon": -9.965, "UL_lat": 53.67,
        "UR_lon": 17.564202528305234, "UR_lat": 52.54813781717718,
        "LL_lon": -6.715173408021557, "LL_lat": 38.14493289858148,
        "LR_lon": 11.976054526221487, "LR_lat": 37.45745953865351,
    })
    d1 = f.create_group("dataset1")
    d1.create_group("what").attrs.update({
        "product": s("COMP"), "startdate": s("20261019"), "starttime": s("160500"),
        "enddate": s("20261019"), "endtime": s("161000"),
    })
    data1 = d1.create_group("data1")
    data1.create_group("what").attrs.update({
        "quantity": s("ACRR"), "gain": 0.01, "offset": 0.0, "nodata": 65535.0, "undetect": 65534.0,
    })
    data1.create_dataset("data", data=data, compression="gzip", chunks=(62, SIZE))
