package com.gilmal.app

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gilmal.app.ui.theme.GILMALTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL
import java.net.HttpURLConnection
import org.json.JSONObject
import java.util.Locale
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.control.OilLevelState
import ai.pleos.playground.vehicle.constant.telltale.TelltaleType
import ai.pleos.playground.vehicle.constant.telltale.TelltaleState
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.CautionPoint
import ai.pleos.playground.navi.data.TBTInfo

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var voiceStatus by mutableStateOf("음성 엔진 연결 중")
    private val mainHandler = Handler(Looper.getMainLooper())
    // 기본 엔진 연결이 실패하면 설치된 다른 음성 엔진을 차례로 시도한다
    private var pendingEngines: MutableList<String>? = null
    private var installedEngines: List<String> = emptyList()
    // Pleos 차량 음성(우선 사용). 없으면 Android 음성 → 알림음 순으로 대체
    private lateinit var pleosVoice: PleosVoice
    private lateinit var navigation: NaviHelper
    private lateinit var vehicle: Vehicle
    private var lightSwitch by mutableStateOf("")
    private var lowBeam by mutableStateOf("")
    private var brakeFluid by mutableStateOf("브레이크액 정보 확인 중")
    private var absWarning by mutableStateOf("ABS 경고 확인 중")
    private var brakeCheckGeneration = 0
    private var destination by mutableStateOf<DestinationInfo?>(null)
    private val pressures = mutableStateMapOf<String, String>()
    private var pressureGeneration = 0
    private var navigationReady by mutableStateOf(false)
    private var navigationStatus by mutableStateOf("내비 연결 중")
    private var locationStatus by mutableStateOf("")
    private var weatherPoint by mutableStateOf<Pair<Double, Double>?>(null)
    // 경로 정보: 내비 안내 목록(터널·휴게소)과 주의 구간(안개·낙석·미끄러운 도로 등)
    private var tbtList by mutableStateOf<List<TBTInfo>>(emptyList())
    private var cautionList by mutableStateOf<List<CautionPoint>>(emptyList())
    private val navigationEvents = object : NaviHelperEventListener {
        override fun onDestinationInfo(destinationInfo: DestinationInfo) {
            runOnUiThread {
                destination = destinationInfo.takeUnless { it.name.isNullOrBlank() }
                // 경로 날씨의 출발 지점이 없으면 현재 위치를 다시 요청 (즉시 + 3초 뒤 + 8초 뒤)
                if (weatherPoint == null && destination != null) requestStartLocation()
                navigationStatus = if (destinationInfo.name.isNullOrBlank()) {
                    "안내 중인 목적지가 없습니다"
                } else {
                    "목적지: ${destinationInfo.name} · 남은 거리: ${formatDistance("${destinationInfo.remainDistance}")}"
                }
            }
        }
        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
            runOnUiThread {
                locationStatus = "현재 위치 · 위도 ${currentLocationInfo.latitude}, 경도 ${currentLocationInfo.longitude}"
                weatherPoint = currentLocationInfo.latitude to currentLocationInfo.longitude
            }
        }
        override fun onRoutingCompleted() {
            runOnUiThread { navigationStatus = "경로 탐색 완료" }
        }
        override fun onTBTInfo(tbtInfoList: List<TBTInfo>) {
            runOnUiThread { tbtList = tbtInfoList }
        }
        override fun onCautionPointInfo(cautionPointList: List<CautionPoint>) {
            runOnUiThread {
                // 지나간 구간이 사라지지 않도록 새로 받은 주의 구간을 합쳐 둔다
                cautionList = (cautionList + cautionPointList).distinctBy { it.id }
            }
        }
        override fun onError(errorCode: NaviErrorCode) {
            runOnUiThread { navigationStatus = "내비 오류: $errorCode" }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 음성 초기화: Pleos 차량 음성을 먼저 연결하고, Android 음성은 대체용으로 함께 준비
        pleosVoice = PleosVoice(this) { status ->
            runOnUiThread {
                voiceStatus = if (pleosVoice.ready || isTtsReady) status else "$status · 알림음 대체"
            }
        }
        pleosVoice.start()
        startTts(null)
        navigation = NaviHelper(this)
        vehicle = Vehicle(this)
        vehicle.initialize()
        navigation.addListener(navigationEvents)
        navigation.initialize(
            onConnected = { runOnUiThread {
                navigationReady = true
                refreshDestination()
            } },
            onFailed = { error -> runOnUiThread {
                navigationReady = false
                navigationStatus = "내비 연결 실패: ${error.localizedMessage}"
            } }
        )

        setContent {
            // 차량 디스플레이에서 잘 보이도록 화면 전체(글자·버튼·여백)를 UI_SCALE배로 키운다
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(baseDensity.density * UI_SCALE, baseDensity.fontScale)
            ) {
                // 차량용 디스플레이 환경을 고려한 다크 모드 적용
                GilmalCarTheme {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        Column {
                            val point = weatherPoint
                            val scope = rememberCoroutineScope()
                            var weather by remember(point) { mutableStateOf("") }
                            var loading by remember(point) { mutableStateOf(false) }
                            var preview by remember { mutableStateOf(false) }
                            var rainTest by remember { mutableStateOf(false) }
                            val target = destination
                            val lat = if (preview) 37.48544722 else target?.latitude
                            val lon = if (preview) 127.03636666 else target?.longitude
                            val name = if (preview) "42dot" else target?.name
                            var forecast by remember(lat, lon, preview) { mutableStateOf<TripForecast?>(null) }
                            var forecastStatus by remember(lat, lon, preview) { mutableStateOf("목적지 예보 확인 중") }
                            // 출발지(현재 위치)를 받으면 한 번 더 조회해 경로 날씨까지 반영한다
                            val hasStart = point != null
                            LaunchedEffect(lat, lon, preview, hasStart) {
                                if (lat != null && lon != null && name != null) {
                                    checkBrakes()
                                    try {
                                        val eta = if (preview) null else target?.remainTime
                                        val distance = if (preview) null else target?.remainDistance
                                        val start = point
                                        val result = withContext(Dispatchers.IO) {
                                            val dest = destinationOutlook(name, lat, lon, eta)
                                            // 경로 날씨: 현재 위치 → 목적지 사이 여러 지점을 '지나갈 시각' 기준으로 조회
                                            var routeError: String? = null
                                            val route = start?.let {
                                                try { fetchRouteWeather(it.first, it.second, lat, lon, distance, eta) }
                                                catch (e: java.io.IOException) { routeError = "경로 날씨 연결 실패 · 목적지 날씨만 먼저 확인했어요."; null }
                                                catch (e: org.json.JSONException) { routeError = "경로 날씨 자료 부족 · 목적지 날씨만 먼저 확인했어요."; null }
                                            }
                                            if (route == null) dest.copy(
                                                routeStatus = routeError ?: "현재 위치를 받는 중이에요. 받는 대로 가는 길 날씨를 확인할게요. 지금은 목적지 날씨만 먼저 확인했어요."
                                            ) else dest.copy(
                                                rain = dest.rain || route.rainAny,
                                                rainMm = maxOf(dest.rainMm ?: 0.0, route.rain3hMax),
                                                rainPeakMmH = maxOf(dest.rainPeakMmH ?: 0.0, route.rainPeakMmH),
                                                maxWind = maxOf(dest.maxWind ?: 0.0, route.windMax),
                                                sunny = dest.sunny || route.sunnyAny,
                                                routeNote = route.note,
                                                routeChecked = true,
                                                rainStartMinutes = route.rainStartMinutes
                                            )
                                        }
                                        if (result.night) checkLights()
                                        if (result.rain) checkTires()
                                        forecast = result
                                    } catch (e: java.io.IOException) {
                                        forecastStatus = "목적지 예보 연결 실패 · 야간·우천 여부 직접 확인"
                                    } catch (e: org.json.JSONException) {
                                        forecastStatus = "목적지 예보 자료 부족 · 야간·우천 여부 직접 확인"
                                    }
                                }
                            }
                            var showTools by remember { mutableStateOf(false) }
                            var driveMode by remember { mutableStateOf(UserMode.FAMILY) }
                            if (!showTools) {
                                // 실사용 주행 화면 (기본 화면)
                                val f = forecast
                                val checkRows = buildList {
                                    if (f != null) {
                                        add(CheckRow("목적지 날씨",
                                            (if (f.rain) "비 예보" else "강수 예보 없음") + (f.maxWind?.let { String.format(Locale.KOREA, " · 바람 최대 %.0fm/s", it) } ?: ""),
                                            if (f.rain) CheckState.CHECK else CheckState.OK))
                                        add(CheckRow(if (f.sunsetDuringTrip) "전조등 (도착 ${f.arrivalText} · 일몰 ${f.sunsetText})" else if (f.night) "전조등 (어두운 시간 운행)" else "전조등",
                                            if (f.night) "하향등 $lowBeam" + (if (f.sunsetDuringTrip) " · 가는 도중 해가 집니다" else "") else "해 지기 전 도착 예상",
                                            when {
                                                !f.night -> CheckState.OK
                                                lowBeam == "ON" -> CheckState.OK
                                                else -> CheckState.CHECK
                                            }))
                                    } else {
                                        add(CheckRow("목적지 날씨", forecastStatus,
                                            if (forecastStatus.contains("실패") || forecastStatus.contains("부족")) CheckState.CHECK else CheckState.UNKNOWN))
                                    }
                                    if (f?.rain == true || pressures.isNotEmpty()) {
                                        val values = pressures.values.joinToString(" · ")
                                        add(CheckRow(if (f?.rain == true) "타이어 공기압 (비 예보)" else "타이어 공기압", values.ifEmpty { "확인 중" },
                                            if (values.contains("직접 확인") || values.contains("정보 없음")) CheckState.CHECK
                                            else if (values.contains("센서값")) CheckState.OK else CheckState.UNKNOWN))
                                    }
                                    if (target != null) {
                                        val brake = "$brakeFluid · $absWarning"
                                        add(CheckRow("브레이크 · ABS", brake,
                                            when {
                                                brake.contains("점검 필요") || brake.contains("확인 불가") -> CheckState.CHECK
                                                brake.contains("정상") && brake.contains("꺼짐") -> CheckState.OK
                                                else -> CheckState.UNKNOWN
                                            }))
                                    } else {
                                        add(CheckRow("차량 점검", "목적지를 설정하면 자동으로 확인합니다", CheckState.UNKNOWN))
                                    }
                                }
                                val (tunnels, restAreas, cautionTypes) = summarizeRoute(tbtList, cautionList)
                                val routeContext = RouteContext(
                                    destinationName = target?.name,
                                    remainDistanceM = target?.remainDistance,
                                    remainTimeS = target?.remainTime,
                                    forecastReady = f != null,
                                    forecastStatus = forecastStatus,
                                    rain = f?.rain == true,
                                    night = f?.night == true,
                                    rainMm = f?.rainMm,
                                    rainPeakMmH = f?.rainPeakMmH,
                                    departText = f?.departText,
                                    arrivalText = f?.arrivalText,
                                    sunsetText = f?.sunsetText,
                                    sunsetDuringTrip = f?.sunsetDuringTrip == true,
                                    departNight = f?.departNight == true,
                                    sunny = f?.sunny == true,
                                    lowSun = f?.lowSun == true,
                                    routeNote = f?.routeNote,
                                    routeChecked = f?.routeChecked == true,
                                    routeStatus = f?.routeStatus,
                                    rainStartMinutes = f?.rainStartMinutes,
                                    sunsetInMinutes = f?.sunsetInMinutes,
                                    maxWind = f?.maxWind,
                                    tunnelCount = tunnels,
                                    serviceAreaDistancesM = restAreas,
                                    cautions = cautionTypes
                                )
                                DriveHomeScreen(
                                    mode = driveMode,
                                    onModeChange = { driveMode = it },
                                    route = routeContext,
                                    distanceText = target?.let { formatDistance("${it.remainDistance}") },
                                    navigationReady = navigationReady,
                                    navigationStatus = navigationStatus,
                                    onRefreshDestination = ::refreshDestination,
                                    checks = checkRows,
                                    voiceStatus = voiceStatus,
                                    onPlayVoice = { text -> speakOut(text) },
                                    onStopVoice = { stopSpeak() },
                                    onOpenTools = { showTools = true }
                                )
                            } else {
                                // 기존 시연·테스트 도구 화면
                                TextButton(onClick = { showTools = false }) { Text("‹ 주행 화면으로") }
                                // 상단 연동 상태 영역: 화면 확대 후에도 아래 주요 화면을 가리지 않도록 높이를 제한하고 스크롤
                                Column(Modifier.fillMaxWidth().heightIn(max = 170.dp).verticalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
                                    Button(enabled = navigationReady, onClick = {
                                        if (!preview) { preview = true; return@Button }
                                        navigationStatus = "경로 요청 중"
                                        try {
                                            navigation.requestRoute(RouteInfo(
                                                latitude = 37.48544722, longitude = 127.03636666,
                                                displayLatitude = null, displayLongitude = null,
                                                poiName = "42dot", poiId = "36khp37hh8gqcm5nqhe5",
                                                poiSubId = "0", address = "서울시 강남구 남부순환로 2621"
                                            ))
                                            preview = false
                                        } catch (error: RuntimeException) {
                                            navigationStatus = "경로 요청 실패: ${error.localizedMessage}"
                                        }
                                    }) { Text(if (preview) "42dot 안내 시작" else "42dot으로 길찾기") }
                                    Text(navigationStatus)
                                    Text(voiceStatus)
                                    Row {

                                        Button(enabled = navigationReady, onClick = ::refreshDestination) { Text("현재 목적지 조회") }
                                        Button(enabled = navigationReady, onClick = {
                                            locationStatus = ""
                                            try {
                                                navigation.getCurrentLocationInfo()
                                            } catch (error: RuntimeException) {
                                                locationStatus = "현재 위치 조회 실패: ${error.localizedMessage}"
                                            }
                                        }) { Text("현재 위치 확인") }
                                        Button(enabled = point != null && !loading, onClick = {
                                            if (point != null) scope.launch {
                                                loading = true
                                                weather = "날씨 조회 중"
                                                try {
                                                    weather = withContext(Dispatchers.IO) { fetchWeather(point) }
                                                } catch (error: java.io.IOException) {
                                                    weather = "날씨 조회 실패 · 인터넷 연결을 확인하고 다시 눌러주세요"
                                                } catch (error: org.json.JSONException) {
                                                    weather = "날씨 응답에 필요한 값이 없습니다"
                                                } finally {
                                                    loading = false
                                                }
                                            }
                                        }) { Text("이 위치 날씨 조회") }
                                    }
                                    if (weather.isNotEmpty()) Text(weather)
                                    if (lat != null && lon != null) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Checkbox(checked = rainTest, onCheckedChange = {
                                                rainTest = it
                                                if (it) checkTires()
                                            })
                                            Text("우천 모의 테스트")
                                        }
                                        if (forecast?.rain == true || forecast?.night == true ||
                                            brakeFluid.contains("점검 필요") || absWarning.contains("점검 필요")) {
                                            Text("주행 조건 관련 브레이크 정보 · $brakeFluid · $absWarning")
                                        }
                                        Text("출발 전 자동 점검 · " + (forecast?.summary ?: forecastStatus))
                                        if (forecast?.night == true) Text("야간 예상 · 하향등 " + lowBeam + " · 스위치 " + lightSwitch +
                                            when (lowBeam) {
                                                "OFF" -> " · 출발 전 하향등을 확인하세요"
                                                "ON" -> " · 점등 신호 확인"
                                                else -> " · 계기판에서 점등 여부를 확인하세요"
                                            })
                                        if (forecast?.rain == true || rainTest) {
                                            Text((if (rainTest) "모의 우천 · " else "강수 예상 · ") +
                                                "타이어 " + pressures.values.joinToString(" · ") +
                                                " · 타이어가 식은 뒤 차량 권장 공기압을 확인하세요")
                                        }
                                        if (preview) TextButton(onClick = { preview = false }) { Text("목적지 선택 취소") }
                                    }
                                    if (locationStatus.isNotEmpty()) Text(locationStatus)
                                }
                                GilmalMvpScreen(
                                onPlayVoice = { text -> speakOut(text) },
                                onStopVoice = { stopSpeak() }
                            )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (navigationReady) refreshDestination()
    }

    private fun checkBrakes() {
        val generation = ++brakeCheckGeneration
        brakeFluid = "브레이크액 정보 확인 중"
        absWarning = "ABS 경고 확인 중"
        try {
            val safety = vehicle.getSafety()
            safety.getBrakeFluidLevelState({ state -> runOnUiThread {
                if (generation == brakeCheckGeneration) brakeFluid = when (state) {
                    OilLevelState.LOW, OilLevelState.CRITICALLY_LOW -> "브레이크액 부족 경고 · 출발 전 점검 필요"
                    OilLevelState.HIGH -> "브레이크액 높음 · 출발 전 점검 필요"
                    OilLevelState.NORMAL -> "브레이크액 센서 정상 표시"
                    else -> "브레이크액 정보 확인 불가 · 계기판 확인"
                }
            } }, { runOnUiThread {
                if (generation == brakeCheckGeneration) brakeFluid = "브레이크액 정보 확인 불가 · 계기판 확인"
            } })
            safety.getTelltalesInfo({ info -> runOnUiThread {
                if (generation == brakeCheckGeneration) {
                    val abs = info?.telltales?.firstOrNull { it.type == TelltaleType.ABS }
                    absWarning = when (abs?.state) {
                        TelltaleState.ON, TelltaleState.BLINK -> "ABS 경고 표시 · 출발 전 점검 필요"
                        TelltaleState.OFF -> "ABS 경고등 꺼짐"
                        else -> "ABS 경고 정보 확인 불가 · 계기판 확인"
                    }
                }
            } }, { runOnUiThread {
                if (generation == brakeCheckGeneration) absWarning = "ABS 경고 정보 확인 불가 · 계기판 확인"
            } })
        } catch (error: RuntimeException) {
            brakeFluid = "브레이크액 정보 확인 불가 · 계기판 확인"
            absWarning = "ABS 경고 정보 확인 불가 · 계기판 확인"
        }
    }

    private fun checkLights() {
        lightSwitch = "조회 중"
        lowBeam = "조회 중"
        try {
            val light = vehicle.getLight()
            light.getLowBeamLightSwitch(
                { value -> runOnUiThread { lightSwitch = value?.toString() ?: "정보 없음" } },
                { runOnUiThread { lightSwitch = "확인 불가" } }
            )
            light.getCurrentLowBeamLightState(
                { value -> runOnUiThread { lowBeam = value?.toString() ?: "정보 없음" } },
                { runOnUiThread { lowBeam = "확인 불가 · 직접 확인" } }
            )
        } catch (error: RuntimeException) {
            lightSwitch = "확인 불가"
            lowBeam = "확인 불가 · 직접 확인"
        }
    }

    private fun checkTires() {
        val generation = ++pressureGeneration
        pressures.clear()
        pressures["status"] = "타이어 센서 조회 중"
        fun unavailable() = runOnUiThread {
            if (generation == pressureGeneration) pressures["status"] = "공기압 정보 없음 · 직접 확인"
        }
        try {
            val tire = vehicle.getTire()
            tire.checkTirePressureCapabilities({ _, areas ->
                runOnUiThread {
                    if (generation != pressureGeneration) return@runOnUiThread
                    if (areas.isNullOrEmpty()) unavailable() else {
                        pressures.remove("status")
                        for (area in areas) {
                            val label = area.values.joinToString("/") { it.toString() }
                            pressures[label] = "$label 조회 중"
                            try {
                                tire.getTirePressure(area, { value -> runOnUiThread {
                                    if (generation == pressureGeneration) pressures[label] =
                                        if (value != null && value.isFinite() && value > 0) "$label 센서값 $value (단위 미확인)"
                                        else "$label 직접 확인"
                                } }, { runOnUiThread { if (generation == pressureGeneration) pressures[label] = "$label 직접 확인" } })
                            } catch (e: RuntimeException) { pressures[label] = "$label 직접 확인" }
                        }
                    }
                }
            }, { unavailable() })
        } catch (e: RuntimeException) { unavailable() }
    }

    private data class TripForecast(
        val summary: String, val night: Boolean, val rain: Boolean,
        val rainMm: Double? = null, val maxWind: Double? = null,
        val rainPeakMmH: Double? = null,
        // 시간 정보: 출발·도착 예정·목적지 일몰 (예: "오후 4시", "오후 6시 5분", "오후 5시 21분")
        val departText: String? = null,
        val arrivalText: String? = null,
        val sunsetText: String? = null,
        val sunsetDuringTrip: Boolean = false,  // 출발은 해 지기 전, 도착은 해 진 뒤
        val departNight: Boolean = false,
        val sunny: Boolean = false,   // 운행 시간 중 맑고 해가 있는 시간이 있음 (터널 출구 눈부심)
        val lowSun: Boolean = false,  // 해가 낮게 뜨는 시간(일출 직후·일몰 직전)에 운행
        val routeNote: String? = null,   // 경로를 따라 날씨가 어떻게 바뀌는지 (예: 출발 후 약 1시간 뒤 경로 중간쯤부터 비)
        val routeChecked: Boolean = false, // 경로 여러 지점 예보를 확인했는지 (false면 목적지 예보만)
        val routeStatus: String? = null,   // 경로 날씨를 못 본 이유 (출발 위치 대기·연결 실패 등)
        val rainStartMinutes: Long? = null,  // 비 구간 시작까지 남은 분
        val sunsetInMinutes: Long? = null    // 목적지 일몰까지 남은 분 (운행 중에 해가 질 때만)
    )

    /** 오후 6시 5분 형식 */
    private fun koTime(t: java.time.LocalDateTime): String {
        val h = t.hour
        val ampm = if (h < 12) "오전" else "오후"
        val h12 = if (h % 12 == 0) 12 else h % 12
        return "$ampm ${h12}시" + if (t.minute > 0) " ${t.minute}분" else ""
    }

    /**
     * 목적지 예보: 지금부터 도착 예정 시각까지(최소 3시간)의 비·바람과,
     * 도착 시각과 목적지 일몰·일출 시각을 비교해 '도착할 때 어두운지'를 판단한다.
     */
    private fun destinationOutlook(name: String, latitude: Double, longitude: Double, etaSeconds: Int?): TripForecast {
        val url = URL("https://api.open-meteo.com/v1/forecast?latitude=$latitude&longitude=$longitude" +
            "&hourly=precipitation,wind_speed_10m,is_day,cloud_cover&daily=sunrise,sunset&forecast_days=2&wind_speed_unit=ms&timezone=Asia%2FSeoul")
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            val root = connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
            val hourly = root.getJSONObject("hourly")
            val times = hourly.getJSONArray("time")
            val now = java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Seoul"))
            val arrival = now.plusSeconds((etaSeconds ?: 0).coerceAtLeast(0).toLong())
            val start = (0 until times.length()).firstOrNull {
                java.time.LocalDateTime.parse(times.getString(it)).isAfter(now)
            } ?: throw java.io.IOException("현재 시각 이후의 예보가 없습니다")
            if (start == 0 || start + 2 >= times.length()) throw java.io.IOException("예보 기간 부족")
            // 도착 시각이 들어 있는 시간대까지 본다 (최소 3시간)
            val arriveIdx = (start until times.length()).firstOrNull {
                !java.time.LocalDateTime.parse(times.getString(it)).isBefore(arrival)
            } ?: (times.length() - 1)
            val end = maxOf(start + 2, arriveIdx).coerceAtMost(times.length() - 1)
            val hours = start..end
            val precip = hours.map { hourly.getJSONArray("precipitation").getDouble(it) }
            // 위험 판단용: 가장 많이 오는 연속 3시간 합계
            val rain = precip.windowed(3, 1, partialWindows = true).maxOf { it.sum() }
            val wind = hours.maxOf { hourly.getJSONArray("wind_speed_10m").getDouble(it) }
            // 체감 표현용: 가장 세게 내리는 1시간의 강수량
            val rainPeak = precip.maxOrNull() ?: 0.0

            // 맑고 해가 있는 시간이 있는지 (구름 40% 미만 · 비 없음 · 낮)
            val sunny = hours.any {
                hourly.getJSONArray("is_day").getInt(it) == 1 &&
                    hourly.getJSONArray("precipitation").getDouble(it) == 0.0 &&
                    hourly.optJSONArray("cloud_cover")?.optDouble(it, 100.0)?.let { c -> c < 40.0 } == true
            }
            var lowSun = false
            var sunsetAt: java.time.LocalDateTime? = null

            // 일몰·일출 비교
            var sunsetText: String? = null
            var nightAtArrival = hours.any { hourly.getJSONArray("is_day").getInt(it) == 0 }
            var sunsetDuringTrip = false
            var departNight = false
            root.optJSONObject("daily")?.let { daily ->
                val days = daily.getJSONArray("time")
                fun sunOf(key: String, date: java.time.LocalDate): java.time.LocalDateTime? {
                    val idx = (0 until days.length()).firstOrNull { days.getString(it) == date.toString() } ?: return null
                    return java.time.LocalDateTime.parse(daily.getJSONArray(key).getString(idx))
                }
                val arriveSunset = sunOf("sunset", arrival.toLocalDate())
                val arriveSunrise = sunOf("sunrise", arrival.toLocalDate())
                val todaySunset = sunOf("sunset", now.toLocalDate())
                val todaySunrise = sunOf("sunrise", now.toLocalDate())
                if (arriveSunset != null && arriveSunrise != null) {
                    nightAtArrival = arrival.isAfter(arriveSunset) || arrival.isBefore(arriveSunrise)
                    sunsetText = koTime(arriveSunset)
                    sunsetAt = arriveSunset
                }
                if (todaySunset != null && todaySunrise != null) {
                    departNight = now.isAfter(todaySunset) || now.isBefore(todaySunrise)
                    sunsetDuringTrip = !departNight && nightAtArrival
                    // 운행 시간이 일몰 전 90분~일몰, 일출~일출 후 90분과 겹치면 해가 낮게 떠 눈부심이 크다
                    fun overlaps(a: java.time.LocalDateTime, b: java.time.LocalDateTime) = now.isBefore(b) && arrival.isAfter(a)
                    lowSun = overlaps(todaySunset.minusMinutes(90), todaySunset) ||
                        overlaps(todaySunrise, todaySunrise.plusMinutes(90))
                }
            }

            val notice = if (rain > 0) {
                "강수 예보 · 출발 전 와이퍼와 타이어 확인\n" +
                    "공기압이 높게 표시되면 타이어가 식은 상태에서 운전석 문 안쪽 라벨의 앞·뒤 권장값과 비교하세요. " +
                    "비 예보나 주행 직후 수치만 보고 공기를 빼지 마세요."
            } else "예보상 강수 없음"
            val summary = String.format(Locale.KOREA, "%s · Open-Meteo 목적지 예보 %s~%s (한국시간) · 도착 예정 %s%s\n강수 최대 3시간 %.1fmm · 최대 풍속 %.1fm/s · %s",
                name, times.getString(start - 1).replace('T',' '), times.getString(end).replace('T',' '),
                koTime(arrival), sunsetText?.let { " · 일몰 $it" } ?: "", rain, wind, notice)
            return TripForecast(
                summary, nightAtArrival || departNight, rain > 0, rain, wind, rainPeak,
                departText = koTime(now), arrivalText = koTime(arrival), sunsetText = sunsetText,
                sunsetDuringTrip = sunsetDuringTrip, departNight = departNight,
                sunny = sunny, lowSun = lowSun,
                sunsetInMinutes = sunsetAt?.takeIf { sunsetDuringTrip }?.let { java.time.Duration.between(now, it).toMinutes() }
            )
        } finally { connection.disconnect() }
    }

    private fun fetchWeather(point: Pair<Double, Double>): String {
        val url = URL("https://api.open-meteo.com/v1/forecast?latitude=${point.first}&longitude=${point.second}" +
            "&current=temperature_2m,precipitation,wind_speed_10m,is_day,shortwave_radiation&wind_speed_unit=ms&timezone=Asia%2FSeoul")
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            val current = connection.inputStream.bufferedReader().use {
                JSONObject(it.readText()).getJSONObject("current")
            }
            return "Open-Meteo 모델 날씨 · ${current.getString("time").replace('T', ' ')} (한국시간)\n" +
                "기온 ${current.getDouble("temperature_2m")}°C · 최근 ${current.getInt("interval") / 60}분 강수량 " +
                "${current.getDouble("precipitation")}mm · 풍속 ${current.getDouble("wind_speed_10m")}m/s" +
                " · ${if (current.getInt("is_day") == 1) "낮" else "밤"} · 일사량 ${current.optString("shortwave_radiation").takeUnless { it == "null" || it.isEmpty() }?.plus(" W/m²") ?: "정보 없음"}"
        } finally {
            connection.disconnect()
        }
    }

    private fun requestStartLocation() {
        fun ask() {
            if (weatherPoint != null) return
            try { navigation.getCurrentLocationInfo() } catch (e: RuntimeException) { }
        }
        ask()
        mainHandler.postDelayed({ ask() }, 3000)
        mainHandler.postDelayed({ ask() }, 8000)
    }

    private fun refreshDestination() {
        navigationStatus = "목적지 조회 중"
        try {
            navigation.getDestinationInfo()
            // 경로 날씨의 출발 지점으로 쓰기 위해 현재 위치도 함께 요청
            try { navigation.getCurrentLocationInfo() } catch (e: RuntimeException) { }
            try { navigation.getTBTInfo() } catch (e: RuntimeException) { /* 경로 안내 목록 미제공 시 무시 */ }
        } catch (error: RuntimeException) {
            navigationStatus = "목적지 조회 실패: ${error.localizedMessage}"
        }
    }

    private fun startTts(enginePackage: String?) {
        tts?.shutdown()
        tts = null
        isTtsReady = false
        voiceStatus = if (enginePackage == null) "음성 엔진 연결 중" else "음성 엔진 재연결 시도: $enginePackage"
        tts = if (enginePackage == null) TextToSpeech(this, this) else TextToSpeech(this, this, enginePackage)
    }

    // 기기에 설치된 음성(TTS) 엔진 패키지 목록
    private fun findInstalledEngines(): List<String> = try {
        packageManager.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
            .mapNotNull { it.serviceInfo?.packageName }
            .distinct()
    } catch (error: RuntimeException) {
        emptyList()
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            if (pendingEngines == null) {
                installedEngines = findInstalledEngines()
                pendingEngines = installedEngines.toMutableList()
            }
            val next = pendingEngines?.removeFirstOrNull()
            if (next != null) {
                // 생성자 안에서 onInit이 바로 호출될 수 있으므로 다음 시도는 메인 스레드에 예약한다
                mainHandler.post { startTts(next) }
                return
            }
            isTtsReady = false
            if (pleosVoice.ready) return
            voiceStatus = if (installedEngines.isEmpty()) {
                "${pleosVoice.lastStatus} · Android 음성 없음 → 알림음 대체"
            } else {
                "${pleosVoice.lastStatus} · Android 음성 연결 실패 → 알림음 대체"
            }
            return
        }
        var result = tts?.setLanguage(Locale.KOREAN)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            result = tts?.setLanguage(Locale.KOREA)
        }
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            isTtsReady = false
            if (!pleosVoice.ready) voiceStatus = (if (result == TextToSpeech.LANG_MISSING_DATA) "한국어 음성 데이터 없음" else "한국어 음성 미지원") +
                " · 알림음과 화면 문구로 대체"
        } else {
            isTtsReady = true
            if (!pleosVoice.ready) voiceStatus = "한국어 음성 준비됨"
            // 발화 속도를 0.9배로 설정
            tts?.setSpeechRate(0.9f)
        }
    }

    // 음성 엔진을 쓸 수 없을 때: 짧은 알림음 + 안내 문구를 화면에 크게 표시
    private fun playFallbackAlert(text: String) {
        try {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 90)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 500)
            mainHandler.postDelayed({ tone.release() }, 800)
        } catch (error: RuntimeException) {
            // 알림음 장치를 쓸 수 없어도 화면 안내는 계속한다
        }
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    private fun speakOut(text: String) {
        // 1순위: Pleos 차량 음성
        if (pleosVoice.speak(text)) {
            voiceStatus = "Pleos 차량 음성으로 안내"
            return
        }
        if (isTtsReady) {
            // 미디어 음량 상태 확인 및 안내
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

            // 미디어 볼륨이 너무 작을 때 (최대 볼륨의 20% 미만이거나 0일 때) 경고 토스트 출력
            if (currentVolume < (maxVolume * 0.2).toInt() || currentVolume == 0) {
                Toast.makeText(this, "미디어 볼륨이 낮습니다. 기기의 소리를 키워주세요.", Toast.LENGTH_LONG).show()
            }

            // STREAM_MUSIC 사용 및 TTS 볼륨 최대치(1.0) 설정
            val params = Bundle().apply {
                putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            }

            // QUEUE_FLUSH를 사용하여 중복 재생을 방지하고 즉시 새 알림 재생
            val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "TTS_ID_GILMAL")
            voiceStatus = if (result == TextToSpeech.SUCCESS) "음성 재생 요청 전달됨" else "음성 재생 요청 실패"
            if (result != TextToSpeech.SUCCESS) playFallbackAlert(text)
        } else {
            playFallbackAlert(text)
        }
    }

    private fun stopSpeak() {
        pleosVoice.stop()
        if (isTtsReady) {
            tts?.stop()
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        pleosVoice.release()
        vehicle.release()
        navigation.removeListener(navigationEvents)
        navigation.release()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}

// 차량 화면 확대 배율: 너무 크면 1.3f, 더 키우려면 1.8f 등으로 조절
const val UI_SCALE = 1.5f

// 내비가 주는 미터 단위 거리를 사람이 읽기 쉬운 형태로 바꾼다 (예: 310000 → 310km)
fun formatDistance(rawMeters: String): String {
    val meters = rawMeters.toDoubleOrNull() ?: return rawMeters
    return when {
        meters >= 10_000 -> String.format(Locale.KOREA, "%,.0fkm", meters / 1000)
        meters >= 1_000 -> String.format(Locale.KOREA, "%.1fkm", meters / 1000)
        else -> String.format(Locale.KOREA, "%.0fm", meters)
    }
}

enum class UserMode(val title: String) {
    FAMILY("가족"), BEGINNER("초보운전"), CARGO("화물차")
}

enum class RiskType(val title: String) {
    WIND("강풍"), RAIN("폭우"), ICE("도로 결빙")
}



@Composable
fun GilmalMvpScreen(
    onPlayVoice: (String) -> Unit,
    onStopVoice: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var selectedMode by remember { mutableStateOf(UserMode.FAMILY) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
    ) {
        // 시뮬레이션 데이터 표시 라벨 (공통)
        Box(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "테스트 도구 · 시뮬레이션 데이터 (실제 날씨 연동 안 됨)",
                color = GilmalColors.Attention,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                modifier = Modifier
                    .background(GilmalColors.Attention.copy(alpha = 0.14f), RoundedCornerShape(50))
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            )
        }

        // 공통: 사용자 모드 선택
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "사용자 모드", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                UserMode.entries.forEach { mode ->
                    SelectableButton(
                        text = mode.title,
                        isSelected = selectedMode == mode,
                        onClick = { selectedMode = mode },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // 탭 메뉴 (3단계로 확장)
        TabRow(selectedTabIndex = selectedTab) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }) {
                Text("간편 상황", modifier = Modifier.padding(16.dp), fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }) {
                Text("위험 강도", modifier = Modifier.padding(16.dp), fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }) {
                Text("주행 시나리오", modifier = Modifier.padding(16.dp), fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            Tab(selected = selectedTab == 3, onClick = { selectedTab = 3 }) {
                Text("음성 비교 (NEW)", modifier = Modifier.padding(16.dp), fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }

        // 화면 내용 전환
        Box(modifier = Modifier.weight(1f)) {
            when (selectedTab) {
                0 -> ManualTestScreen(selectedMode, onPlayVoice, onStopVoice)
                1 -> RiskIntensityTestScreen(selectedMode, onPlayVoice, onStopVoice)
                2 -> MockDrivingScreen(selectedMode, onPlayVoice, onStopVoice)
                3 -> AbTestScreen(selectedMode, onPlayVoice, onStopVoice)
            }
        }
    }
}

@Composable
fun ManualTestScreen(selectedMode: UserMode, onPlayVoice: (String) -> Unit, onStopVoice: () -> Unit) {
    var selectedRisk by remember { mutableStateOf(RiskType.WIND) }
    val guideMessage = getGuideMessage(selectedMode, selectedRisk)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Text(text = "위험 상황 직접 선택 (기존 로직)", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            RiskType.entries.forEach { risk ->
                SelectableButton(
                    text = risk.title,
                    isSelected = selectedRisk == risk,
                    onClick = { selectedRisk = risk },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth().height(200.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = guideMessage,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    lineHeight = 40.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Button(
                onClick = { onPlayVoice(guideMessage) },
                modifier = Modifier.weight(1f).height(80.dp)
            ) { Text("음성 안내", fontSize = 24.sp, fontWeight = FontWeight.Bold) }

            Button(
                onClick = onStopVoice,
                modifier = Modifier.weight(1f).height(80.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) { Text("중지", fontSize = 24.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
fun RiskIntensityTestScreen(selectedMode: UserMode, onPlayVoice: (String) -> Unit, onStopVoice: () -> Unit) {
    val focusManager = LocalFocusManager.current
    val numberOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done)
    val doneAction = KeyboardActions(onDone = { focusManager.clearFocus() })
    var selectedRisk by remember { mutableStateOf(RiskType.WIND) }

    var avgWind by remember { mutableStateOf("") }
    var maxWind by remember { mutableStateOf("") }
    var rain3h by remember { mutableStateOf("") }
    var rain12h by remember { mutableStateOf("") }
    var iceState by remember { mutableStateOf(IceRiskState.UNKNOWN) }

    var currentResult by remember { mutableStateOf<RiskResult?>(null) }
    var errorMessage by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("위험 유형", fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RiskType.entries.forEach { risk ->
                SelectableButton(
                    text = risk.title,
                    isSelected = selectedRisk == risk,
                    onClick = { selectedRisk = risk; currentResult = null; errorMessage = "" },
                    modifier = Modifier.weight(1f).height(48.dp)
                )
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (selectedRisk) {
                    RiskType.WIND -> {
                        OutlinedTextField(
                            value = avgWind,
                            onValueChange = { avgWind = it },
                            label = { Text("평균풍속 (m/s)") },
                            singleLine = true,
                            keyboardOptions = numberOptions,
                            keyboardActions = doneAction,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = maxWind,
                            onValueChange = { maxWind = it },
                            label = { Text("순간풍속 (m/s)") },
                            singleLine = true,
                            keyboardOptions = numberOptions,
                            keyboardActions = doneAction,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    RiskType.RAIN -> {
                        OutlinedTextField(
                            value = rain3h,
                            onValueChange = { rain3h = it },
                            label = { Text("3시간 누적강우량 (mm)") },
                            singleLine = true,
                            keyboardOptions = numberOptions,
                            keyboardActions = doneAction,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = rain12h,
                            onValueChange = { rain12h = it },
                            label = { Text("12시간 누적강우량 (mm)") },
                            singleLine = true,
                            keyboardOptions = numberOptions,
                            keyboardActions = doneAction,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    RiskType.ICE -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            IceRiskState.entries.forEach { state ->
                                SelectableButton(
                                    text = state.title,
                                    isSelected = iceState == state,
                                    onClick = { iceState = state },
                                    modifier = Modifier.weight(1f).height(48.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        Button(
            onClick = {
                try {
                    errorMessage = ""
                    currentResult = when (selectedRisk) {
                        RiskType.WIND -> RiskEngine.evaluateWind(avgWind.toDoubleOrNull(), maxWind.toDoubleOrNull(), selectedMode)
                        RiskType.RAIN -> RiskEngine.evaluateRain(rain3h.toDoubleOrNull(), rain12h.toDoubleOrNull(), selectedMode)
                        RiskType.ICE -> RiskEngine.evaluateIce(iceState, selectedMode)
                    }
                } catch (e: Exception) {
                    errorMessage = e.message ?: "잘못된 입력입니다."
                    currentResult = null
                }
            },
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) {
            Text("결과 확인", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }

        if (errorMessage.isNotEmpty()) {
            Text(text = errorMessage, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
        }

        currentResult?.let { result ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("위험 단계: ${result.level.title}", fontWeight = FontWeight.Bold, fontSize = 22.sp, color = MaterialTheme.colorScheme.primary)
                    Text("판단 근거: ${result.rationale}", fontSize = 14.sp)
                    Text(result.disclaimer, fontSize = 12.sp, color = Color.Gray)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text(result.guideMessage, fontSize = 20.sp, fontWeight = FontWeight.Bold, lineHeight = 30.sp)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = { onPlayVoice(result.guideMessage) }, modifier = Modifier.weight(1f).height(64.dp)) {
                    Text("음성 안내", fontSize = 20.sp)
                }
                Button(onClick = onStopVoice, modifier = Modifier.weight(1f).height(64.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                    Text("중지", fontSize = 20.sp)
                }
            }
        }
    }
}

@Composable
fun MockDrivingScreen(selectedMode: UserMode, onPlayVoice: (String) -> Unit, onStopVoice: () -> Unit) {
    var isDriving by remember { mutableStateOf(false) }
    var timeSec by remember { mutableIntStateOf(0) }
    var currentDisplayMsg by remember { mutableStateOf("대기 중...") }
    var sunnyExit by remember { mutableStateOf(true) }

    // RiskEngine 연동하여 모의 주행 중 실시간 알림 생성
    LaunchedEffect(isDriving, selectedMode) {
        if (isDriving) {
            timeSec = 0
            currentDisplayMsg = "모의 주행을 시작합니다. (청주 -> 서울)"
            onPlayVoice(currentDisplayMsg)

            while (isDriving && timeSec <= 35) {
                delay(1000)
                timeSec++
                when (timeSec) {
                    10 -> {
                        // 가상 수치: 강풍 주의보 상당
                        val result = RiskEngine.evaluateWind(avgWind = 15.0, maxWind = 22.0, mode = selectedMode, roadEnv = "일반 도로")
                        currentDisplayMsg = "[${result.level.title}] ${result.guideMessage}"
                        onPlayVoice(result.guideMessage)
                    }
                    20 -> {
                        // 가상 수치: 폭우 경보 상당
                        val result = RiskEngine.evaluateRain(rain3h = 100.0, rain12h = 190.0, mode = selectedMode)
                        currentDisplayMsg = "[${result.level.title}] ${result.guideMessage}"
                        onPlayVoice(result.guideMessage)
                    }
                    25 -> if (sunnyExit) {
                        currentDisplayMsg = "잠시 후 터널 출구입니다. 바깥이 밝아 눈이 부실 수 있어요."
                        onPlayVoice(currentDisplayMsg)
                    }
                    30 -> {
                        // 가상 수치: 터널 출구 횡풍 및 결빙
                        val result = RiskEngine.evaluateWind(avgWind = 13.0, maxWind = 19.0, mode = selectedMode, roadEnv = "터널 출구")
                        currentDisplayMsg = "[${result.level.title}] ${result.guideMessage}"
                        onPlayVoice(result.guideMessage)
                    }
                    35 -> {
                        currentDisplayMsg = "목적지에 안전하게 도착했습니다. 모의 주행을 종료합니다."
                        onPlayVoice(currentDisplayMsg)
                        isDriving = false
                    }
                }
            }
        } else {
            timeSec = 0
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("경로: 청주 ➔ 서울", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("⚠️ 예상되는 위험 구간 (총 3곳)", fontSize = 18.sp, color = MaterialTheme.colorScheme.error)
                Text("1. 강풍 구간 (진입 후 약 10초)")
                Text("2. 폭우 구간 (진입 후 약 20초)")
                Text("3. 터널 출구 (진입 후 약 30초)")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = sunnyExit, onCheckedChange = { sunnyExit = it }, enabled = !isDriving)
                    Text("맑은 낮으로 모의 주행 · 출구 5초 전 눈부심 안내")
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth().height(150.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isDriving) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Box(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isDriving) "[주행 중 - ${timeSec}초]\n$currentDisplayMsg" else currentDisplayMsg,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    lineHeight = 36.sp
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { onPlayVoice(getPreDepartureMessage(selectedMode)) },
                modifier = Modifier.weight(1f).height(60.dp),
                enabled = !isDriving
            ) { Text("출발 전\n안내 듣기", textAlign = TextAlign.Center, fontWeight = FontWeight.Bold) }

            Button(
                onClick = { isDriving = true },
                modifier = Modifier.weight(1f).height(60.dp),
                enabled = !isDriving
            ) { Text("모의 주행\n시작", textAlign = TextAlign.Center, fontWeight = FontWeight.Bold) }

            Button(
                onClick = {
                    isDriving = false
                    currentDisplayMsg = "주행이 중지되었습니다."
                    onStopVoice()
                },
                modifier = Modifier.weight(1f).height(60.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) { Text("중지", textAlign = TextAlign.Center, fontWeight = FontWeight.Bold) }
        }
    }
}

// ==============================================
// 4단계 기능: A/B 음성 비교 및 평가 스크린
// ==============================================

data class EvaluationRecord(
    val timestamp: Long = System.currentTimeMillis(),
    val riskType: String,
    val userMode: String,
    val intensity: String,
    val evaluatedTarget: String, // "A (일반)" or "B (길말)"
    val q1Risk: String,
    val q2Action: String,
    val score: Int
)

@Composable
fun AbTestScreen(selectedMode: UserMode, onPlayVoice: (String) -> Unit, onStopVoice: () -> Unit) {
    var selectedRisk by remember { mutableStateOf(RiskType.WIND) }
    var selectedIntensity by remember { mutableStateOf(GilmalRiskLevel.LEVEL_2_CAUTION) }
    
    // 선택된 상태에 따른 가상 데이터 및 A/B 문장 생성
    val dummyData = remember(selectedRisk, selectedIntensity) { RiskEngine.generateDummyDataForLevel(selectedRisk, selectedIntensity) }
    val standardMsgA = remember(selectedRisk, selectedIntensity, dummyData) { abStandardLine(selectedRisk, selectedIntensity, dummyData) }
    val gilmalResultB = remember(selectedRisk, selectedIntensity, selectedMode, dummyData) {
        when (selectedRisk) {
            RiskType.WIND -> RiskEngine.evaluateWind(dummyData.avgWind, dummyData.maxWind, selectedMode)
            RiskType.RAIN -> RiskEngine.evaluateRain(dummyData.rain3h, dummyData.rain12h, selectedMode)
            RiskType.ICE -> RiskEngine.evaluateIce(dummyData.iceState, selectedMode)
        }
    }
    // B: 같은 가상 데이터 → 체감 표현 + 국민행동요령 행동 (위험 단계는 RiskEngine 판단 결과 사용)
    val gilmalMsgB = remember(selectedRisk, selectedIntensity, selectedMode, dummyData) {
        abGilmalLine(selectedRisk, gilmalResultB.level, selectedMode, dummyData)
    }

    // 평가 폼 상태
    var evaluateTarget by remember { mutableStateOf("A (일반)") }
    var q1Risk by remember { mutableStateOf("") }
    var q2Action by remember { mutableStateOf("") }
    var score by remember { mutableIntStateOf(3) }
    
    // 평가 결과: 기기 내부에 저장되어 앱을 다시 켜도 유지된다
    val context = androidx.compose.ui.platform.LocalContext.current
    val evaluationList = remember { mutableStateListOf<EvaluationRecord>().apply { addAll(AbStore.load(context)) } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 면책 조항
        Text(
            text = "※ 본 기능은 정차 상태 또는 실내 평가 전용입니다. 실제 운전 중 조작을 엄격히 금지합니다.",
            color = MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
        )
        Text(
            text = "A는 비교를 위해 만든 수치 중심 경고 예시이며 실제 내비게이션의 공식 문구가 아닙니다. A와 B는 같은 가상 기상 데이터와 같은 음성 설정(속도·음량·음성)을 사용하고, 한 번에 하나만 재생됩니다.",
            fontSize = 12.sp, color = GilmalColors.TextSecondary
        )

        // 선택 영역 (위험 유형 / 강도)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RiskType.entries.forEach { risk ->
                SelectableButton(
                    text = risk.title, isSelected = selectedRisk == risk,
                    onClick = { selectedRisk = risk }, modifier = Modifier.weight(1f).height(48.dp)
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GilmalRiskLevel.entries.forEach { level ->
                SelectableButton(
                    text = level.title.take(4), isSelected = selectedIntensity == level,
                    onClick = { selectedIntensity = level }, modifier = Modifier.weight(1f).height(48.dp)
                )
            }
        }

        // 가상 데이터 표시
        Text(
            text = "사용된 가상 데이터(A·B 공통): ${dummyData.description} · 길말 위험 단계: ${gilmalResultB.level.title}",
            fontSize = 14.sp, color = Color.LightGray, modifier = Modifier.padding(top = 4.dp)
        )

        // A/B 음성 패널
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            // A 패널
            Card(
                modifier = Modifier.weight(1f).heightIn(min = 220.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(12.dp).fillMaxWidth()) {
                    Text("A (일반 수치 경고 예시)", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Text(standardMsgA, fontSize = 15.sp, lineHeight = 22.sp)
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = { onStopVoice(); onPlayVoice(standardMsgA) }, modifier = Modifier.fillMaxWidth()) { Text("A 듣기") }
                }
            }
            // B 패널
            Card(
                modifier = Modifier.weight(1f).heightIn(min = 220.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Column(modifier = Modifier.padding(12.dp).fillMaxWidth()) {
                    Text("B (길말 · ${selectedMode.title} 모드)", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Text(gilmalMsgB, fontSize = 15.sp, lineHeight = 22.sp)
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = { onStopVoice(); onPlayVoice(gilmalMsgB) }, modifier = Modifier.fillMaxWidth()) { Text("B 듣기") }
                }
            }
        }
        Button(onClick = onStopVoice, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
            Text("재생 중지")
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // 이해도 평가 폼
        Text("음성 이해도 평가", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            listOf("A (일반)", "B (길말)").forEach { target ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = evaluateTarget == target, onClick = { evaluateTarget = target })
                    Text(target)
                }
            }
        }
        
        OutlinedTextField(
            value = q1Risk, onValueChange = { q1Risk = it },
            label = { Text("Q1. 어떤 위험이 발생할 수 있는가?") }, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = q2Action, onValueChange = { q2Action = it },
            label = { Text("Q2. 운전자가 취해야 할 행동은?") }, modifier = Modifier.fillMaxWidth()
        )
        
        Text("Q3. 안내가 얼마나 이해하기 쉬웠는가? (1~5점)", modifier = Modifier.padding(top = 8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            (1..5).forEach { s ->
                SelectableButton(
                    text = "$s", isSelected = score == s,
                    onClick = { score = s }, modifier = Modifier.size(48.dp)
                )
            }
        }

        Button(
            onClick = {
                val record = EvaluationRecord(
                    riskType = selectedRisk.title,
                    userMode = selectedMode.title,
                    intensity = selectedIntensity.title,
                    evaluatedTarget = evaluateTarget,
                    q1Risk = q1Risk,
                    q2Action = q2Action,
                    score = score
                )
                evaluationList.add(record)
                AbStore.save(context, evaluationList)
                q1Risk = ""
                q2Action = ""
                score = 3
                Toast.makeText(context, "평가가 기기에 저장되었습니다.", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) {
            Text("결과 저장 (${evaluationList.size}건)", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }

        // A/B 결과 비교 요약
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(Modifier.padding(12.dp)) {
                Text("A/B 이해도 비교", fontWeight = FontWeight.Bold)
                Text(AbStore.summary(evaluationList), fontSize = 15.sp)
                Text("소규모 평가 결과는 효과 입증이 아닌 방향 확인용입니다.", fontSize = 11.sp, color = GilmalColors.TextMuted)
            }
        }

        // CSV 내보내기: 기기 파일 저장 + 클립보드 복사
        Button(
            onClick = {
                if (evaluationList.isEmpty()) {
                    Toast.makeText(context, "저장된 결과가 없습니다.", Toast.LENGTH_SHORT).show()
                    return@Button
                }
                val csv = AbStore.toCsv(evaluationList)
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("EvaluationCSV", csv))
                val path = AbStore.exportFile(context, evaluationList)
                Toast.makeText(
                    context,
                    if (path != null) "CSV 저장: $path (클립보드에도 복사됨)" else "클립보드에 CSV로 복사되었습니다.",
                    Toast.LENGTH_LONG
                ).show()
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
        ) {
            Text("평가 결과 CSV 내보내기", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun SelectableButton(text: String, isSelected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier.height(56.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
            contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(text = text, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
}

// 간편 테스트(MVP 1) 하위 호환
fun getGuideMessage(mode: UserMode, risk: RiskType): String {
    return when (mode) {
        UserMode.FAMILY -> when (risk) {
            RiskType.WIND -> "바람이 너무 세서 우산이 뒤집어질 수 있어요! 꽉 잡으세요!"
            RiskType.RAIN -> "하늘에서 물폭탄이 떨어져요. 천천히 안전하게 가요!"
            RiskType.ICE -> "바닥이 스케이트장처럼 미끄러워요. 조심조심 걸어요!"
        }
        UserMode.BEGINNER -> when (risk) {
            RiskType.WIND -> "바람이 강해 차가 흔들릴 수 있습니다. 핸들을 두 손으로 꼭 잡고 속도를 부드럽게 줄여주세요."
            RiskType.RAIN -> "비가 많이 와 미끄러지기 쉽습니다. 앞차와의 거리를 충분히 띄우고 브레이크는 천천히 밟아주세요."
            RiskType.ICE -> "바닥이 얼어있어 매우 위험합니다. 속도를 최대한 줄이고 급브레이크나 급격한 핸들 조작은 절대 피하세요."
        }
        UserMode.CARGO -> when (risk) {
            RiskType.WIND -> "강풍 주의. 적재물이 날아가지 않게 결박 상태를 다시 한번 확인하세요."
            RiskType.RAIN -> "폭우로 제동 거리가 깁니다. 앞차와의 거리를 평소보다 2배 이상 확보하세요."
            RiskType.ICE -> "도로 결빙. 대형 차량은 특히 미끄러지기 쉬우니 반드시 서행하세요."
        }
    }
}

fun getPreDepartureMessage(mode: UserMode): String {
    return when (mode) {
        UserMode.FAMILY -> "청주에서 서울로 출발합니다! 가는 길에 바람이 불고 비가 올 수 있어요. 그리고 터널을 나갈 땐 갑자기 밝아지니 조심해서 가요!"
        UserMode.BEGINNER -> "서울행 경로 안내를 시작합니다. 가는 길에 강풍과 폭우 구간이 있고, 터널 출구에서 위험할 수 있으니 미리 속도를 줄이고 당황하지 않게 준비해 주세요."
        UserMode.CARGO -> "서울행 화물 운송 경로입니다. 강풍과 폭우 구간이 있으며, 터널 출구 횡풍 위험이 있으니 적재물과 차체 제어에 유의하세요."
    }
}
