package com.kongeskog.still

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mudita.mmd.components.text.TextMMD
import com.kongeskog.still.bridge.SignalClient
import com.kongeskog.still.bridge.SignUpStep
import com.kongeskog.still.ui.Screen
import com.kongeskog.still.ui.SignUpFieldScreen
import kotlinx.coroutines.launch

private enum class Stage { Number, Captcha, Working, Code, Name }

/**
 * Creating a Signal account from nothing: number, Signal's captcha if it asks for one,
 * the code from the text message, a name. [onDone] runs once the account is stored.
 */
@Composable
fun SignUpFlow(client: SignalClient, onBack: () -> Unit, onDone: () -> Unit) {
    var stage by remember { mutableStateOf(Stage.Number) }
    var number by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    /** Runs one step and moves to whatever Signal wants next; a failure stays on [from] with its reason shown. */
    fun run(from: Stage, step: suspend () -> SignUpStep) {
        busy = true
        error = null
        scope.launch {
            val result = step()
            busy = false
            when (result) {
                SignUpStep.Captcha -> stage = Stage.Captcha
                SignUpStep.EnterCode -> stage = Stage.Code
                SignUpStep.EnterName -> stage = Stage.Name
                SignUpStep.Done -> onDone()
                is SignUpStep.Failed -> {
                    error = result.reason
                    stage = from
                }
            }
        }
    }

    val back = { if (stage == Stage.Number) onBack() else { stage = Stage.Number; error = null } }
    BackHandler(onBack = back)
    when (stage) {
        Stage.Number -> SignUpFieldScreen(
            "Your number", "Your phone number, with its country code.", "+1 555 010 0100", "Next",
            numeric = true, busy = busy, error = error, onBack = back,
        ) { typed ->
            // Signal wants the bare international form: a plus and digits.
            number = "+" + typed.filter(Char::isDigit)
            run(Stage.Number) { client.signUp.start(number) }
        }
        Stage.Captcha -> CaptchaScreen(onBack = back) { token ->
            stage = Stage.Working
            run(Stage.Number) { client.signUp.captcha(token) }
        }
        Stage.Working -> Screen("One moment…") {}
        Stage.Code -> SignUpFieldScreen(
            "Enter the code", "Signal sent a code by text to $number.", "123456", "Next",
            numeric = true, busy = busy, error = error, onBack = back,
            secondary = "Call me instead" to { run(Stage.Code) { client.signUp.resend(voice = true) } },
        ) { code -> run(Stage.Code) { client.signUp.code(code) } }
        Stage.Name -> SignUpFieldScreen(
            "Your name", "The name people will see in Signal.", "First name", "Create account",
            numeric = false, busy = busy, error = error, onBack = back,
        ) { name -> run(Stage.Name) { client.signUp.finish(name) } }
    }
}

/**
 * Signal's own captcha page in a web view. When it is solved the page tries to open a
 * `signalcaptcha://` address; that address is the token and is never actually loaded.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CaptchaScreen(onBack: () -> Unit, onToken: (String) -> Unit) {
    Screen("Confirm you are human", onBack) {
        TextMMD("Signal asks this before sending a code.", Modifier.padding(16.dp, 8.dp), style = MaterialTheme.typography.bodyMedium)
        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { context ->
                WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val url = request.url.toString()
                            if (!url.startsWith("signalcaptcha://")) return false
                            onToken(url)
                            return true
                        }
                    }
                    loadUrl(SignalClient.CAPTCHA_URL)
                }
            },
        )
    }
}
