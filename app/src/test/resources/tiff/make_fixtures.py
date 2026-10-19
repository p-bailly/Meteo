"""Fabrique de petits GeoTIFF de test, dans les variantes que peut renvoyer un service WCS.

Chaque image fait 40 × 30 pixels de 0,01° ; coin haut-gauche (4,50° E ; 46,00° N).
Valeur du pixel (x, y) = x + 100 * y (0 à 2939), sauf le pixel (0, 0) qui vaut « nodata » (-999).

Usage : python make_fixtures.py  (nécessite numpy, tifffile et imagecodecs)
"""
import numpy as np
import tifffile

W, H = 40, 30
values = (np.arange(W)[None, :] + 100 * np.arange(H)[:, None]).astype(np.float64)
values[0, 0] = -999
geo = [
    (33550, "d", 3, (0.01, 0.01, 0.0), False),                       # ModelPixelScale
    (33922, "d", 6, (0.0, 0.0, 0.0, 4.5, 46.0, 0.0), False),         # ModelTiepoint
    (42113, "s", 0, "-999", False),                                  # GDAL_NODATA
]

variants = {
    "float32_deflate_pred3_tiles_le.tif": dict(dtype="<f4", compression="zlib", predictor=3, tile=(16, 16)),
    "float32_none_strips_be.tif": dict(dtype=">f4", compression=None, rowsperstrip=7),
    "uint16_lzw_pred2_strips_le.tif": dict(dtype="<u2", compression="lzw", predictor=2, rowsperstrip=8),
    "float64_packbits_strips_le.tif": dict(dtype="<f8", compression="packbits", rowsperstrip=30),
}
for name, opts in variants.items():
    dtype = opts.pop("dtype")
    data = values.astype(dtype)
    if np.dtype(dtype).kind == "u":
        data = np.where(values < 0, 65535, values).astype(dtype)
    tags = [t if np.dtype(dtype).kind != "u" or t[0] != 42113 else (42113, "s", 0, "65535", False) for t in geo]
    tifffile.imwrite(name, data, extratags=tags, byteorder=np.dtype(dtype).byteorder.replace("=", "<"),
                     photometric="minisblack", **opts)
    print(name)
