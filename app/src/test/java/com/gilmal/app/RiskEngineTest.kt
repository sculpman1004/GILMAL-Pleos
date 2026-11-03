package com.gilmal.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RiskEngineTest {

    @Test
    fun testWindSevereWarning() {
        val result1 = RiskEngine.evaluateWind(avgWind = 21.0, maxWind = 10.0, mode = UserMode.FAMILY)
        assertEquals(GilmalRiskLevel.LEVEL_3_SEVERE, result1.level)

        val result2 = RiskEngine.evaluateWind(avgWind = 5.0, maxWind = 27.0, mode = UserMode.FAMILY)
        assertEquals(GilmalRiskLevel.LEVEL_3_SEVERE, result2.level)
    }

    @Test
    fun testWindCautionAdvisory() {
        val result1 = RiskEngine.evaluateWind(avgWind = 15.0, maxWind = 10.0, mode = UserMode.FAMILY)
        assertEquals(GilmalRiskLevel.LEVEL_2_CAUTION, result1.level)

        val result2 = RiskEngine.evaluateWind(avgWind = 10.0, maxWind = 20.0, mode = UserMode.FAMILY)
        assertEquals(GilmalRiskLevel.LEVEL_2_CAUTION, result2.level)
    }

    @Test
    fun testWindAttention() {
        val result = RiskEngine.evaluateWind(avgWind = 13.9, maxWind = 19.9, mode = UserMode.FAMILY)
        assertEquals(GilmalRiskLevel.LEVEL_1_ATTENTION, result.level)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testWindInvalidInput() {
        RiskEngine.evaluateWind(avgWind = null, maxWind = null, mode = UserMode.FAMILY)
    }

    @Test
    fun testRainSevereWarning() {
        val result1 = RiskEngine.evaluateRain(rain3h = 95.0, rain12h = 0.0, mode = UserMode.FAMILY)
        assertEquals(GilmalRiskLevel.LEVEL_3_SEVERE, result1.level)
    }

    @Test
    fun testIceHasRisk() {
        val result = RiskEngine.evaluateIce(IceRiskState.HAS_RISK, UserMode.FAMILY)
        assertEquals(GilmalRiskLevel.LEVEL_2_CAUTION, result.level)
        assertTrue(result.guideMessage.contains("스케이트장"))
    }

    @Test
    fun testStandardWarningGeneration() {
        val data = RiskEngine.generateDummyDataForLevel(RiskType.WIND, GilmalRiskLevel.LEVEL_2_CAUTION)
        val warning = RiskEngine.getStandardWarning(RiskType.WIND, GilmalRiskLevel.LEVEL_2_CAUTION, data)
        assertTrue(warning.contains("주의보 기준"))
        assertTrue(warning.contains("일반적인 기상 경고 예시입니다."))
    }
}
