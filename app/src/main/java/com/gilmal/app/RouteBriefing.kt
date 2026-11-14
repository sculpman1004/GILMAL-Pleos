package com.gilmal.app

import ai.pleos.playground.navi.constants.CautionPointType
import ai.pleos.playground.navi.constants.TurnType
import ai.pleos.playground.navi.data.CautionPoint
import ai.pleos.playground.navi.data.TBTInfo

// ───────────────────────────────────────────────────────────────
// 출발 전 경로 브리핑
// - 이동 경로 기준으로 위험을 미리 이야기한다 (목적지 예보 + 내비 주의 구간·터널·휴게소)
// - 문장은 행정안전부 국민행동요령의 운전 관련 행동을 생활 언어로 옮긴 것
//   (강풍: 감속·방어운전, 옆 차로와 거리 / 호우: 감속, 침수 도로·지하차도 진입 금지, 폭우 시 대기 /
//    한파·결빙: 저속, 차간 거리, 차량 사전 점검)
// - 숫자(풍속·강수량)는 판단 근거로만 쓰고, 운전자에게는 행동을 말한다
// ───────────────────────────────────────────────────────────────

/** 내비·예보에서 모은 이동 경로 정보 */
data class RouteContext(
    val destinationName: String?,
    val remainDistanceM: Int?,
    val remainTimeS: Int?,
    val forecastReady: Boolean,
    val forecastStatus: String,
    val rain: Boolean,
    val night: Boolean,
    val rainMm: Double?,
    val rainPeakMmH: Double? = null,
    val departText: String? = null,
    val arrivalText: String? = null,
    val sunsetText: String? = null,
    val sunsetDuringTrip: Boolean = false,
    val departNight: Boolean = false,
    val sunny: Boolean = false,
    val lowSun: Boolean = false,
    val routeNote: String? = null,
    val routeChecked: Boolean = false,
    val routeStatus: String? = null,
    val rainStartMinutes: Long? = null,
    val sunsetInMinutes: Long? = null,
    val maxWind: Double?,
    val tunnelCount: Int,
    val serviceAreaDistancesM: List<Double>,
    val cautions: Set<CautionPointType>
)

data class BriefItem(val title: String, val text: String)

/** 가는 길에 때맞춰 다시 알려 줄 안내 */
data class TimedCue(val minutesFromNow: Long, val label: String, val speech: String)

data class RouteBriefing(
    val level: GilmalRiskLevel?,   // null = 특별한 날씨 위험 예보 없음
    val headline: String,
    val items: List<BriefItem>,
    val speech: String,            // 전체(자세히 듣기)
    val shortSpeech: String = speech, // 출발 전 요약: 15초 안팎
    val cues: List<TimedCue> = emptyList()
)

private fun clockAfter(minutes: Long): String {
    val t = java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Seoul")).plusMinutes(minutes)
    val ampm = if (t.hour < 12) "오전" else "오후"
    val h = if (t.hour % 12 == 0) 12 else t.hour % 12
    return String.format(java.util.Locale.KOREA, "%s %d:%02d", ampm, h, t.minute)
}

/** 문장 목록에서 첫 문장만 (요약용) */
private fun firstSentence(text: String): String {
    // 따옴표 안의 "웅덩이다!" 같은 느낌표에서 끊기지 않도록 '마침표 + 공백'을 문장 끝으로 본다
    val idx = text.indexOf(". ")
    return if (idx > 0) text.substring(0, idx + 1) else text
}

/** 내비 이벤트 목록을 경로 정보로 정리 */
fun summarizeRoute(tbt: List<TBTInfo>, cautions: List<CautionPoint>): Triple<Int, List<Double>, Set<CautionPointType>> {
    val tunnels = tbt.count { it.type == TurnType.TURN_TUNNEL }
    val rest = tbt.filter { it.type == TurnType.TURN_ENTER_SERVICEAREA || it.type == TurnType.TURN_RESTAREA }
        .map { it.distance }.sorted()
    return Triple(tunnels, rest, cautions.map { it.type }.toSet())
}

private fun durationText(seconds: Int): String {
    val m = (seconds + 59) / 60
    return if (m >= 60) "${m / 60}시간" + (if (m % 60 > 0) " ${m % 60}분" else "") else "${m}분"
}

// ── 체감 표현: 숫자를 일상에서 느끼는 모습으로 바꾼다
// 근거: 기상청 시간당 강수량 체감 안내(5mm 도로가 젖음 / 15mm 웅덩이·와이퍼 없이는 시야 불편 /
//       30mm 시야 확보가 어려워 아주 느리게 운행·지하차도 물 참 / 50mm 정상 운행 어려움),
//       기상청 바람 표현(4~9m/s 약간 강한 바람 / 9~14m/s 강한 바람 / 14m/s 이상 강풍주의보 기준)
// 행동: 행정안전부 국민행동요령(감속·방어운전, 옆 차로와 거리, 침수 도로·지하차도 진입 금지, 폭우 시 대기)

/** 시간당 강수량(mm) → 단계 0~4 */
private fun rainStep(mmPerHour: Double) = when {
    mmPerHour < 1.0 -> 0
    mmPerHour < 5.0 -> 1
    mmPerHour < 15.0 -> 2
    mmPerHour < 30.0 -> 3
    else -> 4
}

fun rainFeel(mode: UserMode, mmPerHour: Double): String {
    val step = rainStep(mmPerHour)
    return when (mode) {
        UserMode.FAMILY -> when (step) {
            0 -> "창밖에 아주 가는 비가 살짝 내려요. 젖은 길은 생각보다 미끄러워서, 오늘은 조금 천천히 가는 게 좋아요."
            1 -> "보슬보슬 비가 내려서 우산이 필요해요. 젖은 길에서 빨리 달리면 브레이크를 밟아도 차가 바로 서지 못하고 쭉 밀려요. 그래서 천천히 가요."
            2 -> "토독토독 빗소리가 들릴 만큼 비가 와요. 이런 날 빨리 달리면 차가 물웅덩이 위에서 미끄럼틀 타듯 미끄러질 수 있어요. 천천히 가야 안전해요."
            3 -> "와이퍼가 쉴 새 없이 움직여도 앞이 뿌옇게 보일 만큼 비가 와요. 앞이 잘 안 보일 때 빨리 달리면 앞차를 늦게 보게 돼요. 아주 천천히 가요."
            else -> "앞이 잘 안 보일 만큼 비가 쏟아져요. 이럴 때 빨리 달리는 건 눈을 가리고 달리는 것과 같아요. 물이 찬 지하차도에는 들어가지 않고, 휴게소에서 비가 약해질 때까지 쉬어 가요."
        }
        UserMode.BEGINNER -> when (step) {
            0, 1 -> "도로가 거울처럼 번들거려요. 앞차와 거리만 넉넉히 두면 급할 일이 없어요."
            2 -> "앞차 브레이크등만 번져 보이는 비예요. 와이퍼와 전조등을 켜고, 뒤차가 붙어도 내 속도로 가면 돼요."
            3 -> "차선이 물에 잠겨 잘 안 보이는 비예요. 속도를 줄이세요. 천천히 가는 건 잘하고 있다는 뜻이에요."
            else -> "양동이로 퍼붓듯 쏟아지는 비예요. 휴게소에서 잠시 쉬어 가는 것도 좋은 판단이에요. 물 고인 지하차도는 피하세요."
        }
        UserMode.CARGO -> when (step) {
            0, 1 -> "노면이 젖었습니다. 적재 중량만큼 제동거리가 늘어납니다. 차간거리를 평소보다 넓히십시오."
            2 -> "덮개가 빗물로 무거워지고 끈이 느슨해집니다. 사이드미러로 덮개 펄럭임을 수시로 확인하십시오."
            3 -> "시야가 크게 줄어드는 비입니다. 급제동 시 적재물이 앞으로 쏠립니다. 미리 감속하고, 거울로 적재 상태를 확인하십시오."
            else -> "시간당 30mm 이상, 앞이 거의 안 보이는 비입니다. 운행을 중지하고 안전한 곳에서 대기하십시오. 침수 도로·지하차도 진입 금지."
        }
    }
}

/** 풍속(m/s) → 체감 문장. 9m/s 미만은 안내하지 않는다 */
fun windFeel(mode: UserMode, ms: Double): String {
    val strong = ms >= 14.0
    if (ms >= 20.0) return when (mode) {
        UserMode.FAMILY -> "나무가 쓰러질 만큼 바람이 세게 불어요. 우리 차도 바람에 흔들려요. 빨리 달리면 바람이 차를 옆 차선으로 휙 밀 수 있어요. 천천히, 옆 차와 멀리 떨어져 가야 안전해요."
        UserMode.BEGINNER -> "나무가 쓰러질 만큼 센 바람이에요. 차가 휘청해도 정상이에요. 핸들을 꽉 잡고 천천히 가면 괜찮아요."
        UserMode.CARGO -> "나무가 쓰러지는 수준의 강풍입니다. 덮개 파손과 적재물 낙하 위험이 있습니다. 무리한 운행은 멈추고 안전한 곳에서 대기하십시오."
    }
    return when (mode) {
        UserMode.FAMILY -> if (strong)
            "큰 나뭇가지가 부러질 만큼 바람이 세요. 빨리 달리면 바람이 차를 옆 차선으로 휙 밀 수 있어요. 천천히, 옆 차와 멀리 떨어져 가야 안전해요."
        else "우산이 뒤집힐 만큼 바람이 불어요. 다리 위에서 빨리 달리면 바람이 차를 옆으로 툭 밀 수 있어요. 천천히 가야 안전해요."
        UserMode.BEGINNER -> if (strong)
            "큰 나뭇가지가 부러질 만큼 센 바람이에요. 차가 흔들려도 놀라지 마세요. 두 손으로 핸들을 잡고 속도만 줄이면 돼요."
        else "우산이 뒤집힐 만큼 바람이 불어요. 다리 위에서 살짝 밀려도 괜찮아요. 미리 속도만 줄이세요."
        UserMode.CARGO -> if (strong)
            "큰 나뭇가지가 부러지는 수준의 강풍입니다. 덮개가 펄럭이면 적재물이 이탈합니다. 크게 감속하고 사이드미러로 적재물을 확인하십시오."
        else "우산이 뒤집히는 수준의 바람입니다. 교량 위에서 감속하고, 거울로 덮개와 끈을 확인하십시오."
    }
}

fun iceLine(mode: UserMode) = when (mode) {
    UserMode.FAMILY -> "길이 거울처럼 반짝일 만큼 얼어 있을 수 있어요. 빨리 달리다 멈추려고 하면 썰매처럼 쭉 미끄러져요. 평소보다 천천히, 앞차와 멀리 떨어져 가요."
    UserMode.BEGINNER -> "겉으로는 젖은 길처럼 보여도 얇게 얼어 있을 수 있어요. 평소보다 천천히 달리고 앞차와 거리를 충분히 두세요. 급하게 브레이크를 밟지 마세요."
    UserMode.CARGO -> "노면 미끄럼 위험 구간입니다. 적재 차량은 미끄러지면 제어가 어렵습니다. 저속 주행, 차간거리 확보, 급제동 금지."
}

/**
 * 터널 출구 안내: 경로 날씨에 따라 달라진다.
 * - 비: 출구 쪽 물웅덩이·다시 시작되는 빗길
 * - 맑음: 출구에서 갑자기 눈이 부심(해가 낮게 뜨는 시간이면 더 강하게)
 * - 바람: 출구 옆바람
 */
fun tunnelLine(mode: UserMode, rain: Boolean, sunny: Boolean, lowSun: Boolean, windy: Boolean): String {
    val parts = mutableListOf<String>()
    if (rain) parts += when (mode) {
        UserMode.FAMILY -> "터널을 나오면 비가 와요. 출구 쪽 물웅덩이를 빨리 밟으면 차가 휘청해요. 터널 끝이 보이면 미리 천천히 가요."
        UserMode.BEGINNER -> "터널을 나오면 다시 빗길이에요. 출구 쪽 물웅덩이를 지나면 핸들이 한쪽으로 쏠릴 수 있으니 출구 전에 미리 속도를 줄이세요."
        UserMode.CARGO -> "터널 출구부터 빗길입니다. 출구 물웅덩이에서 미끄럼과 물보라가 발생합니다. 출구 전 감속하십시오."
    }
    if (sunny && !rain) parts += when (mode) {
        UserMode.FAMILY -> "터널 밖으로 나가면 햇빛이 확 눈부셔요. 빨리 나가면 잠깐 동안 앞이 하얗게 안 보여서 위험해요. 터널 끝이 보이면 미리 천천히 가요." +
            if (lowSun) " 해가 낮게 떠 있는 시간이라 더 눈부셔요." else ""
        UserMode.BEGINNER -> "터널을 나올 때 갑자기 눈이 부실 수 있어요. 출구가 보이면 미리 속도를 줄이고 앞차와 거리를 두세요." +
            if (lowSun) " 해가 낮게 떠 있는 시간이라 햇빛이 정면으로 들어올 수 있어요. 햇빛 가리개를 미리 내려 두세요." else ""
        UserMode.CARGO -> "터널 출구에서 눈부심으로 수 초간 앞차가 보이지 않습니다. 출구 전 감속하고 차간거리를 확보하십시오." +
            if (lowSun) " 해가 낮아 눈부심이 더 강합니다." else ""
    }
    if (windy) parts += when (mode) {
        UserMode.FAMILY -> "터널 밖은 바람이 휙 불 수 있어요."
        UserMode.BEGINNER -> "출구에서 옆바람이 강할 수 있으니 핸들을 두 손으로 잡으세요."
        UserMode.CARGO -> "터널 출구에 옆바람이 있습니다. 높은 적재물이 흔들립니다. 출구 전 감속하십시오."
    }
    if (parts.isEmpty()) parts += "터널을 나올 때는 밝기와 노면이 갑자기 바뀔 수 있어요. 출구 전에 미리 속도를 줄이세요."
    return parts.joinToString(" ")
}

/** 시연용 터널 출구: 맑은 날 눈부심 + 옆바람 */
fun tunnelExitLine(mode: UserMode) = tunnelLine(mode, rain = false, sunny = true, lowSun = false, windy = true)

/**
 * 오늘 날씨 그림: 출발 전에 '오늘은 어떤 날인지'를 한 장면으로 먼저 말한다.
 * 가족 모드에서는 아이 머릿속에 그림이 생기도록 — 빨리 달리면 아이가 먼저 "천천히!"라고 반응하게 된다.
 */
fun todayPicture(mode: UserMode, rainMmH: Double?, windMs: Double?): String? {
    val rain = rainMmH?.takeIf { it >= 0.1 }?.let {
        when {
            it < 1.0 -> "가는 비"
            it < 5.0 -> "보슬비"
            it < 15.0 -> "토독토독 빗소리가 들리는 비"
            it < 30.0 -> "와이퍼가 쉴 새 없이 움직이는 비"
            else -> "앞이 잘 안 보일 만큼 쏟아지는 비"
        }
    }
    val wind = windMs?.takeIf { it >= 9.0 }?.let {
        when {
            it < 14.0 -> "우산이 뒤집힐 만큼"
            it < 20.0 -> "큰 나뭇가지가 부러질 만큼"
            else -> "나무가 쓰러질 만큼"
        }
    }
    if (rain == null && wind == null) return null
    val scene = when {
        rain != null && wind != null -> "오늘은 ${rain}가 오고, ${wind} 바람이 세게 부는 날이에요."
        rain != null -> "오늘은 ${rain}가 오는 날이에요."
        else -> "오늘은 ${wind} 바람이 세게 부는 날이에요."
    }
    return when (mode) {
        UserMode.FAMILY -> scene + (if (wind != null) " 우리 차도 바람에 흔들릴 수 있어요." else "") +
            " 이런 날 빨리 달리면 위험해요. 천천히, 안전하게 가요."
        UserMode.BEGINNER -> scene +
            (if (wind != null) " 다리 위에서 차가 조금 흔들려도 정상이에요." else " 앞유리가 빗방울로 번져 보여도 괜찮아요.") +
            " 앞차와 거리를 넉넉히 두고 내 속도로 가면, 오늘도 충분히 잘 갈 수 있어요."
        UserMode.CARGO -> scene +
            (if (wind != null) " 높은 적재물은 돛처럼 바람을 받습니다. 끈 풀림 위험이 큽니다." else " 젖은 덮개는 무거워지고 끈이 느슨해집니다.") +
            " 떨어진 적재물 하나가 뒤차 앞유리를 뚫는 사고가 됩니다. 주행 중 사이드미러로 적재물과 뒤차를 수시로 확인하십시오."
    }
}

/** 비 안내 끝에 붙이는 물웅덩이 주의 */
private fun puddleTail(mode: UserMode) = when (mode) {
    UserMode.FAMILY -> "물웅덩이를 빨리 지나가면 차가 휘청하고, 천천히 지나가면 괜찮아요."
    UserMode.BEGINNER -> "물웅덩이를 빠르게 지나면 핸들이 쏠리거나 미끄러질 수 있어요. 웅덩이가 보이면 미리 속도를 줄이세요."
    UserMode.CARGO -> "물웅덩이 고속 통과 시 미끄럼과 물보라로 옆 차 시야를 가립니다. 웅덩이 전 감속하십시오."
}

/**
 * 어두워지는 시간 안내: 출발은 낮이어도 도착이 일몰 뒤면 출발 전에 조명 점검을 권한다.
 * 예) 오후 4시 출발 · 오후 6시 도착 · 일몰 오후 5시 21분
 */
private fun nightLine(mode: UserMode, ctx: RouteContext): BriefItem {
    val dep = ctx.departText
    val arr = ctx.arrivalText
    val sun = ctx.sunsetText
    if (ctx.sunsetDuringTrip && arr != null && sun != null) {
        val text = when (mode) {
            UserMode.FAMILY -> "지금은 환하지만, 도착할 ${arr}쯤에는 해가 져서 깜깜해져요. 해는 ${sun}에 져요. 출발 전에 전조등이 잘 켜지는지 같이 확인해요."
            UserMode.BEGINNER -> "출발은 ${dep ?: "지금"}이지만 도착 예정 ${arr}은 해가 진 뒤예요. 일몰은 ${sun}이에요. 출발 전에 전조등을 확인하고, 해 질 무렵에는 미리 켜세요."
            UserMode.CARGO -> "출발 ${dep ?: "지금"}, 도착 예정 ${arr}, 일몰 ${sun}. 도착 전 해가 집니다. 전조등·차폭등·후미등과 적재물 반사판을 확인하십시오. 적재물이 뒤차에 보이지 않으면 추돌 위험이 있습니다."
        }
        return BriefItem("가는 길에 해가 져요", text)
    }
    if (ctx.departNight) {
        val text = if (mode == UserMode.FAMILY) "밖이 어두운 시간에 출발해요. 출발 전에 전조등이 잘 켜지는지 확인해요."
        else "어두운 시간에 출발합니다. 출발 전에 전조등이 잘 켜지는지 확인하세요."
        return BriefItem("어두운 시간 출발", text)
    }
    val text = if (mode == UserMode.FAMILY) "도착할 때는 어두워져요. 출발 전에 전조등이 잘 켜지는지 확인해요."
    else "도착 무렵 어두워집니다. 출발 전에 전조등이 잘 켜지는지 확인하세요."
    return BriefItem("야간 도착", text)
}

private fun cautionLine(type: CautionPointType): BriefItem? = when (type) {
    CautionPointType.FOG_AREA -> BriefItem("안개 구간", "가는 길에 안개가 잦은 구간이 있어요. 전조등을 켜고 속도를 줄이세요.")
    CautionPointType.FALLING_ROCK_AREA -> BriefItem("낙석 주의", "낙석 주의 구간이 있어요. 도로 위를 살피며 천천히 지나가세요.")
    CautionPointType.DOWNHILL_CAUTION_AREA -> BriefItem("긴 내리막", "긴 내리막 구간이 있어요. 미리 속도를 줄이고 브레이크를 나눠 밟으세요.")
    CautionPointType.SLIPPERY_ROAD_ALERT -> BriefItem("미끄러운 도로", "미끄러운 도로 구간이 있어요. 속도를 줄이고 앞차와 거리를 넉넉히 두세요.")
    CautionPointType.ACCIDENT_PRONE_AREA -> BriefItem("사고 잦은 곳", "사고가 잦은 구간이 있어요. 주변을 살피며 천천히 지나가세요.")
    CautionPointType.WILDLIFE_CROSSING -> BriefItem("야생동물 출몰", "야생동물이 나올 수 있는 구간이 있어요. 특히 밤에는 속도를 줄이세요.")
    CautionPointType.ROAD_DAMAGE_ALERT -> BriefItem("도로 파손", "도로가 파손된 구간이 있어요. 속도를 줄이세요.")
    else -> null
}

/** 휴게 안내: 가족은 화장실·휴게소, 화물차·초보는 졸음·휴식 중심 */
private fun restItem(mode: UserMode, ctx: RouteContext): BriefItem? {
    val sec = ctx.remainTimeS ?: return null
    if (sec <= 0) return null
    val minutes = sec / 60
    val firstRest = ctx.serviceAreaDistancesM.firstOrNull()?.let { " 가장 가까운 휴게소는 약 ${formatDistance(it.toString())} 앞이에요." } ?: ""
    return when (mode) {
        UserMode.FAMILY -> if (minutes >= 60) BriefItem(
            "휴게소·화장실",
            "도착까지 약 ${durationText(sec)} 걸려요. 출발 전에 아이와 화장실을 다녀오고, 1시간 반에서 2시간마다 휴게소에서 쉬어 가요.$firstRest"
        ) else BriefItem("화장실", "도착까지 약 ${durationText(sec)} 걸려요. 가까운 거리라도 출발 전에 화장실을 다녀오면 좋아요.")
        UserMode.BEGINNER -> if (minutes >= 90) BriefItem(
            "휴식",
            "도착까지 약 ${durationText(sec)} 걸려요. 2시간마다 휴게소에서 쉬어 가고, 졸리면 바로 졸음쉼터에 들르세요.$firstRest"
        ) else null
        UserMode.CARGO -> if (minutes >= 90) BriefItem(
            "휴식",
            "도착까지 약 ${durationText(sec)}. 2시간 운전 후 휴게소에서 휴식하고, 정차 중 고정 끈을 재결박하십시오.$firstRest"
        ) else null
    }
}

fun buildRouteBriefing(mode: UserMode, ctx: RouteContext, checks: List<CheckRow>): RouteBriefing {
    if (ctx.destinationName == null) {
        val text = "내비게이션에서 목적지를 설정하면 가는 길의 위험과 출발 전 점검할 것을 알려 드려요."
        return RouteBriefing(null, "목적지 대기 중", listOf(BriefItem("목적지", text)), text)
    }

    val items = mutableListOf<BriefItem>()
    var level: GilmalRiskLevel? = null
    fun raise(l: GilmalRiskLevel) { if (level == null || l.ordinal > level!!.ordinal) level = l }

    // 오늘 날씨 그림 — 맨 앞에 한 장면으로
    val picture = (if (ctx.forecastReady) todayPicture(mode, if (ctx.rain) (ctx.rainPeakMmH ?: 1.0) else null, ctx.maxWind) else null)
        ?.let { if ((ctx.rainStartMinutes ?: 0L) > 0L) it.replaceFirst("오늘은 ", "오늘은 가는 길에 ") else it }
    picture?.let { items += BriefItem("오늘은 이런 날", it) }

    // 0) 가는 길 날씨 변화 — 출발할 때 미리 알고 대비하도록 맨 앞에 둔다
    ctx.routeNote?.let { note ->
        val prepare = when {
            !ctx.rain -> ""
            mode == UserMode.FAMILY -> " 비가 오기 전에 미리 준비해 두면 마음이 편해요. 출발 전에 와이퍼와 타이어를 같이 살펴봐요."
            mode == UserMode.BEGINNER -> " 비 구간에 들어가기 전에 와이퍼·전조등 켜는 법을 미리 확인하고, 비가 시작되면 바로 속도를 줄이세요."
            else -> " 비 구간 전에 덮개와 고정 끈을 확인하고, 비가 시작되면 앞차와 거리를 두 배로 두세요."
        }
        items += BriefItem("가는 길 날씨 변화", note + prepare)
    }
    if (ctx.routeNote == null && ctx.forecastReady) {
        items += BriefItem("가는 길 날씨", ctx.routeStatus ?: "가는 길 날씨를 확인하는 중이에요.")
    }

    // 1) 날씨 (경로·목적지 예보 기준) — 숫자는 단계 판단에만 사용
    if (ctx.forecastReady) {
        val wind = ctx.maxWind?.let { RiskEngine.evaluateWind(avgWind = it, maxWind = null, mode = mode) }
        val rain = ctx.rainMm?.let { RiskEngine.evaluateRain(rain3h = it, rain12h = null, mode = mode) }
        val windy = wind != null && (wind.level != GilmalRiskLevel.LEVEL_1_ATTENTION || (ctx.maxWind ?: 0.0) >= 9.0)
        if (ctx.rain) {
            val perHour = ctx.rainPeakMmH ?: ((ctx.rainMm ?: 0.0) / 3.0)
            val feel = rainFeel(mode, perHour)
            items += BriefItem("비", if (feel.contains("물웅덩이를")) feel else feel + " " + puddleTail(mode))
            raise(rain?.level ?: GilmalRiskLevel.LEVEL_1_ATTENTION)
        }
        if (windy) {
            items += BriefItem("바람", windFeel(mode, ctx.maxWind ?: 9.0))
            raise(wind!!.level)
        }
        if (ctx.night) items += nightLine(mode, ctx)
    } else {
        items += BriefItem("날씨 확인", "목적지 날씨를 아직 받지 못했어요. 출발 전에 비와 바람 여부를 직접 확인하세요.")
    }

    // 2) 경로 구간 (내비 주의 구간·터널)
    ctx.cautions.mapNotNull { cautionLine(it) }.forEach { items += it; raise(GilmalRiskLevel.LEVEL_1_ATTENTION) }
    if (ctx.cautions.contains(CautionPointType.SLIPPERY_ROAD_ALERT)) items += BriefItem("결빙 대비", iceLine(mode))
    // 터널 출구: 경로 날씨에 따라 비(물웅덩이) / 맑음(눈부심) / 바람으로 나눠 안내
    val windyNow = items.any { it.title == "바람" }
    val tunnelText = tunnelLine(mode, ctx.rain, ctx.sunny, ctx.lowSun, windyNow)
    if (ctx.tunnelCount > 0) {
        items += BriefItem("터널 ${ctx.tunnelCount}곳 · 출구 주의", tunnelText)
    } else if (ctx.forecastReady && (ctx.rain || ctx.sunny)) {
        // 내비가 터널 목록을 주지 않아도, 날씨상 출구 위험이 크면 미리 알려 둔다
        items += BriefItem("터널을 지날 때", tunnelText)
    }

    // 가족 모드는 따로 부탁하지 않고, 문장 자체를 '빨리 달리면 어떻게 되는지' 아이도 알아듣게 쓴다.
    // 아이가 이해하면 운전자가 빨리 달릴 때 스스로 "천천히!"라고 반응한다.
    // 초보운전: 시야가 좁아지기 쉬우므로 '어디를 보면 되는지'를 안내
    if (mode == UserMode.BEGINNER) {
        items += beginnerVisionItem(ctx, items)
        items += beginnerDistanceCoach(ctx)
    }
    // 화물차: 날씨·도로 환경이 적재물에 주는 영향과 점검
    if (mode == UserMode.CARGO) items += cargoLoadItem(ctx, items)

    // 3) 휴게 (가족: 화장실·휴게소)
    restItem(mode, ctx)?.let { items += it }

    // 4) 출발 전 점검 연결
    val toCheck = checks.filter { it.state != CheckState.OK && it.label != "목적지 날씨" }.map { it.label.substringBefore(" (") }
    if (toCheck.isNotEmpty()) {
        items += BriefItem("출발 전 점검", "출발 전에 확인할 것: " + toCheck.joinToString(", ") + ".")
    }

    val weatherQuiet = ctx.forecastReady && !ctx.rain && items.none { it.title == "바람" }
    val headline = when {
        level == GilmalRiskLevel.LEVEL_3_SEVERE || level == GilmalRiskLevel.LEVEL_2_CAUTION ->
            "${level!!.title} · 가는 길 위험 안내"
        level == GilmalRiskLevel.LEVEL_1_ATTENTION -> "${level!!.title} · 가는 길에 살필 것"
        weatherQuiet -> "위험 예보 없음 · 출발 전 안내"
        else -> "출발 전 안내"
    }
    if (items.isEmpty() || weatherQuiet) {
        val calm = if (mode == UserMode.FAMILY) "가는 길에 큰 날씨 위험 예보는 없어요. 다 함께 안전벨트를 확인하고 출발해요."
        else "가는 길에 큰 날씨 위험 예보는 없습니다. 안전벨트를 확인하고 출발하세요."
        items.add(0, BriefItem("날씨", calm))
    }

    // ── 때맞춰 다시 알려 줄 안내 (출발 전에 다 말하지 않고 그 순간에 짧게)
    val cues = mutableListOf<TimedCue>()
    ctx.rainStartMinutes?.takeIf { it > 10 }?.let { m ->
        val perHour = ctx.rainPeakMmH ?: 1.0
        cues += TimedCue(m - 5, "비 구간 들어가기 5분 전", "곧 비가 오는 구간이에요. " + firstSentence(rainFeel(mode, perHour)) + " " + puddleTail(mode))
    }
    ctx.sunsetInMinutes?.takeIf { it > 20 }?.let { m ->
        val line = if (mode == UserMode.FAMILY) "곧 해가 져서 어두워져요. 전조등을 켤 시간이에요." else "곧 해가 집니다. 전조등을 켜세요."
        cues += TimedCue(m - 15, "해 지기 15분 전 · 전조등", line)
    }
    ctx.remainTimeS?.let { it / 60 }?.takeIf { it >= 100 }?.let {
        val line = when (mode) {
            UserMode.FAMILY -> "출발한 지 1시간 반이 지났어요. 다음 휴게소에서 화장실도 가고 잠깐 쉬어 가요."
            UserMode.BEGINNER -> "출발한 지 1시간 반이 지났어요. 다음 휴게소에서 잠깐 쉬어 가세요."
            UserMode.CARGO -> "운행 1시간 30분 경과. 다음 휴게소에서 휴식하고 고정 끈을 재결박하십시오."
        }
        cues += TimedCue(90, "휴게소 쉬어 가기", line)
    }
    cues.sortBy { it.minutesFromNow }
    if (cues.isNotEmpty()) {
        items += BriefItem(
            "가는 길에 알려 드릴 것",
            cues.joinToString("\n") { "· ${clockAfter(it.minutesFromNow)} ${it.label}" } + "\n그때그때 짧게 다시 알려 드릴게요."
        )
    }

    // ── 출발 전 요약 음성: 가장 중요한 것만 (가는 길 변화 1 · 해 지는 시각 1 · 점검 · 가족이면 아이 한마디)
    val shortSpeech = buildString {
        append("${ctx.destinationName}까지 가는 길 안내예요. ")
        items.firstOrNull { it.title == "오늘은 이런 날" }?.let { append(it.text).append(' ') }
        items.firstOrNull { it.title == "가는 길 날씨 변화" }?.let { append(firstSentence(it.text)).append(' ') }
            ?: items.firstOrNull { it.title in setOf("비", "바람", "날씨") }?.let { append(firstSentence(it.text)).append(' ') }
        items.firstOrNull { it.title == "가는 길에 해가 져요" || it.title == "어두운 시간 출발" || it.title == "야간 도착" }
            ?.let { append(if (ctx.sunsetDuringTrip && ctx.sunsetText != null) "가는 도중 ${ctx.sunsetText}에 해가 져요. " else firstSentence(it.text) + " ") }
        if (toCheck.isNotEmpty()) append("출발 전에 확인할 것은 " + toCheck.joinToString(", ") + "이에요. ")
        if (cues.isNotEmpty()) append("나머지는 가는 길에 그때그때 알려 드릴게요.")
    }.trim()

    val speech = buildString {
        append("${ctx.destinationName}까지 가는 길 안내예요. ")
        items.filter { it.title != "가는 길에 알려 드릴 것" }.forEach { append(it.text).append(' ') }
    }.trim()
    return RouteBriefing(level, headline, items, speech, shortSpeech, cues)
}

/** 초보운전 '시야 넓히기': 환경에 따라 시야가 더 좁아지는 순간과 볼 곳 (최대 2가지) */
private fun beginnerVisionItem(ctx: RouteContext, items: List<BriefItem>): BriefItem {
    val tips = mutableListOf<String>()
    if (ctx.rain) tips += "비 오는 날은 앞이 더 좁게 보여요. 바로 앞차만 보지 말고 두세 대 앞과 양쪽 거울을 번갈아 보세요."
    if (items.any { it.title.startsWith("터널") } && ctx.sunny)
        tips += "눈이 부시면 시야가 더 좁아져요. 터널 출구 전에는 속도를 먼저 줄이고, 눈이 적응할 때까지 차선만 지키며 가세요."
    if (ctx.night) tips += "어두우면 전조등이 비추는 곳까지만 보여요. 그 안에서 멈출 수 있도록 낮보다 속도를 줄이세요."
    if (items.any { it.title == "바람" }) tips += "바람에 차가 흔들려도 핸들을 크게 돌리지 말고, 멀리 앞을 보며 차선 가운데를 지키세요."
    if (tips.isEmpty()) tips += "초보운전일수록 바로 앞만 보게 돼요. 시선을 멀리 두고, 차선을 바꾸기 전에는 거울과 어깨 너머를 한 번 더 확인하세요."
    return BriefItem("시야 넓히기", tips.take(2).joinToString(" ") + " 한 번에 하나씩, 천천히 하면 충분해요.")
}

/** 초보운전 '거리 코치': 거리를 두는 것이 맞다는 확신을 주는 안심형 안내 */
private fun beginnerDistanceCoach(ctx: RouteContext): BriefItem {
    val count = if (ctx.rain || ctx.night) "넷" else "셋"
    val counting = if (count == "넷") "하나, 둘, 셋, 넷" else "하나, 둘, 셋"
    return BriefItem("거리 코치",
        "앞차가 표지판을 지나면 마음속으로 '$counting'을 세어 보세요. $count 전에 그 표지판에 닿으면 조금만 더 떨어지면 돼요. " +
        "뒤차가 바짝 붙어도 괜찮아요. 거리를 두는 운전이 가장 안전한 운전이에요.")
}

/** 화물차 '적재물 점검': 환경이 짐에 주는 영향 (최대 2가지) + 공통 고정 확인 */
private fun cargoLoadItem(ctx: RouteContext, items: List<BriefItem>): BriefItem {
    val notes = mutableListOf<String>()
    if (items.any { it.title == "바람" })
        notes += "강풍: 높은 적재물일수록 옆으로 흔들리고, 빈 차는 더 쉽게 밀립니다. 덮개와 끈을 단단히 결박하십시오."
    if (ctx.rain)
        notes += "비: 덮개 틈으로 물이 들어가 적재물이 무거워지거나 미끄러집니다. 덮개 상태를 확인하고 차간거리를 넓히십시오."
    if (ctx.cautions.contains(CautionPointType.SLIPPERY_ROAD_ALERT))
        notes += "미끄럼 구간: 급제동 시 적재물이 앞으로 쏠립니다. 일찍, 단계적으로 감속하십시오."
    if (ctx.cautions.contains(CautionPointType.DOWNHILL_CAUTION_AREA))
        notes += "긴 내리막: 적재 중량으로 브레이크가 과열됩니다. 미리 저단 기어와 엔진 브레이크를 사용하십시오."
    if (items.any { it.title.startsWith("터널") })
        notes += "터널 출구: 옆바람으로 높은 적재물이 흔들립니다."
    val common = "고정 끈은 주행 진동으로 풀립니다. 주행 중 사이드미러로 적재물·덮개·뒤차를 수시로 확인하십시오. " +
        "덮개 펄럭임이나 적재물 기울어짐이 보이면 즉시 안전한 곳에 정차해 재결박하십시오. " +
        "적재물 추락방지 의무 위반은 교통사고처리특례법상 12대 중과실입니다. 낙하물로 사람이 다치면 종합보험에 가입했어도 형사처벌 대상이 됩니다. " +
        "낙하물 사고는 뒤차 가족의 생명을 앗아가고, 운전자 본인도 형사처벌과 면허 처분으로 생계를 잃을 수 있습니다. 한 번의 결박 불량이 두 가정을 무너뜨립니다."
    return BriefItem("불량 적재 주의", (notes.take(1) + common).joinToString(" "))
}

/** 체감 문장에서 첫 문장(장면 묘사)을 빼고 나머지(이유·행동)만 */
private fun firstSentenceAfter(text: String): String {
    val idx = text.indexOf(". ")
    return if (idx > 0) text.substring(idx + 2) else ""
}

/** 시연용: 같은 국민행동요령 문장을 사용 */
fun demoLine(kind: String, mode: UserMode): String {
    val line = when (kind) {
        "WIND" -> windFeel(mode, 20.0)
        "RAIN" -> rainFeel(mode, 35.0)
        "TUNNEL" -> tunnelExitLine(mode)
        "STORM" -> todayPicture(mode, 35.0, 22.0)!! + if (mode == UserMode.FAMILY) " " + firstSentenceAfter(rainFeel(mode, 35.0)) else ""
        else -> iceLine(mode)
    }
    if (kind == "STORM") return line
    if (mode == UserMode.BEGINNER) {
        val tip = when (kind) {
            "RAIN" -> "두세 대 앞과 거울을 번갈아 보세요. 천천히 가도 괜찮아요."
            "TUNNEL" -> "눈이 적응할 때까지 차선만 지키며 천천히 가세요."
            "WIND" -> "핸들을 크게 돌리지 말고 멀리 앞을 보세요."
            else -> "브레이크는 일찍, 부드럽게 나눠 밟으세요."
        }
        return "$line $tip"
    }
    if (mode == UserMode.CARGO) {
        val tip = when (kind) {
            "RAIN" -> "덮개 틈 침수로 적재 중량이 늘어납니다. 차간거리를 넓히십시오."
            "TUNNEL", "WIND" -> "높은 적재물은 옆바람에 흔들립니다. 사이드미러로 고정 상태를 확인하십시오."
            else -> "급제동 시 적재물이 앞으로 쏠립니다. 일찍, 단계적으로 감속하십시오."
        }
        return "$line $tip"
    }
    return line
}

