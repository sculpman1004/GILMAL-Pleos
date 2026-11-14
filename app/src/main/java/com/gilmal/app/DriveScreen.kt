package com.gilmal.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ───────────────────────────────────────────────────────────────
// 길말 주행 화면 (실사용 기본 화면)
// 운전 중에는 "지금 위험 한 가지 + 할 행동"만 크게 보여 주고,
// 운전자 유형·목적지·출발 전 점검은 왼쪽에 모아 정차 중에 확인한다.
// ───────────────────────────────────────────────────────────────

/** 출발 전 점검 항목 상태 */
enum class CheckState { OK, CHECK, UNKNOWN }

data class CheckRow(val label: String, val value: String, val state: CheckState)

/** 주행 화면에 표시할 현재 안내 */
private data class DriveAlert(
    val badge: String,          // 예: "2단계 주의 · 가는 길 위험 안내"
    val message: String,        // 카드에 크게 보일 첫 문장
    val items: List<BriefItem>, // 경로 브리핑 항목 (시연 상황이면 비어 있음)
    val speech: String,         // 음성으로 읽을 전체 문장 (자세히)
    val shortSpeech: String,    // 출발 전 요약 음성 (15초 안팎)
    val cues: List<TimedCue>,   // 가는 길에 때맞춰 다시 알려 줄 안내
    val source: String,         // 근거: 예보 기준인지, 시연용 모의 상황인지
    val color: Color,
    val speakAutomatically: Boolean
)

private enum class DemoScene(val title: String) { STORM("비바람"), WIND("강풍"), RAIN("폭우"), TUNNEL("터널 출구") }

private fun levelColor(level: GilmalRiskLevel) = when (level) {
    GilmalRiskLevel.LEVEL_1_ATTENTION -> GilmalColors.Attention
    GilmalRiskLevel.LEVEL_2_CAUTION -> GilmalColors.Caution
    GilmalRiskLevel.LEVEL_3_SEVERE -> GilmalColors.Severe
}

@Composable
fun DriveHomeScreen(
    mode: UserMode,
    onModeChange: (UserMode) -> Unit,
    route: RouteContext,
    distanceText: String?,
    navigationReady: Boolean,
    navigationStatus: String,
    onRefreshDestination: () -> Unit,
    checks: List<CheckRow>,
    voiceStatus: String,
    onPlayVoice: (String) -> Unit,
    onStopVoice: () -> Unit,
    onOpenTools: () -> Unit
) {
    var demoScene by remember { mutableStateOf<DemoScene?>(null) }

    val destinationName = route.destinationName
    val alert: DriveAlert = remember(mode, demoScene, route, checks) {
        val scene = demoScene
        if (scene != null) {
            val result = when (scene) {
                DemoScene.STORM -> RiskEngine.evaluateWind(avgWind = 22.0, maxWind = 27.0, mode = mode)
                DemoScene.WIND -> RiskEngine.evaluateWind(avgWind = 15.0, maxWind = 22.0, mode = mode)
                DemoScene.RAIN -> RiskEngine.evaluateRain(rain3h = 100.0, rain12h = 190.0, mode = mode)
                DemoScene.TUNNEL -> RiskEngine.evaluateWind(avgWind = 13.0, maxWind = 19.0, mode = mode, roadEnv = "터널 출구")
            }
            val line = demoLine(scene.name, mode)
            DriveAlert(
                badge = "${result.level.title} · ${scene.title}",
                message = line,
                items = emptyList(),
                speech = line,
                shortSpeech = line,
                cues = emptyList(),
                source = "시연용 모의 상황 · 실제 위험 정보가 아닙니다 · 안내 문장: 행정안전부 국민행동요령 기반",
                color = levelColor(result.level),
                speakAutomatically = true
            )
        } else {
            val b = buildRouteBriefing(mode, route, checks)
            val color = b.level?.let { levelColor(it) } ?: if (route.forecastReady) GilmalColors.Calm else GilmalColors.Unknown
            val source = if (route.forecastReady)
                (if (route.routeChecked) "경로 여러 지점의 '지나갈 시각' 예보" else "목적지 예보") +
                    "(Open-Meteo)와 내비 경로 정보 기준 · 실제 도로 상황과 다를 수 있습니다 · 안내 문장: 행정안전부 국민행동요령 기반"
            else route.forecastStatus
            DriveAlert(
                badge = b.headline,
                message = b.items.firstOrNull()?.text ?: "",
                items = b.items,
                speech = b.speech,
                shortSpeech = b.shortSpeech,
                cues = b.cues,
                source = source,
                color = color,
                speakAutomatically = b.level == GilmalRiskLevel.LEVEL_2_CAUTION || b.level == GilmalRiskLevel.LEVEL_3_SEVERE
            )
        }
    }

    // 위험 안내가 새로 바뀌면 자동으로 한 번 읽어 준다 (주의 이상 또는 시연 상황)
    LaunchedEffect(alert.badge, alert.shortSpeech) {
        if (alert.speakAutomatically) onPlayVoice(alert.shortSpeech)
    }

    // 가는 길 안내 예약: 비 구간 5분 전, 해 지기 15분 전, 휴게 시간에 그 항목만 짧게
    LaunchedEffect(destinationName, alert.cues.map { it.label }) {
        var elapsed = 0L
        for (cue in alert.cues) {
            val waitMin = (cue.minutesFromNow - elapsed).coerceAtLeast(0)
            kotlinx.coroutines.delay(waitMin * 60_000L)
            elapsed = cue.minutesFromNow
            onPlayVoice(cue.speech)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(GilmalColors.Background)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        // ── 상단 바: 브랜드 + 운전자 유형
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.size(10.dp).background(alert.color, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text("길말", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = GilmalColors.TextPrimary)
            Spacer(Modifier.width(8.dp))
            Text("도로 위험을 이해하기 쉬운 말로", fontSize = 11.sp, color = GilmalColors.TextMuted,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            ModeSegment(mode = mode, onModeChange = onModeChange)
        }

        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── 왼쪽: 목적지 · 출발 전 점검 (정차 중 확인)
            Column(
                modifier = Modifier.weight(0.4f).fillMaxHeight().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Panel {
                    SectionLabel("목적지")
                    if (destinationName != null) {
                        Text(destinationName, fontSize = 19.sp, fontWeight = FontWeight.Bold,
                            color = GilmalColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (distanceText != null) {
                            Spacer(Modifier.height(2.dp))
                            Text("남은 거리 $distanceText", fontSize = 13.sp, color = GilmalColors.TextSecondary)
                        }
                    } else {
                        Text("안내 중인 목적지 없음", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = GilmalColors.TextPrimary)
                        Text(navigationStatus, fontSize = 11.sp, color = GilmalColors.TextMuted, maxLines = 2)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "목적지 다시 확인",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (navigationReady) GilmalColors.Accent else GilmalColors.TextMuted,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = navigationReady, onClick = onRefreshDestination)
                            .padding(vertical = 4.dp)
                    )
                }

                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionLabel("출발 전 점검", Modifier.weight(1f))
                        val needCheck = checks.count { it.state == CheckState.CHECK }
                        if (needCheck > 0) Pill("확인 $needCheck", GilmalColors.Caution)
                    }
                    Spacer(Modifier.height(4.dp))
                    checks.forEachIndexed { index, row ->
                        if (index > 0) HorizontalDivider(color = GilmalColors.Outline.copy(alpha = 0.5f), thickness = 0.5.dp)
                        CheckLine(row)
                    }
                }
            }

            // ── 오른쪽: 지금 안내 (운전 중 보는 영역)
            Column(
                modifier = Modifier.weight(0.6f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(GilmalColors.Surface)
                        .border(1.dp, alert.color.copy(alpha = 0.45f), RoundedCornerShape(18.dp))
                ) {
                    Box(Modifier.fillMaxHeight().width(8.dp).background(alert.color))
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 18.dp, vertical = 14.dp)
                    ) {
                        Pill(alert.badge, alert.color, large = true)
                        Spacer(Modifier.height(12.dp))
                        if (alert.items.isEmpty()) {
                            Text(
                                alert.message,
                                fontSize = 23.sp,
                                lineHeight = 33.sp,
                                fontWeight = FontWeight.Bold,
                                color = GilmalColors.TextPrimary
                            )
                        } else {
                            alert.items.forEachIndexed { index, item ->
                                if (index > 0) Spacer(Modifier.height(10.dp))
                                Text(item.title, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = GilmalColors.Accent)
                                Text(
                                    item.text,
                                    fontSize = if (index == 0) 20.sp else 16.sp,
                                    lineHeight = if (index == 0) 28.sp else 23.sp,
                                    fontWeight = if (index == 0) FontWeight.Bold else FontWeight.SemiBold,
                                    color = GilmalColors.TextPrimary
                                )
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(alert.source, fontSize = 11.sp, color = GilmalColors.TextMuted)
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onPlayVoice(alert.shortSpeech) },
                        modifier = Modifier.weight(2f).height(54.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = GilmalColors.Accent)
                    ) { Text(if (demoScene == null) "▶  출발 전 안내 듣기" else "▶  다시 듣기", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White) }
                    if (demoScene == null && alert.speech != alert.shortSpeech) {
                        Button(
                            onClick = { onPlayVoice(alert.speech) },
                            modifier = Modifier.weight(0.9f).height(54.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = GilmalColors.AccentSoft)
                        ) { Text("자세히", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White) }
                    }
                    Button(
                        onClick = onStopVoice,
                        modifier = Modifier.weight(1f).height(54.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = GilmalColors.SurfaceHigher)
                    ) { Text("■  멈춤", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = GilmalColors.TextPrimary) }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // ── 하단 바: 시연 상황 · 음성 상태 · 테스트 도구
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("시연", fontSize = 11.sp, color = GilmalColors.TextMuted)
            DemoScene.entries.forEach { scene ->
                Chip(scene.title, selected = demoScene == scene) { demoScene = scene }
            }
            if (demoScene != null) Chip("해제", selected = false) { demoScene = null; onStopVoice() }
            Text(
                voiceStatus,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                color = GilmalColors.TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f).padding(horizontal = 6.dp)
            )
            Chip("테스트 도구 ›", selected = false, onClick = onOpenTools)
        }
    }
}

/** 가족 / 초보운전 / 화물차 선택 (하나로 이어진 세그먼트 버튼) */
@Composable
private fun ModeSegment(mode: UserMode, onModeChange: (UserMode) -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(GilmalColors.Surface)
            .border(1.dp, GilmalColors.Outline, RoundedCornerShape(12.dp))
            .padding(3.dp)
    ) {
        UserMode.entries.forEach { m ->
            val selected = m == mode
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (selected) GilmalColors.Accent else Color.Transparent)
                    .clickable { onModeChange(m) }
                    .padding(horizontal = 14.dp, vertical = 7.dp)
            ) {
                Text(
                    m.title,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = if (selected) Color.White else GilmalColors.TextSecondary
                )
            }
        }
    }
}

@Composable
private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(GilmalColors.Surface)
            .border(1.dp, GilmalColors.Outline.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        content = content
    )
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
        color = GilmalColors.TextMuted, letterSpacing = 0.5.sp)
}

@Composable
private fun Pill(text: String, color: Color, large: Boolean = false) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.18f))
            .padding(horizontal = if (large) 12.dp else 8.dp, vertical = if (large) 5.dp else 2.dp)
    ) {
        Box(Modifier.size(if (large) 8.dp else 6.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = if (large) 15.sp else 11.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) GilmalColors.AccentSoft else GilmalColors.Surface)
            .border(1.dp, if (selected) GilmalColors.Accent else GilmalColors.Outline, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            color = if (selected) Color.White else GilmalColors.TextSecondary)
    }
}

@Composable
private fun CheckLine(row: CheckRow) {
    val (color, label) = when (row.state) {
        CheckState.OK -> GilmalColors.Calm to "정상"
        CheckState.CHECK -> GilmalColors.Caution to "확인 필요"
        CheckState.UNKNOWN -> GilmalColors.Unknown to "정보 없음"
    }
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(row.label, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = GilmalColors.TextPrimary)
            Text(row.value, fontSize = 11.sp, color = GilmalColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Pill(label, color)
    }
}
