package com.suhas.shorting40

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Live NSE CASH / MIS broker boundary. Only app-created positions are managed.
 * All positions have persistent order references, not just in-memory states.
 * Broker OCO is placed immediately after the SELL fill is confirmed.
 */
internal data class ShortOwnedTrade(
    val symbol: String,
    val date: String,
    val origin: String,
    val qtyRequested: Int,
    val entryRef: String,
    val requestedStop: Double,
    val requestedTarget: Double,
    val state: String = "ENTRY_INTENT",
    val orderId: String = "",
    val qtyFilled: Int = 0,
    val fillPrice: Double = 0.0,
    val ocoId: String = "",
    val effectiveStop: Double = 0.0,
    val bestPrice: Double = 0.0,
    val exitRef: String = "",
    val lastError: String = ""
) {
    fun isOpen() = state !in setOf("CLOSED", "REJECTED", "UNFILLED")
}

internal data class BrokerOrderState(
    val id: String,
    val status: String,
    val filled: Int,
    val price: Double
)

internal class BrokerRejected(message:String):IllegalStateException(message)

/** Real Groww REST API. Never retries a POST implicitly: network ambiguity is
 * resolved using the persistent unique reference ID and broker status API. */
internal class GrowwShortBroker(private val token: String) {
    private val client=OkHttpClient.Builder()
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(9,TimeUnit.SECONDS)
        .retryOnConnectionFailure(false).build()
    private fun request(method:String,path:String,json:JsonObject?=null):JsonObject {
        val body=json?.toString()?.toRequestBody("application/json".toMediaType())
        val builder=Request.Builder().url("https://api.groww.in/v1/"+path)
            .header("Authorization","Bearer "+token)
            .header("Accept","application/json")
            .header("X-API-VERSION","1.0")
        when(method) {
            "GET" -> builder.get()
            "POST" -> builder.post(body?:"{}".toRequestBody("application/json".toMediaType()))
            "PUT" -> builder.put(body?:"{}".toRequestBody("application/json".toMediaType()))
            else -> error("Unexpected broker verb")
        }
        client.newCall(builder.build()).execute().use { response->
            val parsed=runCatching {
                JsonParser.parseString(response.body?.string().orEmpty()).asJsonObject
            }.getOrElse { throw IllegalStateException("Unparseable Groww broker response: HTTP "+response.code) }
            if(!response.isSuccessful || parsed.get("status")?.asString!="SUCCESS") {
                val msg=runCatching { parsed.getAsJsonObject("error")?.get("message")?.asString }.getOrNull()
                throw BrokerRejected("Groww HTTP "+response.code+": "+(msg?:"broker rejected request"))
            }
            return parsed.getAsJsonObject("payload")?:error("Groww broker payload missing")
        }
    }
    private fun JsonObject.string(key:String)=get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
    private fun JsonObject.int(key:String)=runCatching { get(key)?.asInt?:0 }.getOrDefault(0)
    private fun JsonObject.double(key:String)=runCatching { get(key)?.asDouble?:0.0 }.getOrDefault(0.0)
    fun quote(symbol:String):Double {
        val p=request("GET","live-data/quote?exchange=NSE&segment=CASH&trading_symbol="+symbol)
        return p.double("last_price").takeIf { it>100 && it.isFinite() }?:error("Invalid latest quote")
    }
    fun availableCash():Double =
        request("GET","margins/detail/user").double("clear_cash")
    fun misQty(symbol:String):Int {
        val p=request("GET","positions/user?segment=CASH")
        val rows=p.getAsJsonArray("positions")?:error("Groww positions missing")
        return rows.sumOf { row->
            val x=row.asJsonObject
            if(x.string("trading_symbol")==symbol && x.string("product")=="MIS" &&
                x.string("exchange")=="NSE") x.int("quantity") else 0
        }
    }
    fun submit(symbol:String,qty:Int,side:String,reference:String):BrokerOrderState {
        val body=JsonObject().apply {
            addProperty("trading_symbol",symbol)
            addProperty("quantity",qty)
            addProperty("price",0)
            addProperty("trigger_price",0)
            addProperty("validity","DAY")
            addProperty("exchange","NSE")
            addProperty("segment","CASH")
            addProperty("product","MIS")
            addProperty("order_type","MARKET")
            addProperty("transaction_type",side)
            addProperty("order_reference_id",reference)
        }
        val p=request("POST","order/create",body)
        return BrokerOrderState(p.string("groww_order_id"),p.string("order_status"),
            p.int("filled_quantity"),p.double("average_fill_price"))
    }
    fun status(reference:String):BrokerOrderState {
        val p=request("GET","order/status/reference/"+reference+"?segment=CASH")
        return BrokerOrderState(p.string("groww_order_id"),p.string("order_status"),
            p.int("filled_quantity"),p.double("average_fill_price"))
    }
    fun cancelOrder(id:String) {
        request("POST","order/cancel",JsonObject().apply {
            addProperty("segment","CASH");addProperty("groww_order_id",id)
        })
    }
    fun createOco(t:ShortOwnedTrade):String {
        require(t.qtyFilled>0 && t.fillPrice>0 && t.effectiveStop>t.fillPrice &&
            t.requestedTarget<t.fillPrice)
        val target=JsonObject().apply {
            addProperty("trigger_price",format(t.requestedTarget))
            addProperty("order_type","MARKET")
        }
        val stop=JsonObject().apply {
            addProperty("trigger_price",format(t.effectiveStop))
            addProperty("order_type","SL_M")
        }
        val body=JsonObject().apply {
            addProperty("reference_id",ref())
            addProperty("smart_order_type","OCO")
            addProperty("segment","CASH")
            addProperty("trading_symbol",t.symbol)
            addProperty("quantity",t.qtyFilled)
            addProperty("net_position_quantity",-t.qtyFilled)
            addProperty("transaction_type","BUY")
            add("target",target)
            add("stop_loss",stop)
            addProperty("product_type","MIS")
            addProperty("exchange","NSE")
            addProperty("duration","DAY")
        }
        val p=request("POST","order-advance/create",body)
        return p.string("smart_order_id").takeIf { it.isNotBlank() }
            ?:error("Groww did not return OCO id")
    }
    fun ocoStatus(id:String):String =
        request("GET","order-advance/status/CASH/OCO/internal/"+id).string("status")
    fun cancelOco(id:String) {
        request("POST","order-advance/cancel/CASH/OCO/"+id)
    }
    fun tightenOco(id:String,newStop:Double) {
        val body=JsonObject().apply {
            addProperty("smart_order_type","OCO")
            addProperty("segment","CASH")
            add("stop_loss",JsonObject().apply { addProperty("trigger_price",format(newStop)) })
        }
        request("PUT","order-advance/modify/"+id,body)
    }
    fun updateOcoQuantity(id:String,remaining:Int) {
        require(remaining>0)
        val body=JsonObject().apply {
            addProperty("smart_order_type","OCO")
            addProperty("segment","CASH")
            addProperty("quantity",remaining)
        }
        request("PUT","order-advance/modify/"+id,body)
    }
    companion object {
        fun ref() = "S40"+UUID.randomUUID().toString().replace("-","").take(13).uppercase(Locale.US)
        fun format(value:Double)=String.format(Locale.US,"%.2f",value)
    }
}

/**
 * Fail-closed state machine. "ARM OFF" stops NEW entries but NEVER disables
 * reconciliation or end-of-day covering of an app-owned open short.
 */
internal object ShortBrokerExecution {
    private val mutex=Mutex()
    private val gson=Gson()
    private val india=ZoneId.of("Asia/Kolkata")
    private const val PREFS="shorting40_live_executions"
    private fun prefs(c:Context)=c.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
    fun active(c:Context):Boolean=load(c)?.isOpen()==true
    private fun load(c:Context):ShortOwnedTrade?=runCatching {
        prefs(c).getString("owned_position",null)?.let { gson.fromJson(it,ShortOwnedTrade::class.java) }
    }.getOrNull()
    private fun save(c:Context,p:ShortOwnedTrade) {
        check(prefs(c).edit().putString("owned_position",gson.toJson(p)).commit()) {
            "Cannot durably record broker order state"
        }
    }
    private fun warn(c:Context,title:String,message:String) {
        val manager=c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel("shorting40_broker",
            "Urgent live short trading",NotificationManager.IMPORTANCE_HIGH))
        if(Build.VERSION.SDK_INT>=33&&c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) return
        manager.notify(7272,NotificationCompat.Builder(c,"shorting40_broker")
            .setSmallIcon(android.R.drawable.ic_dialog_alert).setContentTitle(title)
            .setContentText(message).setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setOngoing(true).build())
    }
    suspend fun consider(context:Context, signal:Signal, origin:String) = withContext(Dispatchers.IO) {
        mutex.lock()
        try {
            val settings=ShortPreferences(context).load()
            val token=ShortRuntime.token(context).load()
            if(!ShortExecutionGate.canAutoShort(!token.isNullOrBlank(),settings,origin=="FIXED")) return@withContext
            val now=LocalDateTime.now(india)
            if(!ShortRuntime.scanningAllowed(now) || now.toLocalTime()<LocalTime.of(9,20) ||
                now.toLocalTime()>=LocalTime.of(14,30) || signal.tier!="PRIMARY") return@withContext
            val date=now.toLocalDate().toString()
            if(load(context)?.isOpen()==true) return@withContext
            val key="attempt_"+date+"_"+signal.symbol
            if(prefs(context).getBoolean(key,false)) return@withContext
            require(token!=null)
            // Check the *current* outbound IP again immediately before any live order.
            val outgoing=IpVerifier.currentPublicIp()
            if(outgoing!=settings.staticIp) {
                ShortPreferences(context).invalidateIp()
                warn(context,"Short entry not sent","The outbound IP no longer matches Groww's configured static IP.")
                return@withContext
            }
            val broker=GrowwShortBroker(token)
            if(broker.misQty(signal.symbol)!=0) return@withContext
            val latest=broker.quote(signal.symbol)
            if(abs(latest/signal.entry-1)>.0125) return@withContext
            val budget=settings.riskBudgetRupees.toDouble()
            if(broker.availableCash()<budget) return@withContext
            val qty=floor(budget/latest).toInt().coerceAtMost(2000)
            if(qty<1) return@withContext
            val reference=GrowwShortBroker.ref()
            val trade=ShortOwnedTrade(signal.symbol,date,origin,qty,reference,
                signal.stop,signal.target)
            // Both preflight ledger and order id are persisted synchronously.
            save(context,trade)
            check(prefs(context).edit().putBoolean(key,true).commit())
            val submitted=runCatching { broker.submit(signal.symbol,qty,"SELL",reference) }
            if(submitted.isFailure) {
                save(context,trade.copy(state="ENTRY_UNCERTAIN",
                    lastError=submitted.exceptionOrNull()?.message.orEmpty()))
                warn(context,"Verify SHORT entry","Broker response uncertain for "+signal.symbol+
                    ". Checking reference "+reference+"; do not submit a duplicate.")
                return@withContext
            }
            val order=submitted.getOrThrow()
            save(context,trade.copy(state="ENTRY_PENDING",orderId=order.id))
            reconcileEntry(context,broker,load(context)?:trade)
        } catch(ex:Exception) {
            warn(context,"SHORT execution needs attention",ex.message?:"Broker operation failed")
        } finally { mutex.unlock() }
    }
    private suspend fun reconcileEntry(c:Context,b:GrowwShortBroker,record:ShortOwnedTrade) {
        var t=record
        // Never resend an ambiguous entry; lookup by stable client reference.
        var s:BrokerOrderState?=null
        for(attempt in 0 until 5) {
            s=runCatching { b.status(t.entryRef) }.getOrNull()
            if((s?.filled?:0)>0 || s?.status in setOf("REJECTED","CANCELLED","FAILED")) break
            if(attempt<4) delay(500)
        }
        val status=s?:return
        if(status.filled==0) {
            if(status.status in setOf("REJECTED","CANCELLED","FAILED")) {
                save(c,t.copy(state="REJECTED"));return
            }
            // Attempt cancel before any new entry. Retry reconciliation on next monitor.
            if(status.id.isNotBlank()) runCatching { b.cancelOrder(status.id) }
            return
        }
        if(status.filled<t.qtyRequested && status.id.isNotBlank() &&
            status.status !in setOf("CANCELLED","COMPLETE","EXECUTED")) {
            runCatching { b.cancelOrder(status.id) }
            // Check again to capture fills occurring during cancellation.
            s=runCatching { b.status(t.entryRef) }.getOrDefault(status)
        }
        val filled=s?.filled?:status.filled
        val avg=(s?.price?:0.0).takeIf { it>0 }?:status.price.takeIf { it>0 }?:b.quote(t.symbol)
        if(filled<1) return
        val originalMove=(t.requestedStop-t.requestedTarget).takeIf { it>0 }?:avg*.02
        val stop=max(avg*1.0075,t.requestedStop)
        val target=avg-min(avg*.01, originalMove*.55)
        t=t.copy(state="UNPROTECTED",qtyFilled=filled,fillPrice=avg,
            effectiveStop=stop,requestedTarget=target,bestPrice=avg)
        save(c,t)
        protect(c,b,t)
    }
    private suspend fun protect(c:Context,b:GrowwShortBroker,t:ShortOwnedTrade) {
        try {
            val id=b.createOco(t)
            save(c,t.copy(state="PROTECTED",ocoId=id,lastError=""))
            warn(c,"Live short opened",t.symbol+" SELL "+t.qtyFilled+
                " @ "+GrowwShortBroker.format(t.fillPrice)+"; BUY OCO active.")
        } catch(ex:Exception) {
            save(c,t.copy(state="UNPROTECTED",lastError=ex.message.orEmpty()))
            if(ex is BrokerRejected) {
                warn(c,"URGENT: broker rejected OCO",t.symbol+
                    " protective order was rejected; emergency MARKET BUY is being submitted.")
                emergencyCover(c,b,t,confirmedNoOco=true)
            } else {
                save(c,t.copy(state="RECONCILIATION_REQUIRED"))
                warn(c,"URGENT: verify broker OCO",t.symbol+
                    " OCO response was ambiguous. Inspect Groww for an active OCO before BUY-to-cover.")
            }
        }
    }
    private fun emergencyCover(c:Context,b:GrowwShortBroker,t:ShortOwnedTrade,
                               confirmedNoOco:Boolean=false) {
        // A rejected OCO may have been accepted despite lost HTTP response.
        // With no confirmed OCO id, do not risk sending an uncontrolled double BUY.
        // This must be explicitly reconciled against broker smart-order history.
        if(t.ocoId.isBlank() && !confirmedNoOco) {
            save(c,t.copy(state="RECONCILIATION_REQUIRED"))
            warn(c,"URGENT: manual short reconciliation",t.symbol+
                " has an unconfirmed protective order. Inspect Groww Orders/OCO and cover manually.")
            return
        }
        val ref=t.exitRef.ifBlank { GrowwShortBroker.ref() }
        save(c,t.copy(state="COVER_PENDING",exitRef=ref))
        runCatching { b.submit(t.symbol,t.qtyFilled,"BUY",ref) }.onFailure { error->
            warn(c,"URGENT: BUY cover failed",t.symbol+": "+error.message)
        }
    }
    suspend fun monitor(context:Context)=withContext(Dispatchers.IO) {
        mutex.lock()
        try {
            val t=load(context)?.takeIf { it.isOpen() }?:return@withContext
            val token=ShortRuntime.token(context).load()
            if(token.isNullOrBlank()) {
                warn(context,"URGENT: Groww token missing","Open short "+t.symbol+
                    " cannot be monitored; enter a valid token or manage it in Groww.")
                return@withContext
            }
            val b=GrowwShortBroker(token)
            if(t.state in setOf("ENTRY_INTENT","ENTRY_PENDING","ENTRY_UNCERTAIN")) {
                reconcileEntry(context,b,t);return@withContext
            }
            val actual=b.misQty(t.symbol)
            if(actual==0) {
                save(context,t.copy(state="CLOSED"))
                if(t.ocoId.isNotBlank()) runCatching { b.cancelOco(t.ocoId) }
                return@withContext
            }
            if(actual>0 || actual< -t.qtyFilled) {
                warn(context,"URGENT: unexpected broker position",t.symbol+
                    " net MIS qty "+actual+". No new action until reconciled.")
                return@withContext
            }
            if(t.state=="COVER_PENDING") {
                val cover=runCatching { b.status(t.exitRef) }.getOrNull()
                if(cover==null || cover.status in setOf("REJECTED","FAILED","CANCELLED")) {
                    warn(context,"URGENT: check BUY cover",t.symbol+
                        " still short by "+(-actual)+" shares; broker BUY cover must be checked.")
                }
                // Never send another BUY while the first cover may still fill.
                return@withContext
            }
            val now=LocalDateTime.now(india)
            if(t.state=="RECONCILIATION_REQUIRED") {
                warn(context,"URGENT: manual short reconciliation",t.symbol+
                    " protective OCO acceptance is unknown. Check broker order book NOW.")
                return@withContext
            }
            if(now.toLocalTime()>=LocalTime.of(14,55) || now.toLocalDate().toString()!=t.date) {
                if(t.ocoId.isNotBlank()) b.cancelOco(t.ocoId)
                // The OCO cancellation must have returned before we buy-to-cover.
                val remain=b.misQty(t.symbol)
                if(remain<0) {
                    val ref=t.exitRef.ifBlank { GrowwShortBroker.ref() }
                    save(context,t.copy(state="COVER_PENDING",exitRef=ref))
                    runCatching { b.status(ref) }.getOrNull()
                        ?:b.submit(t.symbol,-remain,"BUY",ref)
                    warn(context,"Closing intraday short","BUY-to-cover requested for "+t.symbol)
                } else save(context,t.copy(state="CLOSED"))
                return@withContext
            }
            if(t.state=="RECONCILIATION_REQUIRED" || t.ocoId.isBlank()) {
                warn(context,"URGENT: check short protection",t.symbol+" has no confirmed OCO.")
                return@withContext
            }
            val oco=b.ocoStatus(t.ocoId)
            if(oco!="ACTIVE") {
                warn(context,"URGENT: protective OCO inactive",t.symbol+
                    " OCO="+oco+" with remaining short. Inspect Groww immediately.")
                return@withContext
            }
            val ltp=b.quote(t.symbol)
            if(-actual<t.qtyFilled) {
                val bodyQty=-actual
                // A broker exit may have partially filled. Prevent a remaining
                // OCO from buying more than the remaining short position.
                b.updateOcoQuantity(t.ocoId,bodyQty)
                save(context,t.copy(qtyFilled=bodyQty))
            }
            val low=min(ltp,t.bestPrice.takeIf { it>0 }?:ltp)
            val newStop=ShortTrail.stop(t.fillPrice,low,max(t.fillPrice*1.0075,t.requestedStop))
            if(newStop<t.effectiveStop-.01 && newStop>ltp+.05) {
                b.tightenOco(t.ocoId,newStop)
                save(context,t.copy(bestPrice=low,effectiveStop=newStop))
            } else if(low<t.bestPrice) save(context,t.copy(bestPrice=low))
        } catch(ex:Exception) {
            warn(context,"SHORT MONITOR ERROR",ex.message?:"Cannot verify live broker state")
        } finally { mutex.unlock() }
    }
}
