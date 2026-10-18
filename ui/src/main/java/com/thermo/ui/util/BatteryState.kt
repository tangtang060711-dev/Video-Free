package com.thermo.ui.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * 电量百分比（0~100），拿不到时返回 -1。
 *
 * 为什么不用 `BatteryManager.getIntProperty(BATTERY_PROPERTY_CAPACITY)`：
 *  1. 那个接口在部分 ROM 上直接返回 Integer.MIN_VALUE；
 *  2. 它是一次 binder 查询，而 `ACTION_BATTERY_CHANGED` 本身就是**粘性广播** ——
 *     `registerReceiver(null, filter)` 能零成本读到当前值当初值，
 *     之后系统只在电量真的变化时才回调（1% 粒度，不是每秒），不会变成轮询。
 *
 * 注意 API 33 起注册非系统广播必须显式声明导出性，漏了会直接抛 SecurityException。
 */
@Composable
fun rememberBatteryPercent(): Int {
    val context = LocalContext.current
    var percent by remember { mutableIntStateOf(readBattery(context)) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                val v = parse(intent)
                if (v >= 0) percent = v
            }
        }
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val ok = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
        }.isSuccess
        onDispose { if (ok) runCatching { context.unregisterReceiver(receiver) } }
    }
    return percent
}

private fun readBattery(context: Context): Int =
    runCatching { parse(context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))) }
        .getOrDefault(-1)

private fun parse(intent: Intent?): Int {
    val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
    return if (level >= 0 && scale > 0) level * 100 / scale else -1
}