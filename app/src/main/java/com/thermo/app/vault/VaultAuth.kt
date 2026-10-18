package com.thermo.app.vault

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.ContextWrapper
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import java.util.concurrent.Executor

/**
 * 加密空间的解锁。
 *
 * 需求原话是「支持使用系统密码（图案解锁，后续可能数字密码）和指纹」。
 * 这两件事在现代 Android 上是同一个 API 的两种取值，所以不自己画解锁界面：
 *
 * - API 30+：`setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)`，
 *   一次弹窗里同时给出指纹与图案/数字密码，用户选哪个都行 —— 正是需求要的。
 * - API 28~29：只能用 `setDeviceCredentialAllowed(true)`（30 上已废弃，因为它的
 *   行为在厂商 ROM 上不一致）。**这个方法和 setNegativeButton 不能同时用**，
 *   否则 BiometricPrompt 内部直接抛 IllegalArgumentException。
 * - API 24~27：退回 KeyguardManager 的确认凭据界面，没有可用的指纹 API。
 *
 * ## 为什么不引 androidx.biometric
 * 本机 Gradle 缓存里没有它（实测 `caches/modules-2/files-2.1/androidx.biometric`
 * 不存在），离线取不到；而需求要的能力，平台 API 从 28 起就有了。
 * 代价只是 28~29 与 30+ 两条分支 —— 比多一个依赖划算。
 *
 * ## 绝不能自己实现"输入密码比对"
 * 图案/数字密码的校验结果只有锁屏能给。自己在应用里存一份密码哈希，等于把安全
 * 等级降到"一个可以离线暴力破解的字符串"，还不如不加密。
 */

/** 把系统解锁弹窗的结果桥回 Compose。返回的函数调用一次即弹一次。 */
@Composable
fun rememberVaultUnlock(onResult: (Boolean) -> Unit): () -> Unit {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val latest by rememberUpdatedState(onResult)

    // 24~27 走这条路；28+ 只在"指纹这条路走不通"时才会用到
    val credentialLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        latest(result.resultCode == Activity.RESULT_OK)
    }

    return {
        val act = activity
        when {
            act == null -> latest(false)

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                // 30+：指纹与密码在同一个弹窗里，系统自己编排，
                // 不需要我们准备退路。
                showBiometric(act, fallback = null) { ok -> latest(ok) }

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ->
                // 28~29：先给指纹；未录入/无硬件/锁死时再跳系统凭据界面。
                showBiometric(act, fallback = { launchCredential(act, credentialLauncher::launch) }) { ok ->
                    latest(ok)
                }

            else -> launchCredential(act, credentialLauncher::launch)
        }
    }
}

/**
 * API 28+ 的系统生物识别弹窗。
 *
 * [fallback] 只在"指纹这条路走不通"时调用；用户主动取消不会触发，
 * 否则会变成关不掉的弹窗循环。
 */
private fun showBiometric(
    activity: Activity,
    fallback: (() -> Unit)?,
    onResult: (Boolean) -> Unit,
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
        if (fallback != null) fallback() else onResult(false)
        return
    }

    val executor = Executor { it.run() }
    val builder = BiometricPrompt.Builder(activity)
        .setTitle("解锁加密空间")
        .setSubtitle("用指纹，或用你的锁屏密码 / 图案")

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        builder.setAllowedAuthenticators(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )
    } else {
        // 30 起这两个会抛 IllegalStateException（不能与 DEVICE_CREDENTIAL 并用），
        // 所以只在 28~29 这一支里设置。这里也绝不能调 setNegativeButton。
        builder.setDeviceCredentialAllowed(true)
    }

    val prompt = builder.build()

    val callback = object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
            onResult(true)
        }

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
            // 用户主动放弃 —— 不能再弹第二次
            if (errorCode == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED ||
                errorCode == BiometricPrompt.BIOMETRIC_ERROR_CANCELED
            ) {
                onResult(false)
                return
            }
            // 其余一律当作"指纹这条路走不通"：
            // BIOMETRIC_ERROR_NO_BIOMETRICS(12 未录入/hardware 无) /
            // BIOMETRIC_ERROR_HW_NOT_PRESENT(14 无硬件) /
            // BIOMETRIC_ERROR_NO_DEVICE_CREDENTIAL(16 没设锁屏) /
            // BIOMETRIC_ERROR_LOCKOUT(锁死) 都该给一次凭据界面的机会。
            if (fallback != null) fallback() else onResult(false)
        }

        override fun onAuthenticationFailed() {
            // 指纹对不上。系统自己会让用户重试，这里什么都不做。
            // 一旦在这里调 onResult(false)，弹窗会被我们提前关掉，用户来不及重按。
        }
    }

    try {
        prompt.authenticate(CancellationSignal(), executor, callback)
    } catch (t: Throwable) {
        if (fallback != null) fallback() else onResult(false)
    }
}

/** 24~27 的退路：跳系统的"确认你的图案/密码"界面。 */
private fun launchCredential(activity: Activity, launch: (android.content.Intent) -> Unit) {
    val km = activity.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
    val intent = km?.createConfirmDeviceCredentialIntent(
        "解锁加密空间",
        "验证你的锁屏密码或图案",
    )
    if (intent == null) {
        // 用户没设锁屏密码。不能让加密空间在没设锁屏的手机上变成人人可开，
        // 所以直接拒绝 —— 宁可进不去。
        launch(android.content.Intent())
    } else {
        launch(intent)
    }
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
