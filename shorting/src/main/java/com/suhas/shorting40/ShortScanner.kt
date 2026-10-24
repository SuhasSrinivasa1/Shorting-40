package com.suhas.shorting40

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Base64
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.KeyStore
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class ForecastRow(val signal: Signal, val adjustedScore: Double, val evidenceCount: Int)
data class ScanState(
    val running: Boolean = false,
    val status: String = "Not started",
    val lastScanMs: Long = 0,
    val masterCount: Int = 0,
    val coverage: Double = 0.0,
    val evaluated: Int = 0,
    val fixed: List<Signal> = emptyList(),
    val forecast: List<ForecastRow> = emptyList(),
    val totalObservations: Int = 0,
    val lastError: String? = null
)

/**
 * Hardware-backed (where available) AES-GCM token storage. No credentials in Android backup,
 * logs, exported files or the repo. A Groww access token expires daily and is supplied by user.
 */
class AccessTokenVault(context: Context) {
    private val prefs = context.getSharedPreferences("shorting40_auth", Context.MODE_PRIVATE)
    private val alias = "shorting40_broker_token_key_v1"
    private val key: SecretKey
        get() {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey(alias, null) as? SecretKey)?.let { return it }
            val gen = KeyGenerator.getInstance("AES", "AndroidKeyStore")
            gen.init(android.security.keystore.KeyGenParameterSpec.Builder(alias,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                    android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes("GCM")
                .setEncryptionPaddings("NoPadding")
                .setKeySize(256)
                .build())
            return gen.generateKey()
        }
    fun save(value: String) {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) {
            prefs.edit().remove("token").apply()
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val encrypted = Base64.encodeToString(cipher.doFinal(trimmed.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        prefs.edit().putString("token", "$iv:$encrypted").apply()
    }
    fun load(): String? = runCatching {
        val pieces = prefs.getString("token", null)?.split(':') ?: return null
        require(pieces.size == 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key,
            GCMParameterSpec(128, Base64.decode(pieces[0], Base64.NO_WRAP)))
        String(cipher.doFinal(Base64.decode(pieces[1], Base64.NO_WRAP)), Charsets.UTF_8)
    }.getOrNull()
    fun configured(): Boolean = prefs.contains("token")
}

/** Enforces Groww's published market-data ceiling within this app only.
 * No quota is reserved for, or coordinated with, any other application. */
private class ApiPacer(private val perSecond: Int, private val perMinute: Int) {
    private val calls = ArrayDeque<Long>()
    suspend fun acquire() {
        while (true) {
            val now = System.currentTimeMillis()
            while (calls.isNotEmpty() && now-calls.first()>60000L) calls.removeFirst()
            val lastSecond = calls.count { now-it<1000L }
            if (lastSecond<perSecond && calls.size<perMinute) {
                calls.addLast(now)
                return
            }
            delay(125)
        }
    }
}

private val shorting40MarketDataPacer = ApiPacer(10,300)

private class GrowwMarketClient(private val token: String) {
    private val client=OkHttpClient.Builder().connectTimeout(8,TimeUnit.SECONDS)
        .readTimeout(15,TimeUnit.SECONDS).retryOnConnectionFailure(true).build()
    private fun json(url:String): JsonObject {
        val request=Request.Builder().url(url).header("Authorization","Bearer $token")
            .header("Accept","application/json").header("X-API-VERSION","1.0").build()
        client.newCall(request).execute().use { response ->
            if(!response.isSuccessful) error("Groww HTTP ${response.code}")
            val result=JsonParser.parseString(response.body?.string()?:error("No response")).asJsonObject
            if(result.get("status")?.asString!="SUCCESS") error(
                result.getAsJsonObject("error")?.get("message")?.asString ?:"Groww rejected data request")
            return result.get("payload")?.asJsonObject ?: error("Groww payload unavailable")
        }
    }
    private fun url(path:String, params:Map<String,String>):String {
        val b=("https://api.groww.in/v1/"+path).toHttpUrl().newBuilder()
        params.forEach { (key,value)->b.addQueryParameter(key,value) }
        return b.build().toString()
    }
    suspend fun ltps(symbols:List<String>):Map<String,Double> {
        livePacer.acquire()
        val payload=json(url("live-data/ltp",mapOf("segment" to "CASH",
            "exchange_symbols" to symbols.joinToString(",") { "NSE_$it" })))
        return payload.entrySet().mapNotNull { (name,value)->
            val ltp=runCatching { value.asDouble }.getOrNull()
            if(ltp!=null&&ltp.isFinite()&&ltp>=10) name.removePrefix("NSE_") to ltp else null
        }.toMap()
    }
    suspend fun quote(symbol:String):Quote {
        livePacer.acquire()
        val p=json(url("live-data/quote",mapOf("exchange" to "NSE",
            "segment" to "CASH","trading_symbol" to symbol)))
        fun n(key:String)=runCatching { p.get(key)?.takeUnless { it.isJsonNull }?.asDouble }.getOrNull()
        return Quote(
            n("last_price")?:error("Missing LTP for $symbol"),n("bid_price"),n("offer_price"),
            n("day_change_perc"),n("market_cap"),n("week_52_high"),n("week_52_low"),
            n("high"),n("low"),n("total_buy_quantity"),n("total_sell_quantity")
        )
    }
    suspend fun candles(symbol:String,now:LocalDateTime):List<Candle> {
        historicalPacer.acquire()
        val fmt=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val params=mapOf("exchange" to "NSE","segment" to "CASH",
            "groww_symbol" to "NSE-$symbol","candle_interval" to "15minute",
            "start_time" to now.minusDays(4).toLocalDate().atTime(9,15).format(fmt),
            "end_time" to now.format(fmt))
        val data=json(url("historical/candles",params))
        val array=data.getAsJsonArray("candles")?:return emptyList()
        return array.mapNotNull { element->runCatching {
            val row=element.asJsonArray
            if(row.size()<6) return@runCatching null
            val ts=row[0].asString
            val date=runCatching {
                if(ts.firstOrNull()?.isDigit()==true && ts.length>=10 && ts[4]=='-')
                    LocalDate.parse(ts.take(10))
                else {
                    val epoch=ts.toLong()
                    Instant.ofEpochMilli(if(epoch<100000000000L) epoch*1000 else epoch)
                        .atZone(ZoneId.of("Asia/Kolkata")).toLocalDate()
                }
            }.getOrNull()
            Candle(date,row[1].asDouble,row[2].asDouble,row[3].asDouble,
                row[4].asDouble,row[5].asDouble)
        }.getOrNull() }.filterNotNull()
    }
}

/** App-local scanner and observable state, with opt-in live short execution. */
object ShortRuntime {
    val state=MutableStateFlow(ScanState())
    private val india=ZoneId.of("Asia/Kolkata")
    private val gson=Gson()
    private var observations=mutableMapOf<String,MutableList<Long>>()
    private var openLtps=mutableMapOf<String,Double>()
    private var previous=mutableMapOf<String,Double>()
    private var historyDate:LocalDate?=null
    private val benchmark=mutableListOf<Signal>()
    fun token(context:Context)=AccessTokenVault(context)
    fun scanningAllowed(now:LocalDateTime):Boolean =
        now.dayOfWeek.value<=5 && !now.toLocalTime().isBefore(LocalTime.of(9,15)) &&
        now.toLocalTime().isBefore(LocalTime.of(15,30))
    private fun symbols(context:Context):List<String> {
        val prefs=context.getSharedPreferences("short_master",Context.MODE_PRIVATE)
        val old=prefs.getLong("updated",0L)
        if(old>0&&System.currentTimeMillis()-old<25L*24*3600000L) {
            val names=prefs.getString("symbols","").orEmpty().split('|').filter(String::isNotBlank)
            if(names.size>500) return names
        }
        val req=Request.Builder().url("https://nsearchives.nseindia.com/content/equities/EQUITY_L.csv")
            .header("User-Agent","Mozilla/5.0 Shorting40/1.0").build()
        val raw=OkHttpClient().newCall(req).execute().use { response->
            if(!response.isSuccessful) error("NSE master HTTP ${response.code}")
            response.body?.string()?:error("Empty symbol master")
        }
        val names=raw.lineSequence().drop(1).map { it.substringBefore(',').trim('"',' ','\r','\n') }
            .map(String::uppercase).filter { it.matches(Regex("[A-Z0-9&._-]{1,32}")) }
            .distinct().sorted().toList()
        check(names.size>500) { "NSE master insufficient: ${names.size} symbols" }
        prefs.edit().putString("symbols",names.joinToString("|"))
            .putLong("updated",System.currentTimeMillis()).apply()
        return names
    }
    private data class Candidate(val symbol:String,val price:Double,val pulse:Double,val session:Double)
    private fun shortlist(current:Map<String,Double>, now:LocalDateTime):Pair<List<String>,Set<String>> {
        val movers=current.mapNotNull { (symbol,px)->
            val p=previous[symbol]; val o=openLtps[symbol]
            if(p==null||o==null||px<=100) null else
                Candidate(symbol,px,(px/p-1)*100,(px/o-1)*100)
        }
        val ongoing=movers.filter { it.pulse<0&&it.session<0 }
            .sortedByDescending { -.65*it.session-.35*it.pulse }.take(20)
        val fresh=movers.filter { it.pulse<0&&it.session>=-.3 }
            .sortedBy { it.pulse }.take(8)
        val bounce=movers.filter { it.session<-.25&&it.pulse>=0 }
            .sortedBy { it.session }.take(12)
        val inShortlist=mutableListOf<String>()
        (ongoing+fresh+bounce).forEach { if(it.symbol !in inShortlist) inShortlist+=it.symbol }
        val discovered=inShortlist.toSet()
        // Deterministically rotate the eight fallback slots every 30 minutes.
        // LTP-only discovery cannot prove liquidity; detailed quote quality must decide.
        val rotationPool=movers.filter { it.session<0 && it.symbol !in discovered }
            .sortedBy { it.symbol }.map { it.symbol }
        val slot=(now.hour*60+now.minute)/30
        val liquidRotation=if(rotationPool.isEmpty()) emptyList() else
            (0 until minOf(8,rotationPool.size)).map { index->
                rotationPool[(slot*8+index)%rotationPool.size]
            }
        val remaining=movers.filter { it.symbol !in (inShortlist+liquidRotation) }
            .sortedByDescending { -.65*it.session-.35*it.pulse }.map { it.symbol }
        return (inShortlist+liquidRotation+remaining).distinct().take(48) to discovered
    }
    suspend fun scan(context:Context) {
        val now=LocalDateTime.now(india)
        if(!scanningAllowed(now)) { state.value=state.value.copy(status="NSE cash market closed");return }
        val token=token(context).load()
        if(token.isNullOrBlank()) { state.value=state.value.copy(status="Add Groww access token in Settings");return }
        state.value=state.value.copy(status="Scanning NSE equities",lastError=null)
        try {
            if(historyDate!=now.toLocalDate()) {
                observations.clear();openLtps.clear();previous.clear()
                historyDate=now.toLocalDate()
            }
            val master=symbols(context)
            val api=GrowwMarketClient(token)
            val current=mutableMapOf<String,Double>()
            var failures=0
            for(chunk in master.chunked(50)) {
                try { current.putAll(api.ltps(chunk));failures=0 }
                catch(ex:Exception) {
                    failures++
                    if(failures>=3) error("Three consecutive LTP batch failures: ${ex.message}")
                }
            }
            val coverage=current.size.toDouble()/master.size
            val previousCoverage=previous.size.toDouble()/master.size
            val ready=coverage>=.85&&previousCoverage>=.85
            if(!ready) {
                if(coverage>=.85) {
                    current.forEach { (k,v)->openLtps.putIfAbsent(k,v) }
                    previous=current
                }
                state.value=state.value.copy(status="Collecting valid LTP baseline",
                    masterCount=master.size,coverage=coverage,lastScanMs=System.currentTimeMillis())
                return
            }
            val (candidates,discovered)=shortlist(current,now)
            val bearishBreadth=current.count { (s,p)->openLtps[s]?.let { p<it }?:false } >
                current.size*.55
            val rows=mutableListOf<Signal>()
            for(symbol in candidates) {
                try {
                    val quote=api.quote(symbol)
                    val candle=api.candles(symbol,now)
                    rows+=ShortFactorEngine.evaluate(symbol,quote,candle,bearishBreadth)
                } catch (_:Exception) { /* Individual quote/history failures cannot approve a trade. */ }
            }
            val cutoff=System.currentTimeMillis()-20*60*1000
            val valid=rows.filter { it.tier=="PRIMARY" }
            val approved=valid.filter { evaluation->
                val times=observations.getOrPut(evaluation.symbol) { mutableListOf() }
                times.removeAll { it<cutoff }
                val prior=times.size
                times+=System.currentTimeMillis()
                prior >= if(evaluation.symbol in discovered) 1 else 2
            }.sortedWith(compareByDescending<Signal> { it.confidence }
                .thenByDescending { it.familyScore })
            val oldSymbols=state.value.fixed.map { it.symbol }.toSet()
            val oldForecastSymbols=state.value.forecast.map { it.signal.symbol }.toSet()
            val newFixed=approved.filter { it.symbol !in oldSymbols }
            // Forecast is a separate calibration product; do NOT pretend old LONG wins prove
            // that a short model works. Until a properly labeled short outcome set exists,
            // use strictly research-only relative ranking.
            val forecast=if(ShortPreferences(context).load().forecastScannerEnabled)
                approved.sortedByDescending { it.familyScore+.1*it.direction }
                    .map { ForecastRow(it,it.familyScore+.1*it.direction,0) }
                else emptyList()
            state.value=ScanState(running=true,status="Scan complete — live observations only",
                lastScanMs=System.currentTimeMillis(),masterCount=master.size,
                coverage=coverage,evaluated=rows.size,
                fixed=approved,forecast=forecast,
                totalObservations=observations.values.sumOf { it.size })
            if(newFixed.isNotEmpty()) alert(context,newFixed)
            // The user must enable ARM AND the specific auto-short switch.
            // Preference changes never retroactively submit past observations.
            val controls=ShortPreferences(context).load()
            val newForecast=forecast.map { it.signal }.filter { it.symbol !in oldForecastSymbols }
            if(controls.armed) {
                if(controls.fixedAutoShortEnabled) {
                    newFixed.firstOrNull()?.let { ShortBrokerExecution.consider(context,it,"FIXED") }
                }
                if(controls.forecastAutoShortEnabled) {
                    newForecast.firstOrNull()?.let { ShortBrokerExecution.consider(context,it,"FORECAST") }
                }
            }
            current.forEach { (k,v)->openLtps.putIfAbsent(k,v) }
            previous=current
        } catch(ex:Exception) {
            state.value=state.value.copy(status="Scan failed safely",lastError=ex.message)
        }
    }
    private fun alert(context:Context,newRows:List<Signal>) {
        val manager=context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel("shorting40_signals",
            "Short setup observations",NotificationManager.IMPORTANCE_HIGH))
        if(Build.VERSION.SDK_INT>=33&&context.checkSelfPermission(
            android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED) return
        newRows.take(5).forEach { s->
            manager.notify(s.symbol.hashCode(),NotificationCompat.Builder(context,"shorting40_signals")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("SHORT SETUP · ${s.symbol}")
                .setContentText("SELL ₹%.2f · cover target ₹%.2f · stop ₹%.2f".format(s.entry,s.target,s.stop))
                .setAutoCancel(true).build())
        }
    }
}

class ShortScanService:Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var work:Job?=null
    private var protectionMonitor:Job?=null
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action=="STOP") {
            work?.cancel()
            ShortRuntime.state.value=ShortRuntime.state.value.copy(running=false,
                status=if(ShortBrokerExecution.active(this)) "Scanner stopped · live short protection remains active" else "Stopped")
            if(!ShortBrokerExecution.active(this)) { protectionMonitor?.cancel();stopSelf() }
            return START_NOT_STICKY
        }
        val manager=getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel("shorting40_service",
            "Market scanner",NotificationManager.IMPORTANCE_LOW))
        val notification:Notification=NotificationCompat.Builder(this,"shorting40_service")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("Shorting 40 · live market scanner")
            .setContentText("NSE LTP discovery every 150 seconds · opt-in live MIS shorts")
            .setOngoing(true).build()
        startForeground(4040,notification)
        if(protectionMonitor?.isActive!=true) protectionMonitor=scope.launch {
            while(isActive) {
                ShortBrokerExecution.monitor(applicationContext)
                delay(10000)
            }
        }
        if(work?.isActive!=true) work=scope.launch {
            ShortRuntime.state.value=ShortRuntime.state.value.copy(running=true)
            // Match Fixed's nominal 150-second start-to-start gate. If a scan runs
            // longer than the interval, start again on the next worker check;
            // never run two scans concurrently.
            while(isActive) {
                val started=SystemClock.elapsedRealtime()
                ShortRuntime.scan(applicationContext)
                delay((150000L-(SystemClock.elapsedRealtime()-started)).coerceAtLeast(1000L))
            }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        work?.cancel();protectionMonitor?.cancel()
        ShortRuntime.state.value=ShortRuntime.state.value.copy(running=false)
        super.onDestroy()
    }
}
