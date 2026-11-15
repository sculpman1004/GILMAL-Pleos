package com.gilmal.app

enum class GilmalRiskLevel(val title: String) {
    LEVEL_1_ATTENTION("1단계 관심"),
    LEVEL_2_CAUTION("2단계 주의"),
    LEVEL_3_SEVERE("3단계 심각")
}

enum class IceRiskState(val title: String) {
    HAS_RISK("결빙 위험 있음"),
    NO_RISK("결빙 위험 없음"),
    UNKNOWN("정보 부족");
}

data class DummyData(
    val avgWind: Double? = null,
    val maxWind: Double? = null,
    val rain3h: Double? = null,
    val rain12h: Double? = null,
    val iceState: IceRiskState = IceRiskState.UNKNOWN,
    val description: String = ""
)

data class RiskResult(
    val level: GilmalRiskLevel,
    val rationale: String,
    val guideMessage: String,
    val disclaimer: String = "※ 본 단계는 길말 자체 분류이며 공식 재난 등급이 아닙니다."
)

object RiskEngine {
    // [출처: 기상청 날씨누리 기상특보 발표기준 (개발용 참고값)]
    // 공식 기준과 다르거나 최신 여부를 확인하지 못한 값은 검증이 필요합니다.

    fun evaluateWind(avgWind: Double?, maxWind: Double?, mode: UserMode, roadEnv: String = "일반 도로"): RiskResult {
        if (avgWind == null && maxWind == null) {
            throw IllegalArgumentException("풍속 데이터가 없습니다. (최소 1개 입력 필수)")
        }
        val validAvg = avgWind ?: 0.0
        val validMax = maxWind ?: 0.0

        // 경보 기준 우선 평가 (OR 조건)
        val level = if (validAvg >= 21.0 || validMax >= 26.0) {
            GilmalRiskLevel.LEVEL_3_SEVERE
        } else if (validAvg >= 14.0 || validMax >= 20.0) {
            GilmalRiskLevel.LEVEL_2_CAUTION
        } else {
            GilmalRiskLevel.LEVEL_1_ATTENTION
        }

        val rationale = "공식 강풍 특보 기준 수치 상당 여부 판별 (평균: ${avgWind ?: "-"}m/s, 순간: ${maxWind ?: "-"}m/s, 환경: $roadEnv)"
        val msg = getWindVoiceGuide(level, mode, roadEnv)
        return RiskResult(level, rationale, msg)
    }

    fun evaluateRain(rain3h: Double?, rain12h: Double?, mode: UserMode, roadEnv: String = "일반 도로"): RiskResult {
        if (rain3h == null && rain12h == null) {
            throw IllegalArgumentException("강수량 데이터가 없습니다. (최소 1개 입력 필수)")
        }
        val valid3h = rain3h ?: 0.0
        val valid12h = rain12h ?: 0.0

        val level = if (valid3h >= 90.0 || valid12h >= 180.0) {
            GilmalRiskLevel.LEVEL_3_SEVERE
        } else if (valid3h >= 60.0 || valid12h >= 110.0) {
            GilmalRiskLevel.LEVEL_2_CAUTION
        } else {
            GilmalRiskLevel.LEVEL_1_ATTENTION
        }

        val rationale = "공식 호우 특보 기준 수치 상당 여부 판별 (3시간: ${rain3h ?: "-"}mm, 12시간: ${rain12h ?: "-"}mm, 환경: $roadEnv)"
        val msg = getRainVoiceGuide(level, mode)
        return RiskResult(level, rationale, msg)
    }

    fun evaluateIce(state: IceRiskState, mode: UserMode, roadEnv: String = "일반 도로"): RiskResult {
        // 결빙은 임의의 공식 수치 기준을 만들지 않음
        val level = if (state == IceRiskState.HAS_RISK) GilmalRiskLevel.LEVEL_2_CAUTION else GilmalRiskLevel.LEVEL_1_ATTENTION
        val rationale = "결빙 위험 판단 (상태: ${state.title}, 환경: $roadEnv) - ※ 시뮬레이션 데이터이며 실제 도로 상태가 아님"
        val msg = getIceVoiceGuide(state, mode, roadEnv)
        return RiskResult(level, rationale, msg)
    }

    private fun getWindVoiceGuide(level: GilmalRiskLevel, mode: UserMode, env: String): String {
        val envPrefix = if (env == "터널 출구") "터널을 나가기 전부터 속도를 미리 낮춰주세요. 횡풍 위험 구간입니다. " else ""
        return when (mode) {
            UserMode.FAMILY -> when (level) {
                GilmalRiskLevel.LEVEL_1_ATTENTION -> "${envPrefix}바람이 조금 불어요. 창문을 꼭 닫고 조심해서 가요!"
                GilmalRiskLevel.LEVEL_2_CAUTION -> "${envPrefix}우산이 뒤집어질 만큼 바람이 세요! 두 손으로 핸들 꽉 잡아요!"
                GilmalRiskLevel.LEVEL_3_SEVERE -> "${envPrefix}태풍처럼 바람이 엄청나요! 밖은 위험하니 안전한 곳에 차를 세우고 쉬어가요!"
            }
            UserMode.BEGINNER -> when (level) {
                GilmalRiskLevel.LEVEL_1_ATTENTION -> "${envPrefix}바람이 다소 불고 있습니다. 속도를 조금 줄이세요."
                GilmalRiskLevel.LEVEL_2_CAUTION -> "${envPrefix}강한 바람으로 차가 옆으로 밀릴 수 있어요. 당황하지 마시고 핸들을 두 손으로 잡고 속도를 천천히 줄이세요. 급하게 방향을 바꾸지 마세요."
                GilmalRiskLevel.LEVEL_3_SEVERE -> "${envPrefix}위험한 강풍이 불고 있습니다. 운전이 어려울 수 있으니 무리하지 마시고 가까운 휴게소나 안전한 장소에 서서히 정차하세요."
            }
            UserMode.CARGO -> when (level) {
                GilmalRiskLevel.LEVEL_1_ATTENTION -> "${envPrefix}바람이 불고 있습니다. 적재물 상태를 확인하며 주행하세요."
                GilmalRiskLevel.LEVEL_2_CAUTION -> "${envPrefix}강풍 주의 수준입니다. 탑차나 호루 차량은 측풍에 밀릴 수 있으니 즉시 서행하세요."
                GilmalRiskLevel.LEVEL_3_SEVERE -> "${envPrefix}강풍 심각 수준입니다. 차량 전복 위험이 매우 크므로 무리한 운행을 중단하고 안전한 장소로 대피하세요."
            }
        }
    }

    private fun getRainVoiceGuide(level: GilmalRiskLevel, mode: UserMode): String {
        return when (mode) {
            UserMode.FAMILY -> when (level) {
                GilmalRiskLevel.LEVEL_1_ATTENTION -> "비가 부슬부슬 내려요. 와이퍼 켜고 천천히 가요!"
                GilmalRiskLevel.LEVEL_2_CAUTION -> "하늘에서 물폭탄이 떨어져요. 앞이 안 보이니 아주 천천히 안전하게 가요!"
                GilmalRiskLevel.LEVEL_3_SEVERE -> "비가 쏟아져서 길이 강물처럼 변했어요! 당장 물 없는 안전한 곳에 멈춰서 기다려요!"
            }
            UserMode.BEGINNER -> when (level) {
                GilmalRiskLevel.LEVEL_1_ATTENTION -> "비가 내립니다. 노면이 젖어 있으니 부드럽게 감속하세요."
                GilmalRiskLevel.LEVEL_2_CAUTION -> "비가 많이 와 미끄러지기 쉽습니다. 시야 확보를 위해 와이퍼를 켜고, 앞차와의 거리를 충분히 띄우고 브레이크는 천천히 밟아주세요."
                GilmalRiskLevel.LEVEL_3_SEVERE -> "앞이 보이지 않는 심각한 집중호우입니다. 급제동은 미끄러지기 쉬우니 비상등을 켠 후 안전한 곳에 서서히 정차하세요."
            }
            UserMode.CARGO -> when (level) {
                GilmalRiskLevel.LEVEL_1_ATTENTION -> "우천 시 제동거리가 길어집니다. 주의하세요."
                GilmalRiskLevel.LEVEL_2_CAUTION -> "폭우 구간 진입. 빗길 제동거리가 길어지므로 풋브레이크 대신 엔진 브레이크를 적극 활용하세요."
                GilmalRiskLevel.LEVEL_3_SEVERE -> "폭우 심각 수준. 수막현상으로 대형 사고 위험이 높습니다. 운행을 즉시 중단하십시오."
            }
        }
    }

    private fun getIceVoiceGuide(state: IceRiskState, mode: UserMode, env: String): String {
        if (state != IceRiskState.HAS_RISK) {
            return "현재 결빙 위험이 발견되지 않았습니다. 그래도 겨울철에는 늘 조심하세요."
        }
        val envPrefix = if (env == "교량") "다리 위는 땅보다 훨씬 차가워요. " else if (env == "터널 출구") "터널 출구는 특히 얼기 쉬워요. " else ""
        return when (mode) {
            UserMode.FAMILY -> "${envPrefix}바닥이 스케이트장처럼 미끄러워요. 조심조심 걸어가듯 천천히 가요!"
            UserMode.BEGINNER -> "${envPrefix}바닥이 얼어있어 매우 위험합니다. 속도를 최대한 줄이고 급브레이크나 급격한 핸들 조작은 절대 피하세요."
            UserMode.CARGO -> "${envPrefix}도로 결빙 상태입니다. 대형 차량은 미끄러지면 큰 사고로 이어지니 반드시 서행하고 앞차와의 거리를 크게 확보하세요."
        }
    }

    // A/B 테스트용: 강도에 따른 가상 공통 데이터 생성
    fun generateDummyDataForLevel(type: RiskType, level: GilmalRiskLevel): DummyData {
        return when (type) {
            RiskType.WIND -> when (level) {
                GilmalRiskLevel.LEVEL_1_ATTENTION -> DummyData(avgWind = 10.0, maxWind = 15.0, description = "평균풍속 10m/s, 순간풍속 15m/s")
                GilmalRiskLevel.LEVEL_2_CAUTION -> DummyData(avgWind = 15.0, maxWind = 21.0, description = "평균풍속 15m/s, 순간풍속 21m/s")
                GilmalRiskLevel.LEVEL_3_SEVERE -> DummyData(avgWind = 22.0, maxWind = 27.0, description = "평균풍속 22m/s, 순간풍속 27m/s")
            }
            RiskType.RAIN -> when (level) {
                GilmalRiskLevel.LEVEL_1_ATTENTION -> DummyData(rain3h = 30.0, rain12h = 50.0, description = "3시간 30mm, 12시간 50mm")
                GilmalRiskLevel.LEVEL_2_CAUTION -> DummyData(rain3h = 65.0, rain12h = 120.0, description = "3시간 65mm, 12시간 120mm")
                GilmalRiskLevel.LEVEL_3_SEVERE -> DummyData(rain3h = 95.0, rain12h = 190.0, description = "3시간 95mm, 12시간 190mm")
            }
            RiskType.ICE -> when (level) {
                GilmalRiskLevel.LEVEL_1_ATTENTION -> DummyData(iceState = IceRiskState.NO_RISK, description = "결빙 위험 없음")
                GilmalRiskLevel.LEVEL_2_CAUTION, GilmalRiskLevel.LEVEL_3_SEVERE -> DummyData(iceState = IceRiskState.HAS_RISK, description = "결빙 위험 관측됨") // 결빙은 심각 구분이 명확하지 않으므로 주의 이상은 위험으로 통일
            }
        }
    }

    // A/B 테스트용: 수치 중심의 일반적 경고 예시 생성 (A 음성)
    fun getStandardWarning(type: RiskType, level: GilmalRiskLevel, data: DummyData): String {
        val cautionMsg = "일반적인 기상 경고 예시입니다." // 공식 내비 문구가 아님을 암시
        return when (type) {
            RiskType.WIND -> when (level) {
                GilmalRiskLevel.LEVEL_1_ATTENTION -> "풍속 안내. ${data.description}가 관측되었습니다. 안전 운전 하십시오."
                GilmalRiskLevel.LEVEL_2_CAUTION -> "강풍 주의 경고. 강풍 주의보 기준에 도달했습니다. (${data.description}) 감속 운행 바랍니다."
                GilmalRiskLevel.LEVEL_3_SEVERE -> "강풍 심각 경고. 강풍 경보 수준입니다. (${data.description}) 즉각적인 안전 확보가 필요합니다."
            }
            RiskType.RAIN -> when (level) {
                GilmalRiskLevel.LEVEL_1_ATTENTION -> "강우 안내. ${data.description}의 비가 내리고 있습니다."
                GilmalRiskLevel.LEVEL_2_CAUTION -> "호우 주의 경고. 호우 주의보 기준 수치입니다. (${data.description}) 빗길 미끄럼에 주의하십시오."
                GilmalRiskLevel.LEVEL_3_SEVERE -> "호우 심각 경고. 호우 경보 수준입니다. (${data.description}) 집중호우로 인한 위험이 예상됩니다."
            }
            RiskType.ICE -> when (level) {
                GilmalRiskLevel.LEVEL_1_ATTENTION -> "현재 노면 결빙 위험은 낮습니다."
                GilmalRiskLevel.LEVEL_2_CAUTION, GilmalRiskLevel.LEVEL_3_SEVERE -> "노면 결빙 주의. 결빙 현상이 발생할 수 있으니 속도를 줄여 운행하십시오."
            }
        } + " $cautionMsg"
    }
}
