package com.suhas.shorting40

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity:ComponentActivity() {
    private val notifyPermission=registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        if(Build.VERSION.SDK_INT>=33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) {
            notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            MaterialTheme {
                Shorting40App(
                    start = { ContextCompat.startForegroundService(this,Intent(this,ShortScanService::class.java)) },
                    stop = { startService(Intent(this,ShortScanService::class.java).setAction("STOP")) }
                )
            }
        }
    }
}

private val subdued=Color(0xFF64748B)
private val downRed=Color(0xFFBD293A)

@Composable
fun Shorting40App(start:()->Unit, stop:()->Unit) {
    val state by ShortRuntime.state.collectAsState()
    var page by rememberSaveable { mutableIntStateOf(0) }
    val titles=listOf("Fixed","Forecast","Settings")
    Scaffold(
        topBar={
            Surface(tonalElevation=2.dp) {
                Column(Modifier.fillMaxWidth().padding(18.dp)) {
                    Text("SHORTING 40",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
                    Text("Intraday NSE · paper-first · 40 bearish factors",
                        style=MaterialTheme.typography.bodySmall,color=subdued)
                }
            }
        },
        bottomBar={
            NavigationBar {
                titles.forEachIndexed { index,label->
                    NavigationBarItem(selected=page==index,onClick={page=index},
                        icon={ Text(when(index){0->"↘";1->"◇";else->"⚙"}) },
                        label={Text(label)})
                }
            }
        }
    ) { inner->
        Column(Modifier.fillMaxSize().padding(inner)) {
            when(page) {
                0->FixedScreen(state,start,stop)
                1->ForecastScreen(state)
                else->SettingsScreen(state,start,stop)
            }
        }
    }
}

@Composable
private fun StatusCard(state:ScanState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(if(state.running) "Scanner running" else "Scanner stopped",
                fontWeight=FontWeight.SemiBold)
            Text(state.status,style=MaterialTheme.typography.bodySmall)
            state.lastError?.let { Text("Error: $it",color=downRed,
                style=MaterialTheme.typography.bodySmall) }
            Text("NSE: ${state.masterCount} symbols · Coverage: ${"%.1f".format(state.coverage*100)}% · Detailed: ${state.evaluated}/48",
                style=MaterialTheme.typography.bodySmall,color=subdued)
            if(state.lastScanMs>0) Text("Last completed: ${SimpleDateFormat("dd MMM HH:mm:ss",Locale.getDefault()).format(Date(state.lastScanMs))}",
                style=MaterialTheme.typography.bodySmall,color=subdued)
        }
    }
}

@Composable
private fun FixedScreen(state:ScanState,start:()->Unit,stop:()->Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(14.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {
            StatusCard(state)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Button(onClick=start,enabled=!state.running) { Text("Start 150s scanner") }
                OutlinedButton(onClick=stop,enabled=state.running) { Text("Stop") }
            }
            Spacer(Modifier.height(8.dp))
            Text("Fixed Short V1 · PRIMARY",style=MaterialTheme.typography.titleMedium,
                fontWeight=FontWeight.SemiBold)
            Text("Two confirmations for market discoveries; three for fallback candidates. All prices are paper signals.",
                style=MaterialTheme.typography.bodySmall,color=subdued)
        }
        if(state.fixed.isEmpty()) item {
            Card(Modifier.fillMaxWidth()) {
                Text("No qualifying short recommendations yet. An initial full-market LTP baseline and repeated PRIMARY confirmations are required.",
                    Modifier.padding(18.dp),style=MaterialTheme.typography.bodyMedium)
            }
        }
        items(state.fixed,key={it.symbol}) { signal-> SignalCard(signal) }
    }
}

@Composable
private fun SignalCard(s:Signal) {
    var expanded by rememberSaveable(s.symbol) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth(),shape=RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                Text(s.symbol,fontWeight=FontWeight.Bold,style=MaterialTheme.typography.titleMedium)
                Text("SELL · ${s.tier}",color=downRed,fontWeight=FontWeight.Bold)
            }
            Text("${s.strategy.replace('_',' ')} · Confidence ${"%.0f".format(s.confidence*100)}%",
                style=MaterialTheme.typography.bodySmall,color=subdued)
            Text("Entry ₹${money(s.entry)}    Target ₹${money(s.target)}    BUY stop ₹${money(s.stop)}")
            Text("40 factors · ${s.available} available · Family score ${"%.2f".format(s.familyScore)}",
                style=MaterialTheme.typography.bodySmall)
            TextButton(onClick={expanded=!expanded}) {
                Text(if(expanded) "Hide factors" else "Inspect 40 factors")
            }
            if(expanded) {
                s.factors.forEachIndexed { i,f ->
                    Text("${i+1}. ${f.name.replace('_',' ')}: ${"%.2f".format(f.score)}",
                        style=MaterialTheme.typography.bodySmall)
                }
                if(s.reasons.isNotEmpty()) Text(s.reasons.joinToString("; "),
                    color=downRed,style=MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ForecastScreen(state:ScanState) {
    LazyColumn(Modifier.fillMaxSize().padding(14.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                    Text("Forecast Short · calibration",style=MaterialTheme.typography.titleMedium)
                    Text("Research-only ranking. The new short engine has no validated downside-outcome history, so adaptive target expansion and live execution are disabled.",
                        style=MaterialTheme.typography.bodyMedium)
                    Text("Current PRIMARY observations: ${state.totalObservations}",
                        style=MaterialTheme.typography.bodySmall,color=subdued)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text("Research ranking",style=MaterialTheme.typography.titleMedium)
        }
        if(state.forecast.isEmpty()) item {
            Text("Forecast suggestions will appear after Fixed has confirmed short candidates.",
                style=MaterialTheme.typography.bodyMedium,color=subdued)
        }
        items(state.forecast,key={it.signal.symbol}) { row->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("${row.signal.symbol} · ${row.signal.strategy}",fontWeight=FontWeight.SemiBold)
                    Text("Research score: ${"%.2f".format(row.adjustedScore)} · Calibrated samples: ${row.evidenceCount}",
                        style=MaterialTheme.typography.bodySmall)
                    Text("Paper target ₹${money(row.signal.target)} · stop ₹${money(row.signal.stop)}",
                        style=MaterialTheme.typography.bodySmall,color=subdued)
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(state:ScanState,start:()->Unit,stop:()->Unit) {
    val context=LocalContext.current
    val vault=remember { ShortRuntime.token(context) }
    var token by remember { mutableStateOf("") }
    var configured by remember { mutableStateOf(vault.configured()) }
    var note by remember { mutableStateOf("") }
    val scope=rememberCoroutineScope()
    LazyColumn(Modifier.fillMaxSize().padding(14.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    Text("Groww market-data access",style=MaterialTheme.typography.titleMedium,
                        fontWeight=FontWeight.SemiBold)
                    Text(if(configured) "Encrypted access token is configured" else "Groww access token required",
                        style=MaterialTheme.typography.bodySmall,color=subdued)
                    OutlinedTextField(value=token,onValueChange={token=it},
                        label={Text("Daily Groww API access token")},singleLine=true,
                        visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Button(onClick={
                            try { vault.save(token);configured=vault.configured();token="";note="Token saved securely" }
                            catch(ex:Exception){note=ex.message?:"Unable to save token"}
                        },enabled=token.isNotBlank()) { Text("Save token") }
                        OutlinedButton(onClick={
                            vault.save("");configured=false;note="Token removed"
                            if(state.running) stop()
                        }) { Text("Remove") }
                    }
                    if(note.isNotBlank()) Text(note,style=MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text("Scan settings",style=MaterialTheme.typography.titleMedium)
                    Text("Market-wide LTP scan: 150-second interval")
                    Text("Detailed stocks: max 48")
                    Text("Historical candles: 15-minute / 4 calendar days")
                    Text("NSE LTP discovery: full stored equity master")
                    Text("Groww budgets: live 3/s, 90/min; history 2/s, 90/min",
                        style=MaterialTheme.typography.bodySmall,color=subdued)
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Button(onClick=start,enabled=configured&&!state.running){Text("Start")}
                        OutlinedButton(onClick=stop,enabled=state.running){Text("Stop")}
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text("Execution safety",style=MaterialTheme.typography.titleMedium)
                    Text("PAPER ONLY · Live order endpoints not compiled into this APK",
                        color=downRed,fontWeight=FontWeight.Bold)
                    Text("Short SELL MIS, protective BUY-to-cover OCO, fill reconciliation, broker eligibility, and forced 14:55 IST exit require authenticated broker integration testing before a live-release build.",
                        style=MaterialTheme.typography.bodySmall)
                    Text("Existing Multify LONG trades and Multify learning data are never used.",
                        style=MaterialTheme.typography.bodySmall,color=subdued)
                }
            }
        }
    }
}

private fun money(value:Double):String="%.2f".format(Locale.US,value)
