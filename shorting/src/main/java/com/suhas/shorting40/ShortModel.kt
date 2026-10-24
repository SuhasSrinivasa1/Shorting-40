package com.suhas.shorting40

import java.time.LocalDate
import kotlin.math.*

data class Candle(val date: LocalDate?, val open: Double, val high: Double, val low: Double,
                  val close: Double, val volume: Double)
data class Quote(val ltp: Double, val bid: Double?, val ask: Double?, val dayChange: Double?,
                 val marketCap: Double?, val high52: Double?, val low52: Double?,
                 val high: Double?, val low: Double?, val buyQty: Double?, val sellQty: Double?)
data class Factor(val name: String, val family: String, val raw: Double?, val score: Double)
data class Signal(val symbol: String, val entry: Double, val target: Double, val stop: Double,
                  val confidence: Double, val direction: Double, val familyScore: Double,
                  val available: Int, val strategy: String, val tier: String, val reasons: List<String>,
                  val factors: List<Factor>, val at: Long = System.currentTimeMillis())

/** Deterministic 40-factor SHORT engine. Confidence is a heuristic score, not a probability. */
object ShortFactorEngine {
    const val FACTOR_COUNT = 40
    const val MIN_CONFIDENCE = .63
    const val MIN_DIRECTION = .28
    const val MAX_SPREAD_BPS = 40.0
    const val MIN_RVOL = 1.05
    private fun clip(n: Double) = n.coerceIn(-1.0, 1.0)
    private fun sc(n: Double?, scale: Double) = n?.takeIf { it.isFinite() }?.let { clip(it / scale) } ?: 0.0
    private fun inv(n: Double?, scale: Double) = sc(n?.let { -it }, scale)
    private fun yes(b: Boolean?) = when (b) { true -> 1.0; false -> -1.0; null -> 0.0 }
    private fun ctr(n: Double?) = n?.let { clip(2*it-1) } ?: 0.0

    fun evaluate(symbol: String, quote: Quote, history: List<Candle>, bearishBreadth: Boolean): Signal {
        val bars = history.filter { listOf(it.open,it.high,it.low,it.close,it.volume).all(Double::isFinite) &&
                it.low > 0 && it.high >= it.low && it.volume >= 0 }
        val closes = bars.map { it.close }
        val last = bars.lastOrNull(); val prev = bars.getOrNull(bars.size-2)
        val price = quote.ltp
        val atr = atr(bars)
        val ema9=ema(closes,9); val ema20=ema(closes,20); val ema50=ema(closes,50)
        val ema9p=ema(closes.dropLast(1),9); val ema20p=ema(closes.dropLast(1),20)
        val vwap=vwap(bars); val vwapP=vwap(bars.dropLast(1))
        val macd=macd(closes); val macdP=macd(closes.dropLast(1))
        val rsi=rsi(closes); val rsiP=rsi(closes.dropLast(1))
        val spread=if (quote.bid != null && quote.ask != null && quote.bid > 0 &&
            quote.ask >= quote.bid && price > 0) (quote.ask-quote.bid)/price*10000 else null
        val rvol=if (bars.size > 6) bars.dropLast(1).takeLast(20).map { it.volume }.average()
            .takeIf { it > 0 }?.let { (last?.volume ?: 0.0)/it } else null
        val recent=bars.takeLast(10); val volume=recent.sumOf { it.volume }
        val redShare=if (volume > 0) recent.filter { it.close < it.open }.sumOf { it.volume }/volume else null
        val baseVolume=bars.dropLast(1).takeLast(3).takeIf { it.isNotEmpty() }?.map { it.volume }?.average()
        val redAcceleration=if (baseVolume != null && baseVolume > 0 && last != null && last.close < last.open)
            last.volume/baseVolume-1 else null
        val book=if (quote.buyQty != null && quote.sellQty != null && quote.buyQty+quote.sellQty > 0)
            (quote.buyQty-quote.sellQty)/(quote.buyQty+quote.sellQty) else null
        val range=if (last != null) last.high-last.low else null
        val body=if (last != null) last.close-last.open else null
        val atrPct=if (atr != null && price > 0) atr/price*100 else null
        val previous20=bars.dropLast(1).takeLast(20); val donchianLow=previous20.minOfOrNull { it.low }
        val date=last?.date; val today=if (date == null) emptyList() else bars.filter { it.date == date }
        val prevDate=bars.lastOrNull { it.date != null && date != null && it.date < date }?.date
        val prevDay=if (prevDate == null) emptyList() else bars.filter { it.date == prevDate }
        val prevDayLow=prevDay.minOfOrNull { it.low }; val prevDayClose=prevDay.lastOrNull()?.close
        val bounce=if (bars.size >= 3) {
            val a=bars[bars.size-3]; val b=bars[bars.size-2]
            b.close > a.close && last!!.close < b.close
        } else null
        val lowerHigh=if (last != null && prev != null) last.high < prev.high else null
        val lowerLow=if (last != null && prev != null) last.low < prev.low else null
        val trend=if (bars.size >= 7 && atr != null) (last!!.close-bars[bars.size-7].close)/atr else null
        val yearPos=if (quote.high52 != null && quote.low52 != null && quote.high52 > quote.low52)
            ((price-quote.low52)/(quote.high52-quote.low52)).coerceIn(0.0,1.0) else null
        val candlePos=if (last != null && range != null && range > 0) (last.close-last.low)/range else null
        val gap=if (today.isNotEmpty() && prevDayClose != null)
            (today.first().open/prevDayClose-1)*100 else null
        val lowDistance=quote.low?.takeIf { it>0 }?.let { (price/it-1)*100 }
        val downsideRoom=previous20.filter { it.low < price }.minOfOrNull { it.low }
            ?.let { (price-it)/price*100 }
        val votes=listOf(
            ema9 != null && ema20 != null && ema9 < ema20 && vwap != null && price < vwap,
            donchianLow != null && price < donchianLow,
            bounce == true,
            macd != null && macd < 0 && rvol != null && rvol >= MIN_RVOL
        )
        val direction=clip(votes.sumOf { if (it) 1.0 else -.5 }/2.5)
        val xs=ArrayList<Factor>(40)
        fun add(name:String, family:String, value:Double?, score:Double) {
            xs += Factor(name,family,value?.takeIf(Double::isFinite),clip(score))
        }
        fun bool(name:String, family:String, b:Boolean?) =
            add(name,family,b?.let { if(it) 1.0 else -1.0 },yes(b))
        val m="MOMENTUM"; val p="PARTICIPATION"; val s="STRUCTURE"
        // A: 14 momentum and trend factors.
        add("day_change",m,quote.dayChange,inv(quote.dayChange,2.5))
        bool("below_vwap",m,vwap?.let { price < it })
        bool("below_ema9",m,ema9?.let { price < it })
        bool("ema9_below_ema20",m,if(ema9!=null&&ema20!=null) ema9<ema20 else null)
        bool("ema20_below_ema50",m,if(ema20!=null&&ema50!=null) ema20<ema50 else null)
        val e9Slope=if(ema9!=null&&ema9p!=null&&atr!=null) (ema9-ema9p)/atr else null
        val e20Slope=if(ema20!=null&&ema20p!=null&&atr!=null) (ema20-ema20p)/atr else null
        val vwSlope=if(vwap!=null&&vwapP!=null&&atr!=null) (vwap-vwapP)/atr else null
        add("ema9_down_slope",m,e9Slope,inv(e9Slope,.2))
        add("ema20_down_slope",m,e20Slope,inv(e20Slope,.15))
        add("vwap_down_slope",m,vwSlope,inv(vwSlope,.15))
        add("negative_macd",m,macd,inv(macd,(atr?:1.0)*.2))
        val macdSlope=if(macd!=null&&macdP!=null) macd-macdP else null
        add("macd_falling",m,macdSlope,inv(macdSlope,(atr?:1.0)*.1))
        add("bearish_rsi",m,rsi,sc(rsi?.let { 50-it },20.0))
        val rsiSlope=if(rsi!=null&&rsiP!=null) rsi-rsiP else null
        add("rsi_falling",m,rsiSlope,inv(rsiSlope,10.0))
        add("negative_trend_slope",m,trend,inv(trend,2.0))
        val structure=if(lowerHigh!=null&&lowerLow!=null)
            (if(lowerHigh) .5 else -.5)+(if(lowerLow) .5 else -.5) else null
        add("bearish_hl_structure",m,structure,sc(structure,1.0))
        // B: 13 volume / execution-quality factors; healthy liquidity remains positive.
        add("relative_volume",p,rvol,sc(rvol?.let { it-1 },.8))
        add("red_volume_acceleration",p,redAcceleration,sc(redAcceleration,.8))
        add("red_volume_share",p,redShare,ctr(redShare))
        add("seller_book_pressure",p,book,inv(book,.5))
        add("tight_spread",p,spread,spread?.let { clip(1-it/45) }?:0.0)
        add("atr_capacity",p,atrPct,atrPct?.let { clip((it-.25)/.75) }?:0.0)
        val width=bollWidth(closes)
        add("bollinger_width",p,width,width?.let { clip((it-.010)/.025) }?:0.0)
        add("session_low_proximity",p,lowDistance,inv(lowDistance?.let { it-.6 },1.5))
        bool("failed_bounce",p,bounce)
        bool("donchian_low_break",p,donchianLow?.let { price < it })
        bool("opening15_low_break",p,today.firstOrNull()?.low?.let { price < it })
        bool("opening30_low_break",p,today.take(2).takeIf { it.size==2 }?.minOfOrNull { it.low }?.let { price < it })
        val impulse=if(range!=null&&range>0&&body!=null) body/range else null
        add("red_candle_impulse",p,impulse,inv(impulse,1.0))
        // C: 13 structure and context factors.
        add("year52_weakness",s,yearPos,-ctr(yearPos))
        add("market_cap_quality",s,quote.marketCap,
            quote.marketCap?.takeIf { it>0 }?.let { clip((ln(it)-15)/6) }?:0.0)
        bool("prior_day_low_break",s,prevDayLow?.let { price < it })
        bool("failed_support_retest",s,if(prevDayLow!=null&&last!=null)
            last.high>=prevDayLow&&last.close<prevDayLow else null)
        bool("lower_high",s,lowerHigh)
        bool("lower_low",s,lowerLow)
        val downClose=if(last!=null&&prev!=null) (last.close/prev.close-1)*100 else null
        add("negative_close_change",s,downClose,inv(downClose,1.0))
        add("candle_close_near_low",s,candlePos,-ctr(candlePos))
        add("bearish_gap",s,gap,inv(gap,1.0))
        val rangeAtr=if(range!=null&&atr!=null) range/atr else null
        add("bearish_range_atr",s,rangeAtr,
            if(body!=null&&body<0) rangeAtr?.let { clip((it-1)/1.5) }?:0.0 else 0.0)
        bool("pullback_rejection",s,bounce)
        add("downside_room",s,downsideRoom,sc(downsideRoom?.let { it-1 },2.0))
        add("strategy_vote_consensus",s,direction,direction)
        check(xs.size==FACTOR_COUNT) { "Expected 40 short factors, got ${xs.size}" }
        val families=listOf(m,p,s).map { family -> xs.filter { it.family==family }.map { it.score }.average() }
        val weights=if(bearishBreadth) listOf(1.15,1.05,.90) else listOf(.80,.90,1.20)
        val familyScore=families.indices.sumOf { families[it]*weights[it] }/weights.sum()
        val available=xs.count { it.raw!=null }
        val positives=families.count { it>=.08 }
        val confidence=(.5+.42*direction).coerceIn(.05,.95)
        val stopOffset=max(price*.0075,(atr?:price*.005)*1.1)
        val swing=bars.takeLast(4).maxOfOrNull { it.high }?.plus((atr?:price*.005)*.1)
        val stop=max(price+stopOffset,swing?:price+stopOffset)
        val target=price-max(price*.01,(stop-price)*1.5)
        val failures=mutableListOf<String>()
        if(!price.isFinite()||price<=100) failures+="price must exceed ₹100"
        if(bars.size<50) failures+="fewer than 50 valid candles"
        if(available<32) failures+="incomplete factors ($available/40)"
        if(spread==null||spread>MAX_SPREAD_BPS) failures+="spread unavailable/wide"
        if(rvol==null||rvol<MIN_RVOL) failures+="RVOL below 1.05"
        if(ema9==null||ema20==null||ema9>=ema20) failures+="EMA not bearish"
        if(vwap==null||price>=vwap) failures+="price not below VWAP"
        if(trend==null||trend>=0) failures+="no confirmed downtrend"
        if(rsi==null||rsi<25) failures+="RSI oversold/missing"
        if(positives<2||familyScore<.08) failures+="weak family consensus"
        if(confidence<MIN_CONFIDENCE||direction<MIN_DIRECTION) failures+="confidence below threshold"
        if(atrPct==null||atrPct*4<(price-target)/price*100*.75) failures+="inadequate ATR capacity"
        if(!target.isFinite()||target<=0) failures+="invalid downside target"
        return Signal(symbol,price,target,stop,confidence,direction,familyScore,available,
            when { bounce==true->"FAILED_BOUNCE"; donchianLow!=null&&price<donchianLow->"BREAKDOWN"; else->"DOWNTREND" },
            if(failures.isEmpty()) "PRIMARY" else "RESEARCH",failures,xs)
    }

    private fun ema(xs:List<Double>,period:Int):Double? {
        if(xs.size<period) return null
        var result=xs.take(period).average(); val a=2.0/(period+1)
        for(x in xs.drop(period)) result=a*x+(1-a)*result
        return result
    }
    private fun atr(xs:List<Candle>):Double? {
        if(xs.size<15) return null
        return (1 until xs.size).map { i->max(xs[i].high-xs[i].low,
            max(abs(xs[i].high-xs[i-1].close),abs(xs[i].low-xs[i-1].close))) }
            .takeLast(14).average().takeIf { it>0 }
    }
    private fun vwap(xs:List<Candle>):Double? {
        val c=xs.takeLast(26); val v=c.sumOf { it.volume }
        return if(v>0) c.sumOf { (it.high+it.low+it.close)/3*it.volume }/v else null
    }
    private fun macd(xs:List<Double>):Double? {
        if(xs.size<35) return null
        val line=(26..xs.size).mapNotNull { k->
            val x=xs.take(k); val a=ema(x,12); val b=ema(x,26)
            if(a!=null&&b!=null) a-b else null
        }
        val signal=ema(line,9)?:return null
        return line.last()-signal
    }
    private fun rsi(xs:List<Double>):Double? {
        if(xs.size<15) return null
        val diffs=xs.zipWithNext { a,b->b-a }.takeLast(14)
        val gain=diffs.sumOf { max(it,0.0) }/14
        val loss=diffs.sumOf { max(-it,0.0) }/14
        return if(loss==0.0) if(gain>0) 100.0 else 50.0 else 100-100/(1+gain/loss)
    }
    private fun bollWidth(xs:List<Double>):Double? {
        if(xs.size<20) return null
        val c=xs.takeLast(20); val mean=c.average().takeIf { it>0 }?:return null
        return 4*sqrt(c.sumOf { (it-mean).pow(2) }/20)/mean
    }
}

object ShortTrail {
    const val ARM=.55; const val LOCK=.50; const val STEP=.10; const val BUFFER=.05
    fun stop(entry:Double,lowest:Double,initialStop:Double):Double {
        require(entry>0&&lowest>0&&initialStop>entry)
        val favorable=(entry-lowest)/entry*100
        if(favorable+1e-9<ARM) return initialStop
        val steps=floor((favorable-ARM+1e-9)/STEP).toInt().coerceAtLeast(0)
        return min(initialStop,entry*(1-(LOCK+steps*STEP)/100))
    }
}
