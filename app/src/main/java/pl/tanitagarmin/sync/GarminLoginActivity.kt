package pl.tanitagarmin.sync

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import org.json.JSONTokener
import pl.tanitagarmin.sync.garmin.GarminAuthClient
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class GarminLoginActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var status: TextView
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val ticketHandled = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Garmin Connect"
        setContentView(buildUi())
        configureWebView()
        webView.loadUrl(GarminAuthClient.SSO_LOGIN_URL)
        scheduleTicketScan()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        status = TextView(this).apply {
            text = "Zaloguj się do Garmin Connect w oknie poniżej. Hasło pozostaje na stronie Garmin."
            setTextColor(Color.DKGRAY)
            textSize = 14f
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        root.addView(status, LinearLayout.LayoutParams(-1, -2))
        webView = WebView(this)
        root.addView(webView, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    private fun configureWebView() {
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
        }
        webView.addJavascriptInterface(TicketBridge(), "TanitaGarminBridge")
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                request?.url?.toString()?.let(::tryTicketFromText)
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                url?.let(::tryTicketFromText)
                installPostMessageBridge()
                scanPageForTicket()
            }
        }
    }


    private inner class TicketBridge {
        @JavascriptInterface
        fun onMessage(value: String?) {
            if (value == null) return
            runOnUiThread { tryTicketFromText(value) }
        }
    }

    private fun installPostMessageBridge() {
        if (ticketHandled.get()) return
        val js = """
            (function(){
              try {
                if(window.__tanitaGarminTicketBridgeInstalled) return 'ok';
                window.__tanitaGarminTicketBridgeInstalled=true;
                window.addEventListener('message', function(ev){
                  try {
                    var v = (typeof ev.data === 'string') ? ev.data : JSON.stringify(ev.data);
                    TanitaGarminBridge.onMessage(v || '');
                  } catch(e) {}
                }, false);
                return 'ok';
              } catch(e) { return ''; }
            })()
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun scheduleTicketScan() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (!ticketHandled.get() && !isFinishing) {
                    tryTicketFromText(webView.url.orEmpty())
                    scanPageForTicket()
                    handler.postDelayed(this, 500)
                }
            }
        }, 500)
    }

    private fun scanPageForTicket() {
        if (ticketHandled.get()) return
        val js = """
            (function(){
              try {
                var h=document.documentElement?document.documentElement.outerHTML:'';
                var m=h.match(/ticket=(ST-[A-Za-z0-9\-]+)/);
                if(m) return m[1];
                var t=document.body?document.body.innerText:'';
                m=t.match(/(ST-[A-Za-z0-9\-]+)/);
                return m?m[1]:'';
              } catch(e) { return ''; }
            })()
        """.trimIndent()
        webView.evaluateJavascript(js) { raw ->
            val decoded = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull().orEmpty()
            tryTicketFromText(decoded)
        }
    }

    private fun tryTicketFromText(text: String) {
        if (ticketHandled.get()) return
        val m = Regex("ST-[A-Za-z0-9\\-]+").find(text) ?: return
        val ticket = m.value
        if (!ticketHandled.compareAndSet(false, true)) return
        status.text = "Logowanie Garmin potwierdzone. Pobieranie tokenu…"
        webView.visibility = View.GONE
        val root = webView.parent as? LinearLayout
        root?.addView(ProgressBar(this).apply { isIndeterminate = true }, LinearLayout.LayoutParams(dp(56), dp(56)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(24)
        })
        executor.execute {
            try {
                GarminAuthClient(this).exchangeServiceTicket(ticket)
                runOnUiThread {
                    status.text = "Garmin Connect: zalogowano."
                    setResult(RESULT_OK)
                    finish()
                }
            } catch (e: Exception) {
                ticketHandled.set(false)
                runOnUiThread {
                    status.text = "Błąd autoryzacji Garmin: ${e.message ?: e.javaClass.simpleName}. Możesz spróbować zalogować się ponownie."
                    webView.visibility = View.VISIBLE
                    webView.loadUrl(GarminAuthClient.SSO_LOGIN_URL)
                }
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}
