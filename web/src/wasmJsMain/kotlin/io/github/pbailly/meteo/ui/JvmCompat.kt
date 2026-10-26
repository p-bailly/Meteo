package io.github.pbailly.meteo.ui

import java.util.Locale
import jvmcompat.jformat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

// Ce que la JVM d'Android donne sans import (java.lang, String.format…), pour les fichiers de app/ compilés aussi pour
// le web. Même contenu dans chaque paquet partagé.

internal typealias Math = jvmcompat.JMath
internal typealias System = jvmcompat.JSystem
internal typealias Integer = jvmcompat.JInteger

internal fun String.format(vararg args: Any?): String = jformat(null, this, args)
internal fun String.format(locale: Locale?, vararg args: Any?): String = jformat(locale, this, args)
internal fun String.Companion.format(format: String, vararg args: Any?): String = jformat(null, format, args)
internal fun String.Companion.format(locale: Locale?, format: String, vararg args: Any?): String = jformat(locale, format, args)
internal fun String.uppercase(locale: Locale): String = uppercase()
internal fun String.lowercase(locale: Locale): String = lowercase()
internal fun Char.titlecase(locale: Locale): String = titlecase()

/** Pas de fils d'exécution dans le navigateur : les entrées-sorties passent par la boucle d'événements. */
internal val Dispatchers.IO: CoroutineDispatcher get() = Dispatchers.Default

internal fun <K : Comparable<K>, V> sortedMapOf(): MutableMap<K, V> = jvmcompat.SortedMutableMap()

/** `javaClass.simpleName` : le nom de la classe. */
internal val <T : Any> T.javaClass: kotlin.reflect.KClass<out T> get() = this::class
