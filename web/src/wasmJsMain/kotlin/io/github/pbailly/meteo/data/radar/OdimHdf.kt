package io.github.pbailly.meteo.data.radar

import java.io.toLatin1
import kotlinx.coroutines.await
import kotlin.js.Promise

// Lecture des fichiers ODIM_H5 dans le navigateur, par h5wasm (la bibliothèque HDF5 compilée en WebAssembly), que
// hdf.mjs charge et expose dans window.meteoHdf. Sur le téléphone : jhdf (app/…/data/radar/OdimHdf.kt).

private fun hdfReady(): Promise<JsAny?> = js("window.meteoHdf.ready()")
private fun hdfOpen(latin1: String): JsAny = js("window.meteoHdf.open(latin1)")
private fun hdfAttrType(file: JsAny, group: String, name: String): String = js("typeof window.meteoHdf.attr(file, group, name)")
private fun hdfAttrNumber(file: JsAny, group: String, name: String): Double = js("Number(window.meteoHdf.attr(file, group, name))")
private fun hdfAttrString(file: JsAny, group: String, name: String): String = js("String(window.meteoHdf.attr(file, group, name))")
private fun hdfData(file: JsAny, path: String): String = js("window.meteoHdf.data(file, path)")
private fun hdfClose(file: JsAny): Unit = js("window.meteoHdf.close(file)")

/** Attend que h5wasm soit chargé (quelques Mo, une fois par visite). */
internal suspend fun awaitHdf5() {
    hdfReady().await<JsAny?>()
}

/** Le fichier radar [bytes], ouvert pour [OdimDecoder] ; h5wasm doit être prêt ([awaitHdf5]). */
internal fun openOdim(bytes: ByteArray): OdimFile = H5wasmOdim(hdfOpen(bytes.toLatin1()))

private class H5wasmOdim(private val file: JsAny) : OdimFile {
    override fun attribute(group: String, name: String): Any? = when (hdfAttrType(file, group, name)) {
        "number", "bigint" -> hdfAttrNumber(file, group, name)
        "string" -> hdfAttrString(file, group, name)
        else -> null
    }

    /** Une valeur de 16 bits par caractère (les données du radar sont des entiers de 0 à 65 535). */
    override fun data(path: String): Any = hdfData(file, path).toCharArray()

    override fun close() = hdfClose(file)
}
