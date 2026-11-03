package com.gilmal.app

import android.content.Context
import android.media.AudioManager
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

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var voiceStatus by mutableStateOf("음성 엔진 연결 중")
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
    private val navigationEvents = object : NaviHelperEventListener {
        override fun onDestinationInfo(destinationInfo: DestinationInfo) {
            runOnUiThread {
                destination = destinationInfo.takeUnless { it.name.isNullOrBlank() }
                navigationStatus = if (destinationInfo.name.isNullOrBlank()) {
                    "안내 중인 목적지가 없습니다"
                } else {
                    "목적지: ${destinationInfo.name} · 남은 거리: ${destinationInfo.remainDistance}m"
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
        override fun onError(errorCode: NaviErrorCode) {
            runOnUiThread { navigationStatus = "내비 오류: $errorCode" }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // TTS 초기화
        tts = TextToSpeech(this, this)
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
            // 차량용 디스플레이 환경을 고려한 다크 모드 적용
            GILMALTheme(darkTheme = true) {
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
                        LaunchedEffect(lat, lon, preview) {
                            if (lat != null && lon != null && name != null) {
                                checkBrakes()
                                try {
                                    val result = withContext(Dispatchers.IO) { destinationOutlook(name, lat, lon) }
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
                        GilmalMvpScreen(
                        onPlayVoice = { text -> speakOut(text) },
                        onStopVoice = { stopSpeak() }
                    )
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

    private data class TripForecast(val summary: String, val night: Boolean, val rain: Boolean)

    private fun destinationOutlook(name: String, latitude: Double, longitude: Double): TripForecast {
        val url = URL("https://api.open-meteo.com/v1/forecast?latitude=$latitude&longitude=$longitude" +
            "&hourly=precipitation,wind_speed_10m,is_day&forecast_days=2&wind_speed_unit=ms&timezone=Asia%2FSeoul")
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            val hourly = connection.inputStream.bufferedReader().use { JSONObject(it.readText()).getJSONObject("hourly") }
            val times = hourly.getJSONArray("time")
            val now = java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Seoul"))
            val start = (0 until times.length()).firstOrNull {
                java.time.LocalDateTime.parse(times.getString(it)).isAfter(now)
            } ?: throw java.io.IOException("현재 시각 이후의 예보가 없습니다")
            if (start == 0 || start + 2 >= times.length()) throw java.io.IOException("예보 기간 부족")
            val hours = start..start + 2
            val rain = hours.sumOf { hourly.getJSONArray("precipitation").getDouble(it) }
            val wind = hours.maxOf { hourly.getJSONArray("wind_speed_10m").getDouble(it) }
            val notice = if (rain > 0) {
                "강수 예보 · 출발 전 와이퍼와 타이어 확인\n" +
                    "공기압이 높게 표시되면 타이어가 식은 상태에서 운전석 문 안쪽 라벨의 앞·뒤 권장값과 비교하세요. " +
                    "비 예보나 주행 직후 수치만 보고 공기를 빼지 마세요."
            } else "예보상 강수 없음"
            val summary = String.format(Locale.KOREA, "%s · Open-Meteo 목적지 예보 %s~%s (한국시간)\n강수 합계 %.1fmm · 시간대별 최대 풍속 %.1fm/s · %s",
                name, times.getString(start - 1).replace('T',' '), times.getString(start + 2).replace('T',' '), rain, wind, notice)
            val night = hours.any { hourly.getJSONArray("is_day").getInt(it) == 0 }
            return TripForecast(summary, night, rain > 0)
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

    private fun refreshDestination() {
        navigationStatus = "목적지 조회 중"
        try {
            navigation.getDestinationInfo()
        } catch (error: RuntimeException) {
            navigationStatus = "목적지 조회 실패: ${error.localizedMessage}"
        }
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            voiceStatus = "음성 엔진 연결 실패"
        } else {
            val result = tts?.setLanguage(Locale.KOREAN)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                voiceStatus = if (result == TextToSpeech.LANG_MISSING_DATA) "한국어 음성 데이터 없음" else "한국어 음성 미지원"
            } else {
                isTtsReady = true
                voiceStatus = "한국어 음성 준비됨"
                // 발화 속도를 0.9배로 설정
                tts?.setSpeechRate(0.9f)
            }
        }
    }

    private fun speakOut(text: String) {
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
        }
    }

    private fun stopSpeak() {
        if (isTtsReady) {
            tts?.stop()
        }
    }

    override fun onDestroy() {
        vehicle.release()
        navigation.removeListener(navigationEvents)
        navigation.release()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
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
                text = "시뮬레이션 데이터 (실제 날씨 연동 안됨)",
                color = Color.Yellow,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                modifier = Modifier
                    .background(Color.DarkGray, RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
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
    val standardMsgA = remember(selectedRisk, selectedIntensity, dummyData) { RiskEngine.getStandardWarning(selectedRisk, selectedIntensity, dummyData) }
    val gilmalResultB = remember(selectedRisk, selectedIntensity, selectedMode, dummyData) {
        when (selectedRisk) {
            RiskType.WIND -> RiskEngine.evaluateWind(dummyData.avgWind, dummyData.maxWind, selectedMode)
            RiskType.RAIN -> RiskEngine.evaluateRain(dummyData.rain3h, dummyData.rain12h, selectedMode)
            RiskType.ICE -> RiskEngine.evaluateIce(dummyData.iceState, selectedMode)
        }
    }
    val gilmalMsgB = gilmalResultB.guideMessage

    // 평가 폼 상태
    var evaluateTarget by remember { mutableStateOf("A (일반)") }
    var q1Risk by remember { mutableStateOf("") }
    var q2Action by remember { mutableStateOf("") }
    var score by remember { mutableIntStateOf(3) }
    
    // 메모리 내 평가 결과 리스트
    val evaluationList = remember { mutableStateListOf<EvaluationRecord>() }
    val context = androidx.compose.ui.platform.LocalContext.current

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
            text = "사용된 가상 데이터: ${dummyData.description}",
            fontSize = 14.sp, color = Color.LightGray, modifier = Modifier.padding(top = 4.dp)
        )

        // A/B 음성 패널
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            // A 패널
            Card(
                modifier = Modifier.weight(1f).height(240.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(12.dp).fillMaxSize()) {
                    Text("A (일반 수치/경고)", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Text(standardMsgA, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Button(onClick = { onPlayVoice(standardMsgA) }, modifier = Modifier.fillMaxWidth()) { Text("A 듣기") }
                }
            }
            // B 패널
            Card(
                modifier = Modifier.weight(1f).height(240.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Column(modifier = Modifier.padding(12.dp).fillMaxSize()) {
                    Text("B (길말 생활 안내)", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Text(gilmalMsgB, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Button(onClick = { onPlayVoice(gilmalMsgB) }, modifier = Modifier.fillMaxWidth()) { Text("B 듣기") }
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
                q1Risk = ""
                q2Action = ""
                score = 3
                Toast.makeText(context, "평가가 임시 저장되었습니다.", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) {
            Text("결과 저장 (${evaluationList.size}건)", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }

        // CSV 추출 버튼
        Button(
            onClick = {
                if (evaluationList.isEmpty()) {
                    Toast.makeText(context, "저장된 결과가 없습니다.", Toast.LENGTH_SHORT).show()
                    return@Button
                }
                val sb = StringBuilder()
                sb.append("평가대상,위험유형,사용자모드,위험강도,위험인지(Q1),행동인지(Q2),이해도점수(Q3)\n")
                evaluationList.forEach { r ->
                    // CSV 특성상 따옴표 치환 및 추가 필요
                    val safeQ1 = r.q1Risk.replace("\"", "\"\"")
                    val safeQ2 = r.q2Action.replace("\"", "\"\"")
                    sb.append("\"${r.evaluatedTarget}\",\"${r.riskType}\",\"${r.userMode}\",\"${r.intensity}\",\"$safeQ1\",\"$safeQ2\",${r.score}\n")
                }
                
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("EvaluationCSV", sb.toString())
                clipboard.setPrimaryClip(clip)
                
                Toast.makeText(context, "결과가 클립보드에 CSV 형식으로 복사되었습니다.", Toast.LENGTH_LONG).show()
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
        ) {
            Text("평가 결과 내보내기 (클립보드 복사)", fontSize = 16.sp, fontWeight = FontWeight.Bold)
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
