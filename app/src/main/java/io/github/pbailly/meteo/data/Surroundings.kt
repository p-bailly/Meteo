package io.github.pbailly.meteo.data

/** Le lieu habité où l'on est : personne (la campagne, la montagne), un hameau, un village, une petite ou une grande ville. */
enum class Settlement { NONE, HAMLET, VILLAGE, TOWN, CITY }

/**
 * Ce qui entoure le lieu, d'après OpenStreetMap : la ville ou le village, une forêt, une rivière, un lac, la mer, une
 * île, une station de ski ; les vignes et les vergers, un château, des ruines, une abbaye, des éoliennes, la voie
 * ferrée, un port, un phare. Le dessin d'Aujourd'hui s'en sert pour poser le décor.
 */
data class Surroundings(
    val settlement: Settlement,
    val forest: Boolean = false,
    val river: Boolean = false,
    val lake: Boolean = false,
    val coast: Boolean = false,
    val island: Boolean = false,
    /** Une station de ski : remontées mécaniques ou pistes de descente tout près. */
    val ski: Boolean = false,
    /** Habitants du lieu habité (la taille de la ville dessinée en dépend). */
    val population: Long? = null,
    val vineyard: Boolean = false,
    val orchard: Boolean = false,
    val castle: Boolean = false,
    val ruins: Boolean = false,
    /** Une abbaye ou un monastère. */
    val abbey: Boolean = false,
    /** Des éoliennes dans les 8 km. */
    val windTurbines: Boolean = false,
    /** Une voie ferrée en service tout près. */
    val railway: Boolean = false,
    /** Un port ou une marina. */
    val harbour: Boolean = false,
    /** Un vrai phare dans les 5 km. */
    val lighthouse: Boolean = false,
)
