package com.suhas.shorting40

import android.content.Context
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.util.concurrent.TimeUnit

data class ShortControls(
    val staticIp: String = "",
    val observedIp: String = "",
    val staticIpVerified: Boolean = false,
    val forecastScannerEnabled: Boolean = true,
    val fixedAutoShortEnabled: Boolean = false,
    val forecastAutoShortEnabled: Boolean = false,
    val armed: Boolean = false,
    val riskBudgetRupees: Int = 20000
)

object ShortExecutionGate {
    fun canArm(tokenConfigured: Boolean, controls: ShortControls): Boolean =
        tokenConfigured && controls.staticIpVerified &&
            controls.staticIp.isNotBlank()
    fun canAutoShort(tokenConfigured: Boolean, controls: ShortControls, fixed: Boolean): Boolean =
        canArm(tokenConfigured, controls) && controls.armed &&
            if (fixed) controls.fixedAutoShortEnabled else controls.forecastAutoShortEnabled
}

class ShortPreferences(context: Context) {
    private val prefs=context.getSharedPreferences("shorting40_settings",Context.MODE_PRIVATE)
    fun load()=ShortControls(
        staticIp=prefs.getString("static_ip","").orEmpty(),
        observedIp=prefs.getString("observed_ip","").orEmpty(),
        staticIpVerified=prefs.getBoolean("ip_verified",false) &&
            System.currentTimeMillis()-prefs.getLong("ip_verified_at",0) in 0..300000,
        forecastScannerEnabled=prefs.getBoolean("forecast_scanner",true),
        fixedAutoShortEnabled=prefs.getBoolean("fixed_auto_short",false),
        forecastAutoShortEnabled=prefs.getBoolean("forecast_auto_short",false),
        armed=prefs.getBoolean("armed",false),
        riskBudgetRupees=prefs.getInt("risk_budget",20000)
    )
    fun saveIp(ip:String) {
        val cleaned=ip.trim()
        require(cleaned.isEmpty() || isValidIp(cleaned)) { "Enter a valid IPv4 or IPv6 address" }
        prefs.edit().putString("static_ip",cleaned).putBoolean("ip_verified",false)
            .putString("observed_ip","").putBoolean("armed",false)
            .putLong("ip_verified_at",0).apply()
    }
    fun saveForecastScanner(enabled:Boolean) {
        prefs.edit().putBoolean("forecast_scanner",enabled).apply()
    }
    fun saveArm(enabled:Boolean) {
        prefs.edit().putBoolean("armed",enabled).apply()
    }
    fun saveFixedAutoShort(enabled:Boolean) {
        prefs.edit().putBoolean("fixed_auto_short",enabled).apply()
    }
    fun saveForecastAutoShort(enabled:Boolean) {
        prefs.edit().putBoolean("forecast_auto_short",enabled).apply()
    }
    fun saveBudget(rupees:Int) {
        require(rupees in 10000..200000 && rupees%10000==0)
        prefs.edit().putInt("risk_budget",rupees).apply()
    }
    fun invalidateIp() { prefs.edit().putBoolean("ip_verified",false)
        .putLong("ip_verified_at",0).putBoolean("armed",false).apply() }
    fun saveVerification(ip:String, matches:Boolean) {
        prefs.edit().putString("observed_ip",ip).putBoolean("ip_verified",matches)
            .putLong("ip_verified_at",if(matches) System.currentTimeMillis() else 0)
            .putBoolean("armed",false).apply()
    }
    fun disableAllAutoOrders() {
        prefs.edit().putBoolean("fixed_auto_short",false)
            .putBoolean("forecast_auto_short",false)
            .putBoolean("armed",false).apply()
    }
    companion object {
        fun isValidIp(ip:String):Boolean {
            if(ip.isEmpty() || ip.any { !it.isDigit() && !it.isLetter() && it !in ".:" }) return false
            return runCatching {
                val addr=InetAddress.getByName(ip)
                // Only literal IP addresses: never resolve user-entered hostnames.
                if(ip.contains(':')) addr.address.size==16
                else ip.count { it=='.' }==3 && addr.address.size==4
            }.getOrDefault(false)
        }
    }
}

object IpVerifier {
    private val client=OkHttpClient.Builder().connectTimeout(6,TimeUnit.SECONDS)
        .readTimeout(8,TimeUnit.SECONDS).build()
    suspend fun currentPublicIp():String=withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url("https://api.ipify.org?format=json").build()).execute().use { response->
            require(response.isSuccessful) { "Unable to verify public IP (HTTP ${response.code})" }
            JsonParser.parseString(response.body?.string()?:error("IP response missing"))
                .asJsonObject.get("ip").asString
        }
    }
}
