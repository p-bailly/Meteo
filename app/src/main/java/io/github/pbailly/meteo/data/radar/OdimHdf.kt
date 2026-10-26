package io.github.pbailly.meteo.data.radar

import io.jhdf.HdfFile

// Lecture des fichiers ODIM_H5 sur le téléphone, par jhdf (la webapp a la sienne, par h5wasm).

/** Le fichier radar [bytes], ouvert pour [OdimDecoder]. */
internal fun openOdim(bytes: ByteArray): OdimFile = JhdfOdim(HdfFile.fromBytes(bytes))

private class JhdfOdim(private val hdf: HdfFile) : OdimFile {
    override fun attribute(group: String, name: String): Any? = hdf.getByPath(group).getAttribute(name)?.data?.let { data ->
        when (data) {
            is Array<*> -> data.firstOrNull()
            is DoubleArray -> data.firstOrNull()
            is LongArray -> data.firstOrNull()
            is IntArray -> data.firstOrNull()
            else -> data
        }
    }

    override fun data(path: String): Any = hdf.getDatasetByPath(path).dataFlat

    override fun close() = hdf.close()
}
