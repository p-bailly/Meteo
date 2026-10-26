package io.github.pbailly.meteo.data

import java.time.LocalDate

/**
 * Le temps passé d'un lieu, jour par jour, de [start] au dernier jour connu, sans trou : la réanalyse ERA5 d'Open-Meteo
 * (depuis 1940, maille de 9 à 25 km). Les extrêmes y sont un peu adoucis par rapport à une station.
 */
class DailyHistory(
    val start: LocalDate,
    val temperatureMax: DoubleArray,
    val temperatureMin: DoubleArray,
    val precipitation: DoubleArray,
    /** Neige tombée, en cm. */
    val snowfall: DoubleArray,
    val windGusts: DoubleArray,
    /** Ensoleillement, en secondes. */
    val sunshine: DoubleArray,
    val weatherCode: IntArray,
) {
    val size: Int get() = temperatureMax.size
    val end: LocalDate get() = date(size - 1)

    private val startDay = start.toEpochDay()

    fun date(i: Int): LocalDate = start.plusDays(i.toLong())

    /** Rang du jour [date], ou -1 s'il n'est pas dans l'historique. */
    fun index(date: LocalDate): Int = (date.toEpochDay() - startDay).toInt().takeIf { it in 0 until size } ?: -1

    /** Année, mois et jour de chaque rang, calculés une fois (les records les parcourent tous). */
    val years: IntArray by lazy { IntArray(size) { date(it).year } }
    val months: IntArray by lazy { IntArray(size) { date(it).monthValue } }

    /**
     * [recent] mis à la suite (il remplace les jours qu'ils ont en commun). Les derniers jours encore inconnus d'Open-Meteo
     * (vides) sont retirés.
     */
    fun followedBy(recent: DailyHistory?): DailyHistory {
        val offset = recent?.let { (it.start.toEpochDay() - startDay).toInt() } ?: 0
        val total = if (recent == null || offset < 0) size else maxOf(size, offset + recent.size)
        fun merge(mine: DoubleArray, theirs: DoubleArray?): DoubleArray = DoubleArray(total) { i ->
            val j = i - offset
            val other = if (theirs != null && offset >= 0 && j in theirs.indices) theirs[j] else Double.NaN
            if (!other.isNaN()) other else mine.getOrElse(i) { Double.NaN }
        }
        val codes = IntArray(total) { i ->
            val j = i - offset
            val other = if (recent != null && offset >= 0 && j in recent.weatherCode.indices) recent.weatherCode[j] else -1
            if (other >= 0) other else weatherCode.getOrElse(i) { -1 }
        }
        val max = merge(temperatureMax, recent?.temperatureMax)
        val known = (max.indexOfLast { !it.isNaN() } + 1).coerceAtLeast(1)
        return DailyHistory(
            start,
            max.copyOf(known),
            merge(temperatureMin, recent?.temperatureMin).copyOf(known),
            merge(precipitation, recent?.precipitation).copyOf(known),
            merge(snowfall, recent?.snowfall).copyOf(known),
            merge(windGusts, recent?.windGusts).copyOf(known),
            merge(sunshine, recent?.sunshine).copyOf(known),
            codes.copyOf(known),
        )
    }
}

/** Une valeur remarquable et le jour où elle a été atteinte (le premier jour, pour une série de jours secs). */
data class Record(val value: Double, val date: LocalDate)

/** Températures normales d'un jour (moyennes des maxis et des minis). */
data class DayNormal(val max: Double, val min: Double) {
    val mean: Double get() = (max + min) / 2
}

/** Les records d'un jour de l'année (le 26 octobre de toutes les années). */
data class DayRecords(val hottest: Record?, val coldest: Record?, val wettest: Record?)

/** Les records du lieu sur une période : de tous les temps, d'un mois de l'année, ou d'une année. */
data class PlaceRecords(
    val hottest: Record?,
    val coldest: Record?,
    val wettest: Record?,
    val gust: Record?,
    val snowfall: Record?,
    /** La plus longue suite de jours secs (moins de 1 mm) : sa longueur en jours, et son premier jour. */
    val drySpell: Record?,
)

/** Une période comparée à la normale : la valeur mesurée et la valeur habituelle sur les mêmes jours. */
data class Versus(val value: Double, val normal: Double)

/** Les jours passés d'une période comparés à la normale ([from] → [to]). */
data class PeriodSummary(
    val from: LocalDate,
    val to: LocalDate,
    /** Température moyenne, et écart à la normale. */
    val temperatureGap: Double,
    val precipitation: Versus,
    /** Ensoleillement, en heures. */
    val sunshine: Versus,
    /** Jours de gel (mini sous 0 °C) et jours de chaleur (maxi à 30 °C ou plus). */
    val frostDays: Versus,
    val hotDays: Versus,
)

/** Normales et records, calculés sur un [DailyHistory]. */
object History {
    /** Période des normales (celle de Météo-France et de l'OMM). */
    const val NORMAL_FIRST_YEAR = 1991
    const val NORMAL_LAST_YEAR = 2020

    /** Les normales d'un jour lissent ± 7 jours autour de lui : 30 ans × 15 jours. */
    private const val NORMAL_WINDOW = 7

    /** Un jour « sec » : moins de 1 mm. */
    const val DRY_MM = 1.0

    /** Le même jour une autre année (le 29 février devient le 28 les années ordinaires). */
    fun sameDay(year: Int, date: LocalDate): LocalDate {
        val day = if (date.monthValue == 2 && date.dayOfMonth == 29 && !LocalDate.of(year, 1, 1).isLeapYear) 28 else date.dayOfMonth
        return LocalDate.of(year, date.monthValue, day)
    }

    /** Normales du jour [date] sur 1991–2020, ou null si l'historique ne couvre pas assez d'années. */
    fun normal(h: DailyHistory, date: LocalDate): DayNormal? {
        var max = 0.0
        var min = 0.0
        var n = 0
        for (year in NORMAL_FIRST_YEAR..NORMAL_LAST_YEAR) {
            val center = h.index(sameDay(year, date))
            if (center < 0) continue
            for (k in center - NORMAL_WINDOW..center + NORMAL_WINDOW) {
                if (k !in 0 until h.size) continue
                val hi = h.temperatureMax[k]
                val lo = h.temperatureMin[k]
                if (hi.isNaN() || lo.isNaN()) continue
                max += hi
                min += lo
                n++
            }
        }
        if (n < MIN_NORMAL_DAYS) return null
        return DayNormal(max / n, min / n)
    }

    /** Records du jour de l'année de [date], sur toutes les années connues avant lui. */
    fun dayRecords(h: DailyHistory, date: LocalDate): DayRecords {
        var hottest: Record? = null
        var coldest: Record? = null
        var wettest: Record? = null
        for (year in h.start.year until date.year + 1) {
            // Le 29 février n'existe que les années bissextiles : on ne le compare qu'à lui-même.
            if (date.monthValue == 2 && date.dayOfMonth == 29 && sameDay(year, date).dayOfMonth != 29) continue
            val day = sameDay(year, date)
            if (!day.isBefore(date)) continue
            val i = h.index(day)
            if (i < 0) continue
            hottest = higher(hottest, h.temperatureMax[i], day)
            coldest = lower(coldest, h.temperatureMin[i], day)
            wettest = higher(wettest, h.precipitation[i], day)
        }
        return DayRecords(hottest, coldest, wettest)
    }

    /** Records parmi les jours que [keep] retient (par rang dans l'historique). */
    fun records(h: DailyHistory, keep: (Int) -> Boolean): PlaceRecords {
        var hottest: Record? = null
        var coldest: Record? = null
        var wettest: Record? = null
        var gust: Record? = null
        var snowfall: Record? = null
        var dry: Record? = null
        var runStart = -1
        fun closeRun(endExclusive: Int) {
            if (runStart < 0) return
            val length = (endExclusive - runStart).toDouble()
            if (dry == null || length > dry!!.value) dry = Record(length, h.date(runStart))
            runStart = -1
        }
        for (i in 0 until h.size) {
            if (!keep(i)) {
                closeRun(i)
                continue
            }
            hottest = higher(hottest, h.temperatureMax[i], i, h)
            coldest = lower(coldest, h.temperatureMin[i], i, h)
            wettest = higher(wettest, h.precipitation[i], i, h)
            gust = higher(gust, h.windGusts[i], i, h)
            snowfall = higher(snowfall, h.snowfall[i], i, h)
            val rain = h.precipitation[i]
            if (!rain.isNaN() && rain < DRY_MM) {
                if (runStart < 0) runStart = i
            } else {
                closeRun(i)
            }
        }
        closeRun(h.size)
        return PlaceRecords(hottest, coldest, wettest, gust, snowfall?.takeIf { it.value > 0 }, dry)
    }

    fun allTimeRecords(h: DailyHistory): PlaceRecords = records(h) { true }

    /** Records d'un mois de l'année (octobre de toutes les années). */
    fun monthRecords(h: DailyHistory, month: Int): PlaceRecords {
        val months = h.months
        return records(h) { months[it] == month }
    }

    fun yearRecords(h: DailyHistory, year: Int): PlaceRecords {
        val years = h.years
        return records(h) { years[it] == year }
    }

    /** Maxi du même jour les [count] années d'avant [date] : (année, maxi), de la plus ancienne à la plus récente. */
    fun sameDayMaxima(h: DailyHistory, date: LocalDate, count: Int): List<Pair<Int, Double>> =
        (date.year - count until date.year).mapNotNull { year ->
            val i = h.index(sameDay(year, date))
            if (i < 0 || h.temperatureMax[i].isNaN()) null else year to h.temperatureMax[i]
        }

    /** Les jours de [from] à [to] (compris) face à la moyenne des mêmes jours de 1991–2020. */
    fun period(h: DailyHistory, from: LocalDate, to: LocalDate): PeriodSummary? {
        val first = h.index(from)
        val last = h.index(to)
        if (first < 0 || last < first) return null
        val length = last - first
        var gapSum = 0.0
        var gapDays = 0
        for (i in first..last) {
            val normal = normal(h, h.date(i)) ?: continue
            val hi = h.temperatureMax[i]
            val lo = h.temperatureMin[i]
            if (hi.isNaN() || lo.isNaN()) continue
            gapSum += (hi + lo) / 2 - normal.mean
            gapDays++
        }
        if (gapDays == 0) return null

        class Totals(var rain: Double = 0.0, var sun: Double = 0.0, var frost: Double = 0.0, var hot: Double = 0.0)
        fun totals(a: Int, b: Int) = Totals().apply {
            for (i in a..b) {
                h.precipitation[i].takeIf { !it.isNaN() }?.let { rain += it }
                h.sunshine[i].takeIf { !it.isNaN() }?.let { sun += it / 3600 }
                if (h.temperatureMin[i] < 0) frost++
                if (h.temperatureMax[i] >= 30) hot++
            }
        }
        val now = totals(first, last)
        // Les mêmes jours chaque année de la période des normales (la période peut chevaucher deux années).
        val normal = Totals()
        var years = 0
        for (year in NORMAL_FIRST_YEAR..NORMAL_LAST_YEAR) {
            val a = h.index(sameDay(year - (to.year - from.year), from))
            if (a < 0 || a + length >= h.size) continue
            val t = totals(a, a + length)
            normal.rain += t.rain
            normal.sun += t.sun
            normal.frost += t.frost
            normal.hot += t.hot
            years++
        }
        if (years == 0) return null
        return PeriodSummary(
            from = from,
            to = to,
            temperatureGap = gapSum / gapDays,
            precipitation = Versus(now.rain, normal.rain / years),
            sunshine = Versus(now.sun, normal.sun / years),
            frostDays = Versus(now.frost, normal.frost / years),
            hotDays = Versus(now.hot, normal.hot / years),
        )
    }

    /** Il faut au moins 20 des 30 années pour une normale. */
    private const val MIN_NORMAL_DAYS = 20 * (2 * NORMAL_WINDOW + 1)

    private fun higher(best: Record?, value: Double, date: LocalDate): Record? =
        if (value.isNaN() || (best != null && value <= best.value)) best else Record(value, date)

    private fun lower(best: Record?, value: Double, date: LocalDate): Record? =
        if (value.isNaN() || (best != null && value >= best.value)) best else Record(value, date)

    // Variantes par rang : la date n'est calculée que pour un nouveau record.
    private fun higher(best: Record?, value: Double, i: Int, h: DailyHistory): Record? =
        if (value.isNaN() || (best != null && value <= best.value)) best else Record(value, h.date(i))

    private fun lower(best: Record?, value: Double, i: Int, h: DailyHistory): Record? =
        if (value.isNaN() || (best != null && value >= best.value)) best else Record(value, h.date(i))
}
