package com.gilmal.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ───────────────────────────────────────────────────────────────
// A/B 음성 비교 (정차 상태·실내 평가 전용)
// - A: 수치와 위험 수준 중심의 일반 경고 '예시' (실제 내비게이션 공식 문구 아님)
// - B: 길말 안내 = 체감 표현(기상청 체감 안내 기준) + 국민행동요령 행동, 운전자 유형별 말투
// - A와 B는 같은 가상 기상 데이터(RiskEngine.generateDummyDataForLevel)를 사용한다
// - 평가 결과는 기기 내부(SharedPreferences)에 저장되어 앱을 다시 켜도 남는다
// ───────────────────────────────────────────────────────────────

/** A: 비교용 수치 중심 경고 예시 (음성에는 '예시입니다' 꼬리말을 넣지 않아 공정하게 비교) */
fun abStandardLine(type: RiskType, level: GilmalRiskLevel, data: DummyData): String =
    RiskEngine.getStandardWarning(type, level, data).removeSuffix(" 일반적인 기상 경고 예시입니다.").trim()

/** B: 길말 생활형 안내 — 같은 가상 데이터에서 체감 단계와 위험 단계를 함께 반영 */
fun abGilmalLine(type: RiskType, level: GilmalRiskLevel, mode: UserMode, data: DummyData): String = when (type) {
    RiskType.WIND -> {
        val ms = data.avgWind ?: data.maxWind ?: 0.0
        val base = windFeel(mode, ms)
        if (level == GilmalRiskLevel.LEVEL_3_SEVERE) base + " " + severeTail(mode) else base
    }
    RiskType.RAIN -> {
        // 3시간 누적을 시간당으로 환산해 체감 단계를 고른다
        val perHour = (data.rain3h ?: ((data.rain12h ?: 0.0) / 12.0) * 3.0) / 3.0
        rainFeel(mode, perHour)
    }
    RiskType.ICE -> when (data.iceState) {
        IceRiskState.HAS_RISK -> iceLine(mode)
        IceRiskState.NO_RISK -> when (mode) {
            UserMode.FAMILY -> "지금은 길이 얼었다는 정보는 없어요. 그래도 다리 위와 그늘진 길은 먼저 얼 수 있으니 조심해서 가요."
            UserMode.BEGINNER -> "지금은 결빙 정보가 없어요. 그래도 다리 위와 그늘진 곳은 먼저 얼 수 있으니 그곳에서는 속도를 줄이세요."
            UserMode.CARGO -> "지금은 결빙 정보가 없습니다. 다리 위와 그늘진 구간은 먼저 얼 수 있으니 짐을 실은 차는 미리 속도를 줄이세요."
        }
        IceRiskState.UNKNOWN -> "길이 얼었는지 정보가 부족해요. 확인되지 않았으니 다리 위와 그늘진 곳에서는 천천히 가세요."
    }
}

private fun severeTail(mode: UserMode) = when (mode) {
    UserMode.FAMILY -> "지금은 무리해서 가지 말고, 안전한 곳에서 잠시 쉬었다 가요."
    UserMode.BEGINNER -> "무리해서 가지 말고 휴게소 같은 안전한 곳에서 잠시 기다리세요."
    UserMode.CARGO -> "무리한 운행은 멈추고 안전한 곳에서 기다리세요."
}

/** 평가 결과 저장소 (기기 내부) */
object AbStore {
    private const val PREFS = "gilmal_ab_study"
    private const val KEY = "records"

    fun load(context: Context): List<EvaluationRecord> = try {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        val arr = JSONArray(raw)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            EvaluationRecord(
                timestamp = o.optLong("timestamp"),
                riskType = o.optString("riskType"),
                userMode = o.optString("userMode"),
                intensity = o.optString("intensity"),
                evaluatedTarget = o.optString("evaluatedTarget"),
                q1Risk = o.optString("q1Risk"),
                q2Action = o.optString("q2Action"),
                score = o.optInt("score")
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun save(context: Context, records: List<EvaluationRecord>) {
        val arr = JSONArray()
        records.forEach { r ->
            arr.put(JSONObject().apply {
                put("timestamp", r.timestamp)
                put("riskType", r.riskType)
                put("userMode", r.userMode)
                put("intensity", r.intensity)
                put("evaluatedTarget", r.evaluatedTarget)
                put("q1Risk", r.q1Risk)
                put("q2Action", r.q2Action)
                put("score", r.score)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    fun toCsv(records: List<EvaluationRecord>): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA)
        fun q(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
        val sb = StringBuilder("평가시각,평가대상,위험유형,사용자모드,위험강도,위험인지(Q1),행동인지(Q2),이해도점수(Q3)\n")
        records.forEach { r ->
            sb.append(listOf(q(fmt.format(Date(r.timestamp))), q(r.evaluatedTarget), q(r.riskType), q(r.userMode),
                q(r.intensity), q(r.q1Risk), q(r.q2Action), r.score.toString()).joinToString(",")).append('\n')
        }
        return sb.toString()
    }

    /** 앱 전용 폴더에 CSV 파일로 저장하고 경로를 돌려준다 (adb pull 또는 기기 파일 앱으로 확인) */
    fun exportFile(context: Context, records: List<EvaluationRecord>): String? = try {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val file = File(dir, "gilmal_ab_results.csv")
        // 엑셀에서 한글이 깨지지 않도록 BOM 포함
        file.writeText("﻿" + toCsv(records), Charsets.UTF_8)
        file.absolutePath
    } catch (e: Exception) {
        null
    }

    /** A/B 비교 요약: 대상별 응답 수와 평균 이해도 */
    fun summary(records: List<EvaluationRecord>): String {
        fun part(label: String, key: String): String {
            val rs = records.filter { it.evaluatedTarget.startsWith(key) }
            return if (rs.isEmpty()) "$label 0건" else String.format(Locale.KOREA, "%s %d건 · 평균 %.1f점", label, rs.size, rs.map { it.score }.average())
        }
        return part("A(일반)", "A") + "   |   " + part("B(길말)", "B")
    }
}
