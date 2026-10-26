// Lecture des fichiers HDF5 du radar Météo-France dans le navigateur : h5wasm (la bibliothèque HDF5 officielle
// compilée en WebAssembly), chargée à la première image radar. Appelé depuis Kotlin (OdimHdf.kt) par window.meteoHdf.

let loading = null;
let h5 = null;
let FS = null;
let count = 0;

window.meteoHdf = {
  ready() {
    loading ??= import("./h5wasm/hdf5_hl.js").then(async (m) => {
      h5 = m.default ?? m;
      FS = (await h5.ready).FS;
    });
    return loading;
  },

  /** Le fichier (octets en « latin1 » : un caractère par octet), ouvert en lecture. */
  open(latin1) {
    const bytes = new Uint8Array(latin1.length);
    for (let i = 0; i < latin1.length; i++) bytes[i] = latin1.charCodeAt(i);
    const name = "/radar-" + (++count) + ".h5";
    FS.writeFile(name, bytes);
    const file = new h5.File(name, "r");
    file.meteoName = name;
    return file;
  },

  /** Attribut d'un groupe : nombre ou texte (le premier élément d'un tableau), ou null. */
  attr(file, group, name) {
    const node = file.get(group);
    const attr = node && node.attrs ? node.attrs[name] : null;
    if (!attr) return null;
    let v = attr.value;
    if (ArrayBuffer.isView(v) || Array.isArray(v)) v = v[0];
    if (typeof v === "bigint") v = Number(v);
    return v ?? null;
  },

  /** Les valeurs d'un jeu de données, une par caractère (entiers de 0 à 65 535). */
  data(file, path) {
    const v = file.get(path).value;
    let s = "";
    for (let i = 0; i < v.length; i += 8192) s += String.fromCharCode.apply(null, Array.from(v.subarray(i, i + 8192), Number));
    return s;
  },

  close(file) {
    try { file.close(); } catch (e) {}
    try { FS.unlink(file.meteoName); } catch (e) {}
  },
};
