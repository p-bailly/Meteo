package io.github.pbailly.meteo.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Normales de saison d'un lieu autour d'une date : moyennes 1991-2020 des températures maximales et minimales sur
 * deux semaines, avec leur pente (les normales montent au printemps et baissent à l'automne).
 */
class Normals(
    /** Jour central de la fenêtre (l'année ne compte pas). */
    val anchor: LocalDate,
    val max: Double,
    val min: Double,
    /** Variation par jour, en °C. */
    val maxSlope: Double,
    val minSlope: Double,
) {
    /** Normales maximale et minimale du [date] ; null loin de la fenêtre. */
    fun on(date: LocalDate): Pair<Double, Double>? {
        val days = ChronoUnit.DAYS.between(anchor.withYear(date.year), date).let {
            // Autour du 1er janvier, la fenêtre peut être dans l'année d'à côté.
            when {
                it > 180 -> it - date.lengthOfYear()
                it < -180 -> it + date.lengthOfYear()
                else -> it
            }
        }
        if (kotlin.math.abs(days) > 10) return null
        return (max + maxSlope * days) to (min + minSlope * days)
    }
}

object Climate {
    private const val ARCHIVE_URL = "https://archive-api.open-meteo.com/v1/archive"
    val YEARS = 1991..2020

    /** Jour central d'une semaine de l'année : la même fenêtre (et le même cache) sert sept jours. */
    fun anchorFor(date: LocalDate): LocalDate {
        val week = (date.dayOfYear - 1) / 7
        return LocalDate.ofYearDay(2001, (week * 7 + 4).coerceAtMost(365))
    }

    /**
     * Deux semaines autour de [anchor] pour l'année [year] : un seul « appel » au sens d'Open-Meteo (30 ans d'un coup
     * en compteraient près de 800 sur les 10 000 du quota quotidien).
     */
    fun windowUrl(place: Place, year: Int, anchor: LocalDate): String {
        val start = anchor.withYear(year).minusDays(7)
        return "$ARCHIVE_URL?latitude=${coord(place.latitude)}&longitude=${coord(place.longitude)}" +
            "&start_date=$start&end_date=${start.plusDays(13)}&daily=temperature_2m_max,temperature_2m_min&timezone=auto"
    }

    private fun coord(v: Double) = String.format(Locale.ROOT, "%.4f", v)

    /** Jours d'une fenêtre : (écart au jour central [center] de cette année-là, maxi, mini). */
    fun parseWindow(body: String, center: LocalDate): List<Triple<Long, Double, Double>> {
        val daily = Json.parseToJsonElement(body).jsonObject["daily"]?.jsonObject ?: throw OpenMeteoException("Réponse incomplète : « daily » manquant")
        val time = daily["time"]?.jsonArray ?: throw OpenMeteoException("Réponse incomplète")
        val hi = daily["temperature_2m_max"]?.jsonArray ?: throw OpenMeteoException("Réponse incomplète")
        val lo = daily["temperature_2m_min"]?.jsonArray ?: throw OpenMeteoException("Réponse incomplète")
        return time.indices.mapNotNull { k ->
            val date = LocalDate.parse(time[k].jsonPrimitive.content)
            val mx = hi.getOrNull(k)?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val mn = lo.getOrNull(k)?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            Triple(ChronoUnit.DAYS.between(center, date), mx, mn)
        }
    }

    /** Moyennes et pentes (moindres carrés) de tous les jours de toutes les années. */
    fun normals(anchor: LocalDate, days: List<Triple<Long, Double, Double>>): Normals {
        if (days.size < 30) throw OpenMeteoException("Trop peu de données pour les normales")
        fun fit(values: (Triple<Long, Double, Double>) -> Double): Pair<Double, Double> {
            val mx = days.map { it.first.toDouble() }.average()
            val my = days.map(values).average()
            val cov = days.sumOf { (it.first - mx) * (values(it) - my) }
            val v = days.sumOf { (it.first - mx) * (it.first - mx) }
            val slope = if (v == 0.0) 0.0 else cov / v
            return (my - slope * mx) to slope
        }
        val (max, maxSlope) = fit { it.second }
        val (min, minSlope) = fit { it.third }
        return Normals(anchor, max, min, maxSlope, minSlope)
    }

    fun encode(n: Normals): String = buildJsonObject {
        put("anchor", n.anchor.toString())
        put("max", n.max)
        put("min", n.min)
        put("maxSlope", n.maxSlope)
        put("minSlope", n.minSlope)
    }.toString()

    fun decode(text: String): Normals {
        val o = Json.parseToJsonElement(text) as JsonObject
        fun d(key: String) = o[key]!!.jsonPrimitive.double
        return Normals(LocalDate.parse(o["anchor"]!!.jsonPrimitive.content), d("max"), d("min"), d("maxSlope"), d("minSlope"))
    }
}

/**
 * Normales de chaque lieu pour la semaine en cours : 30 petites requêtes (une par année, six à la fois), gardées sur
 * le téléphone ; la semaine suivante, on recommence pour la nouvelle fenêtre.
 */
class ClimateRepository(
    private val dir: File,
    private val httpGet: (String) -> String,
) {
    suspend fun normals(place: Place, today: LocalDate): Normals = withContext(Dispatchers.IO) {
        val anchor = Climate.anchorFor(today)
        val file = File(dir, "normals_${place.id}_${anchor.monthValue}-${anchor.dayOfMonth}.json")
        if (file.exists()) {
            try {
                return@withContext Climate.decode(file.readText())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                file.delete()
            }
        }
        val days = coroutineScope {
            Climate.YEARS.chunked(6).flatMap { years ->
                years.map { year ->
                    async { Climate.parseWindow(httpGet(Climate.windowUrl(place, year, anchor)), anchor.withYear(year)) }
                }.awaitAll()
            }.flatten()
        }
        val normals = Climate.normals(anchor, days)
        dir.mkdirs()
        // Une seule fenêtre gardée par lieu.
        dir.listFiles { f -> f.name.startsWith("normals_${place.id}_") }?.forEach { it.delete() }
        file.writeText(Climate.encode(normals))
        normals
    }
}
