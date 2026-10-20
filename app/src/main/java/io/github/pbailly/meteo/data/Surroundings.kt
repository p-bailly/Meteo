package io.github.pbailly.meteo.data

/** Le lieu habité où l'on est : personne (la campagne, la montagne), un hameau, un village, une petite ou une grande ville. */
enum class Settlement { NONE, HAMLET, VILLAGE, TOWN, CITY }

/**
 * Ce qui entoure le lieu, d'après OpenStreetMap : la ville ou le village, une forêt, une rivière, un lac, la mer, une
 * île. Le dessin d'Aujourd'hui s'en sert pour poser le décor.
 */
data class Surroundings(
    val settlement: Settlement,
    val forest: Boolean = false,
    val river: Boolean = false,
    val lake: Boolean = false,
    val coast: Boolean = false,
    val island: Boolean = false,
)
