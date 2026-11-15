package com.gilmal.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AbStudyTest {

    @Test
    fun a_and_b_use_same_dummy_data() {
        val data = RiskEngine.generateDummyDataForLevel(RiskType.WIND, GilmalRiskLevel.LEVEL_2_CAUTION)
        val a = abStandardLine(RiskType.WIND, GilmalRiskLevel.LEVEL_2_CAUTION, data)
        // A는 수치를 그대로 말하고, '예시입니다' 꼬리말은 음성에서 빠진다
        assertTrue(a.contains(data.description))
        assertFalse(a.contains("예시입니다"))
    }

    @Test
    fun b_changes_by_user_mode() {
        val data = RiskEngine.generateDummyDataForLevel(RiskType.RAIN, GilmalRiskLevel.LEVEL_2_CAUTION)
        val family = abGilmalLine(RiskType.RAIN, GilmalRiskLevel.LEVEL_2_CAUTION, UserMode.FAMILY, data)
        val beginner = abGilmalLine(RiskType.RAIN, GilmalRiskLevel.LEVEL_2_CAUTION, UserMode.BEGINNER, data)
        val cargo = abGilmalLine(RiskType.RAIN, GilmalRiskLevel.LEVEL_2_CAUTION, UserMode.CARGO, data)
        assertNotEquals(family, beginner)
        assertNotEquals(beginner, cargo)
    }

    @Test
    fun b_rain_wording_grows_with_intensity() {
        // 시간당 강수량 단계: 1mm 미만 / 1~5 / 5~15 / 15~30 / 30 이상
        assertNotEquals(rainFeel(UserMode.FAMILY, 2.0), rainFeel(UserMode.FAMILY, 35.0))
        assertTrue(rainFeel(UserMode.FAMILY, 35.0).contains("지하차도"))
    }

    @Test
    fun b_severe_wind_advises_not_to_force_driving() {
        val data = RiskEngine.generateDummyDataForLevel(RiskType.WIND, GilmalRiskLevel.LEVEL_3_SEVERE)
        val line = abGilmalLine(RiskType.WIND, GilmalRiskLevel.LEVEL_3_SEVERE, UserMode.CARGO, data)
        assertTrue(line.contains("무리한 운행"))
    }

    @Test
    fun b_ice_unknown_is_not_reported_as_safe() {
        val line = abGilmalLine(RiskType.ICE, GilmalRiskLevel.LEVEL_1_ATTENTION, UserMode.BEGINNER,
            DummyData(iceState = IceRiskState.UNKNOWN))
        assertTrue(line.contains("정보가 부족"))
    }

    @Test
    fun summary_counts_a_and_b_separately() {
        val records = listOf(
            EvaluationRecord(riskType = "강풍", userMode = "가족", intensity = "2단계 주의", evaluatedTarget = "A (일반)", q1Risk = "", q2Action = "", score = 2),
            EvaluationRecord(riskType = "강풍", userMode = "가족", intensity = "2단계 주의", evaluatedTarget = "B (길말)", q1Risk = "", q2Action = "", score = 5),
            EvaluationRecord(riskType = "강풍", userMode = "가족", intensity = "2단계 주의", evaluatedTarget = "B (길말)", q1Risk = "", q2Action = "", score = 4)
        )
        val s = AbStore.summary(records)
        assertTrue(s.contains("A(일반) 1건"))
        assertTrue(s.contains("B(길말) 2건"))
        assertEquals(1, s.split("|").size - 1)
    }
}
