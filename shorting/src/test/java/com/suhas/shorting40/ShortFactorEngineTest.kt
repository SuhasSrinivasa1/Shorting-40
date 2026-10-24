package com.suhas.shorting40

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class ShortFactorEngineTest {
    private fun candles(falling:Boolean):List<Candle> = (0 until 90).map { i ->
        val close=if(falling) 150.0-i*.42 else 110.0+i*.42
        Candle(LocalDate.of(2026,10,22).plusDays((i/25).toLong()),
            close+.15, close+.35,close-.35,close,10000.0+i*20)
    }
    private fun quote(price:Double)=Quote(price,price-.02,price+.02,-1.8,90000000.0,
        200.0,70.0,price+3,price-.3,2000.0,6000.0)

    @Test fun exactlyFortyAndThreeFamilies() {
        val down=candles(true)
        val s=ShortFactorEngine.evaluate("TEST",quote(down.last().close),down,true)
        assertEquals(40,s.factors.size)
        assertEquals(14,s.factors.count { it.family=="MOMENTUM" })
        assertEquals(13,s.factors.count { it.family=="PARTICIPATION" })
        assertEquals(13,s.factors.count { it.family=="STRUCTURE" })
        assertTrue(s.factors.all { it.score.isFinite() && it.score in -1.0..1.0 })
        assertTrue(s.target<s.entry)
        assertTrue(s.stop>s.entry)
        assertTrue(s.available>20)
    }
    @Test fun downtrendHasStrongerDirectionalScoreThanUptrend() {
        val down=candles(true); val up=candles(false)
        val a=ShortFactorEngine.evaluate("DOWN",quote(down.last().close),down,true)
        val b=ShortFactorEngine.evaluate("UP",quote(up.last().close),up,true)
        assertTrue("Bearish votes should outrank bullish votes",a.direction>b.direction)
        assertTrue(a.confidence>b.confidence)
    }
    @Test fun trailingIsInvertedAndNeverLoosens() {
        val original=101.0
        assertEquals(original,ShortTrail.stop(100.0,99.5,original),1e-9)
        assertEquals(99.5,ShortTrail.stop(100.0,99.45,original),1e-9)
        assertEquals(99.4,ShortTrail.stop(100.0,99.35,original),1e-9)
        assertTrue(ShortTrail.stop(100.0,99.25,original)<ShortTrail.stop(100.0,99.35,original))
    }
    @Test fun marketSessionIsWeekdayOnly() {
        assertFalse(ShortRuntime.scanningAllowed(LocalDateTime.of(2026,10,25,10,0)))
        assertTrue(ShortRuntime.scanningAllowed(LocalDateTime.of(2026,10,26,9,15)))
        assertFalse(ShortRuntime.scanningAllowed(LocalDateTime.of(2026,10,26,15,30)))
    }
}
