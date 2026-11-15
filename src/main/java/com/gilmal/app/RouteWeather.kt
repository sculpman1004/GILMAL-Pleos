package com.gilmal.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

// ───────────────────────────────────────────────────────────────
// 경로 날씨 자동 확인 (Open-Meteo, API 키 불필요)
// - Pleos 내비는 경로 좌표 목록을 주지 않으므로, 현재 위치 → 목적지 사이를 거리에 비례해
//   여러 지점으로 나누고, 각 지점을 '지나갈 예정 시각'의 예보를 본다.
// - 지점 수: 40km마다 1곳, 최소 3곳 ~ 최대 7곳 (목적지 포함), 한 번의 요청으로 모두 조회
// - 직선 보간이라 실제 도로와 다를 수 있지만, 지역 단위 날씨 변화(어디쯤부터 비가 오는지)를 알기에는 충분하다.
// ───────────────────────────────────────────────────────────────

data class RouteSample(
    val fraction: Double,         // 경로 진행 비율 0~1
    val passTime: LocalDateTime,  // 지나갈 예정 시각
    val precipitation: Double,    // 그 시각 1시간 강수량(mm)
    val windSpeed: Double,        // 그 시각 풍속(m/s)
    val cloudCover: Double,       // 구름 양(%)
    val isDay: Boolean
) {
    val rainy get() = precipitation >= 0.1
    val sunny get() = isDay && !rainy && cloudCover < 40.0
}

data class RouteWeather(
    val samples: List<RouteSample>,
    val rainAny: Boolean,
    val rainPeakMmH: Double,
    val rain3hMax: Double,
    val windMax: Double,
    val sunnyAny: Boolean,
    val note: String?,           // 예: "출발 후 약 1시간 30분 뒤, 경로 중간쯤부터 비가 와요."
    val rainStartMinutes: Long? = null  // 출발 뒤 비 구간이 시작될 때까지 남은 분 (출발 때부터 비면 0)
)

private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = Math.sin(dLat / 2).let { it * it } +
        Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2).let { it * it }
    return 2 * r * Math.asin(Math.sqrt(a))
}

private fun afterText(minutes: Long): String = when {
    minutes < 10 -> "출발하자마자"
    minutes < 60 -> "출발 후 약 ${minutes}분 뒤"
    else -> "출발 후 약 ${minutes / 60}시간" + (if (minutes % 60 >= 10) " ${(minutes % 60) / 10 * 10}분" else "") + " 뒤"
}

private fun whereText(fraction: Double): String = when {
    fraction < 0.25 -> "경로 앞부분"
    fraction < 0.6 -> "경로 중간쯤"
    fraction < 0.9 -> "경로 뒷부분"
    else -> "목적지 근처"
}

/** 지점별 결과에서 '어디쯤부터 무엇이 바뀌는지' 한 문장으로 */
fun describeRoute(samples: List<RouteSample>, now: LocalDateTime): String? {
    if (samples.isEmpty()) return null
    val rainyIdx = samples.indices.filter { samples[it].rainy }
    val windyIdx = samples.indices.filter { samples[it].windSpeed >= 9.0 }
    val parts = mutableListOf<String>()
    when {
        rainyIdx.size == samples.size -> parts += "가는 길 내내 비가 와요."
        rainyIdx.isNotEmpty() && rainyIdx.first() == 0 -> {
            val stop = (1 until samples.size).firstOrNull { !samples[it].rainy }
            parts += if (stop != null)
                "출발할 때는 비가 오다가 ${afterText(java.time.Duration.between(now, samples[stop].passTime).toMinutes())}, ${whereText(samples[stop].fraction)}부터는 그쳐요."
            else "출발할 때 비가 와요."
        }
        rainyIdx.isNotEmpty() -> {
            val s = samples[rainyIdx.first()]
            parts += "출발할 때는 비가 없지만, ${afterText(java.time.Duration.between(now, s.passTime).toMinutes())} ${whereText(s.fraction)}부터 비가 와요."
        }
    }
    if (windyIdx.isNotEmpty() && windyIdx.size < samples.size) {
        val s = samples[windyIdx.first()]
        parts += "${whereText(s.fraction)}에서는 바람이 세져요."
    } else if (windyIdx.size == samples.size) parts += "가는 길 내내 바람이 세요."
    if (rainyIdx.isEmpty() && samples.any { it.sunny }) parts += "가는 길에 해가 비치는 구간이 있어요."
    if (rainyIdx.isEmpty() && windyIdx.isEmpty()) {
        // 변화가 없어도 '확인했다'는 사실을 알려 준다
        val sky = when {
            samples.all { !it.isDay } -> "가는 길 내내 어두운 시간이에요."
            samples.none { it.sunny } -> "가는 길 내내 구름이 많아요."
            else -> ""
        }
        parts.add(0, "가는 길 ${samples.size}곳의 날씨를 지나갈 시각 기준으로 확인했어요. 비나 강한 바람 소식은 없어요." + if (sky.isNotEmpty()) " $sky" else "")
    }
    return parts.joinToString(" ").ifBlank { null }
}

/**
 * 출발지(현재 위치)와 목적지 사이의 경로 날씨를 조회한다.
 * @param distanceM 내비가 알려 준 남은 거리(m). 없으면 직선거리로 추정
 * @param etaS 내비가 알려 준 남은 시간(초)
 */
fun fetchRouteWeather(
    startLat: Double, startLon: Double,
    destLat: Double, destLon: Double,
    distanceM: Int?, etaS: Int?
): RouteWeather {
    val zone = ZoneId.of("Asia/Seoul")
    val now = LocalDateTime.now(zone)
    val km = (distanceM?.div(1000.0)) ?: (haversineKm(startLat, startLon, destLat, destLon) * 1.3)
    val count = (km / 40.0).toInt().coerceIn(3, 7)
    val fractions = (0 until count).map { it.toDouble() / (count - 1) }   // 0(출발) ~ 1(목적지)
    val lats = fractions.map { startLat + (destLat - startLat) * it }
    val lons = fractions.map { startLon + (destLon - startLon) * it }
    val totalS = (etaS ?: (km / 70.0 * 3600).toInt()).coerceAtLeast(0)

    val url = URL(
        "https://api.open-meteo.com/v1/forecast" +
            "?latitude=" + lats.joinToString(",") { String.format(Locale.US, "%.4f", it) } +
            "&longitude=" + lons.joinToString(",") { String.format(Locale.US, "%.4f", it) } +
            "&hourly=precipitation,wind_speed_10m,cloud_cover,is_day&forecast_days=2&wind_speed_unit=ms&timezone=Asia%2FSeoul"
    )
    val connection = url.openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 8000
        connection.readTimeout = 8000
        val text = connection.inputStream.bufferedReader().use { it.readText() }
        // 여러 지점이면 배열, 한 지점이면 객체로 온다
        val roots: List<JSONObject> = text.trim().let { t ->
            if (t.startsWith("[")) JSONArray(t).let { arr -> (0 until arr.length()).map { arr.getJSONObject(it) } }
            else listOf(JSONObject(t))
        }
        val samples = roots.mapIndexed { i, root ->
            val hourly = root.getJSONObject("hourly")
            val times = hourly.getJSONArray("time")
            val pass = now.plusSeconds((totalS * fractions[i]).toLong())
            // 지나갈 시각이 들어 있는 1시간 칸
            val idx = (0 until times.length()).lastOrNull {
                !LocalDateTime.parse(times.getString(it)).isAfter(pass)
            } ?: 0
            RouteSample(
                fraction = fractions[i],
                passTime = pass,
                precipitation = hourly.getJSONArray("precipitation").optDouble(idx, 0.0),
                windSpeed = hourly.getJSONArray("wind_speed_10m").optDouble(idx, 0.0),
                cloudCover = hourly.optJSONArray("cloud_cover")?.optDouble(idx, 100.0) ?: 100.0,
                isDay = hourly.getJSONArray("is_day").optInt(idx, 1) == 1
            )
        }
        // 위험 판단용 3시간 합계: 지점들을 지나는 시간 순서로 이어 본 강수량의 연속 3칸 최대
        val rain3h = samples.map { it.precipitation }.windowed(3, 1, partialWindows = true).maxOfOrNull { it.sum() } ?: 0.0
        return RouteWeather(
            samples = samples,
            rainAny = samples.any { it.rainy },
            rainPeakMmH = samples.maxOfOrNull { it.precipitation } ?: 0.0,
            rain3hMax = rain3h,
            windMax = samples.maxOfOrNull { it.windSpeed } ?: 0.0,
            sunnyAny = samples.any { it.sunny },
            note = describeRoute(samples, now),
            rainStartMinutes = samples.firstOrNull { it.rainy }?.let { java.time.Duration.between(now, it.passTime).toMinutes() }
        )
    } finally {
        connection.disconnect()
    }
}
