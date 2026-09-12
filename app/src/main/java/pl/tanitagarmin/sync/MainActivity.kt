package pl.tanitagarmin.sync

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import pl.tanitagarmin.sync.garmin.GarminAuthClient
import pl.tanitagarmin.sync.garmin.GarminClient
import pl.tanitagarmin.sync.storage.MeasurementStore
import pl.tanitagarmin.sync.storage.SecurePrefs
import pl.tanitagarmin.sync.storage.SyncPrefs
import pl.tanitagarmin.sync.sync.AutoSyncManager
import pl.tanitagarmin.sync.tanita.Measurement
import pl.tanitagarmin.sync.tanita.MyTanitaClient
import pl.tanitagarmin.sync.tanita.ParseResult
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var email: EditText
    private lateinit var password: EditText
    private lateinit var remember: CheckBox
    private lateinit var autoSync: CheckBox
    private lateinit var autoStatus: TextView
    private lateinit var syncButton: Button
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var latestCard: LinearLayout
    private lateinit var latestText: TextView

    private lateinit var garminStatus: TextView
    private lateinit var garminLoginButton: Button
    private lateinit var garminSendButton: Button
    private lateinit var garminLogoutButton: Button

    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var securePrefs: SecurePrefs
    private lateinit var syncPrefs: SyncPrefs
    private lateinit var store: MeasurementStore
    private lateinit var garminAuth: GarminAuthClient
    private var latestMeasurement: Measurement? = null
    private var suppressAutoListener = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        securePrefs = SecurePrefs(this)
        syncPrefs = SyncPrefs(this)
        store = MeasurementStore(this)
        garminAuth = GarminAuthClient(this)
        setContentView(buildUi())

        securePrefs.loadCredentials()?.let {
            email.setText(it.email)
            password.setText(it.password)
            remember.isChecked = true
        }

        // v0.3: użytkownik poprosił o auto-sync. Przy pierwszym uruchomieniu tej
        // wersji zaznaczamy opcję; faktyczne zadanie ruszy dopiero gdy mamy
        // zapisane dane MyTANITA i aktywną sesję Garmin.
        suppressAutoListener = true
        autoSync.isChecked = if (syncPrefs.configured) syncPrefs.autoEnabled else true
        suppressAutoListener = false

        store.load()?.let { showMeasurements(it, "Dane zapisane lokalnie") }
        refreshGarminUi()
        refreshAutoUi()
        ensureAutoScheduled(runNow = false)
    }

    override fun onResume() {
        super.onResume()
        if (::autoStatus.isInitialized) refreshAutoUi()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("Legacy Activity result API is sufficient for this small no-AndroidX Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_GARMIN_LOGIN) {
            refreshGarminUi()
            if (resultCode == RESULT_OK) {
                garminStatus.text = "Garmin Connect: zalogowano."
                ensureAutoScheduled(runNow = true)
            }
        }
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(32))
        }

        root.addView(TextView(this).apply {
            text = "Tanita → Garmin"
            textSize = 28f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.rgb(25, 25, 25))
        })
        root.addView(TextView(this).apply {
            text = "v0.3 • automatyczna synchronizacja RD-953 → Garmin Connect"
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(4), 0, dp(22))
        })

        root.addView(sectionTitle("MyTANITA"))
        root.addView(label("E-mail MyTANITA"))
        email = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            hint = "adres używany w mytanita.eu"
            setSingleLine(true)
        }
        root.addView(email, matchWrap())

        root.addView(label("Hasło MyTANITA").apply { setPadding(0, dp(12), 0, 0) })
        password = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "hasło"
            setSingleLine(true)
        }
        root.addView(password, matchWrap())

        remember = CheckBox(this).apply {
            text = "Zapamiętaj dane logowania na tym telefonie"
            setPadding(0, dp(6), 0, dp(2))
        }
        root.addView(remember, matchWrap())

        autoSync = CheckBox(this).apply {
            text = "Automatyczna synchronizacja"
            setPadding(0, dp(2), 0, dp(6))
            setOnCheckedChangeListener { _, checked ->
                if (suppressAutoListener) return@setOnCheckedChangeListener
                if (checked) {
                    val mail = email.text.toString().trim()
                    val pass = password.text.toString()
                    if (mail.isNotBlank() && pass.isNotBlank()) {
                        // Auto-sync musi mieć dostęp do MyTANITA po zamknięciu aplikacji.
                        securePrefs.saveCredentials(mail, pass)
                        remember.isChecked = true
                    }
                    AutoSyncManager.enable(this@MainActivity, runNow = garminAuth.isAuthenticated())
                } else {
                    AutoSyncManager.disable(this@MainActivity)
                }
                refreshAutoUi()
            }
        }
        root.addView(autoSync, matchWrap())

        autoStatus = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(0, 0, 0, dp(10))
        }
        root.addView(autoStatus, matchWrap())

        syncButton = Button(this).apply {
            text = "POBIERZ POMIARY Z MyTANITA"
            setOnClickListener { startSync() }
        }
        root.addView(syncButton, matchWrap())

        progress = ProgressBar(this).apply {
            visibility = View.GONE
            isIndeterminate = true
        }
        root.addView(progress, LinearLayout.LayoutParams(dp(44), dp(44)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(12)
        })

        status = TextView(this).apply {
            text = "Gotowe do połączenia z MyTANITA."
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(14), 0, dp(14))
        }
        root.addView(status, matchWrap())

        latestCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            visibility = View.GONE
            background = GradientDrawable().apply {
                setColor(Color.rgb(245, 247, 250))
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.rgb(220, 224, 230))
            }
        }
        latestCard.addView(TextView(this).apply {
            text = "Ostatni pomiar"
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.rgb(25, 25, 25))
        })
        latestText = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.rgb(35, 35, 35))
            setPadding(0, dp(10), 0, 0)
        }
        latestCard.addView(latestText)
        root.addView(latestCard, matchWrap())

        root.addView(sectionTitle("Garmin Connect").apply { setPadding(0, dp(26), 0, dp(8)) })
        garminStatus = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(garminStatus, matchWrap())

        garminLoginButton = Button(this).apply {
            text = "ZALOGUJ DO GARMIN CONNECT"
            setOnClickListener {
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(this@MainActivity, GarminLoginActivity::class.java), REQ_GARMIN_LOGIN)
            }
        }
        root.addView(garminLoginButton, matchWrap())

        garminSendButton = Button(this).apply {
            text = "SYNCHRONIZUJ TERAZ"
            setOnClickListener { syncLatestNow() }
        }
        root.addView(garminSendButton, matchWrap())

        garminLogoutButton = Button(this).apply {
            text = "WYLOGUJ GARMIN"
            setOnClickListener {
                garminAuth.logout()
                refreshGarminUi()
                garminStatus.text = "Garmin Connect: wylogowano. Auto-sync czeka na ponowne logowanie."
            }
        }
        root.addView(garminLogoutButton, matchWrap())

        root.addView(TextView(this).apply {
            text = "Auto-sync sprawdza najnowszy pomiar co 6 godzin. Raz na 7 dni robi kontrolę braków wyłącznie z ostatnich 7 dni. Przy pierwszym uruchomieniu v0.3 nie importuje starego tygodnia."
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(0, dp(10), 0, 0)
        })

        return ScrollView(this).apply { addView(root) }
    }

    private fun startSync() {
        val mail = email.text.toString().trim()
        val pass = password.text.toString()
        if (mail.isBlank() || pass.isBlank()) {
            status.text = "Podaj e-mail i hasło MyTANITA."
            return
        }

        if (remember.isChecked || autoSync.isChecked) {
            securePrefs.saveCredentials(mail, pass)
            if (autoSync.isChecked) remember.isChecked = true
        } else {
            securePrefs.clearCredentials()
        }

        setBusy(true, "Łączenie z mytanita.eu i pobieranie CSV…")
        executor.execute {
            try {
                val result = MyTanitaClient().loginAndDownload(mail, pass)
                store.saveCsv(result.csv)
                runOnUiThread {
                    setBusy(false, "")
                    showMeasurements(result.parseResult, "Synchronizacja MyTANITA zakończona")
                    refreshGarminUi()
                    ensureAutoScheduled(runNow = false)
                }
            } catch (e: Exception) {
                runOnUiThread { setBusy(false, "Błąd: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    private fun syncLatestNow() {
        val latest = latestMeasurement
        if (latest == null) {
            garminStatus.text = "Najpierw pobierz pomiar z MyTANITA."
            return
        }
        if (!garminAuth.isAuthenticated()) {
            garminStatus.text = "Najpierw zaloguj Garmin Connect."
            return
        }
        val fingerprint = latest.fingerprint()
        if (store.sentFingerprints().contains(fingerprint)) {
            garminStatus.text = "Ostatni pomiar jest już w Garmin Connect. Brak duplikatu."
            if (autoSync.isChecked) AutoSyncManager.runNow(this)
            return
        }

        setBusy(true, "Wysyłanie ostatniego pomiaru do Garmin Connect…")
        garminSendButton.isEnabled = false
        executor.execute {
            try {
                val result = GarminClient(this).uploadBodyComposition(latest)
                store.markSent(listOf(fingerprint))
                runOnUiThread {
                    setBusy(false, "")
                    refreshGarminUi()
                    garminStatus.text = "Wysłano do Garmin Connect ✓  HTTP ${result.httpStatus}."
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setBusy(false, "")
                    refreshGarminUi()
                    garminStatus.text = "Błąd Garmin: ${e.message ?: e.javaClass.simpleName}"
                }
            }
        }
    }

    private fun ensureAutoScheduled(runNow: Boolean) {
        if (!autoSync.isChecked) return
        val creds = securePrefs.loadCredentials()
        if (creds == null) {
            refreshAutoUi("Auto-sync czeka na zapisanie danych MyTANITA.")
            return
        }
        AutoSyncManager.enable(this, runNow = runNow && garminAuth.isAuthenticated())
        refreshAutoUi()
    }

    private fun refreshAutoUi(extra: String? = null) {
        val enabled = if (syncPrefs.configured) syncPrefs.autoEnabled else autoSync.isChecked
        if (::autoSync.isInitialized && autoSync.isChecked != enabled && syncPrefs.configured) {
            suppressAutoListener = true
            autoSync.isChecked = enabled
            suppressAutoListener = false
        }
        val base = if (autoSync.isChecked) {
            "Auto: WŁ. • co 6 h • kontrola braków: ostatnie 7 dni co 7 dni"
        } else {
            "Auto: WYŁ."
        }
        autoStatus.text = listOfNotNull(base, extra, syncPrefs.lastRunText()).joinToString("\n")
    }

    private fun showMeasurements(result: ParseResult, prefix: String) {
        val latest = result.latest
        latestMeasurement = latest
        status.text = "$prefix • ${result.uniqueRows} unikalnych pomiarów" +
            if (result.duplicateRows > 0) " • usunięto ${result.duplicateRows} duplikatów" else ""

        if (latest == null) {
            latestCard.visibility = View.GONE
            return
        }
        latestCard.visibility = View.VISIBLE
        latestText.text = formatMeasurement(latest)
    }

    private fun refreshGarminUi() {
        val logged = garminAuth.isAuthenticated()
        val latest = latestMeasurement
        val alreadySent = latest?.let { store.sentFingerprints().contains(it.fingerprint()) } == true
        garminLoginButton.visibility = if (logged) View.GONE else View.VISIBLE
        garminLogoutButton.visibility = if (logged) View.VISIBLE else View.GONE
        garminSendButton.visibility = if (logged) View.VISIBLE else View.GONE
        garminSendButton.isEnabled = logged && latest != null && !alreadySent && progress.visibility != View.VISIBLE
        garminStatus.text = when {
            !logged -> "Garmin Connect: niezalogowany."
            latest == null -> "Garmin Connect: zalogowano. Pobierz pomiar z MyTANITA."
            alreadySent -> "Garmin Connect: zalogowano. Ostatni pomiar jest już wysłany."
            else -> "Garmin Connect: zalogowano. Ostatni pomiar czeka na synchronizację."
        }
    }

    private fun formatMeasurement(m: Measurement): String {
        val dt = m.date.format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss"))
        fun n(v: Double?, decimals: Int = 1): String =
            v?.let { String.format(Locale.US, "%.${decimals}f", it).replace('.', ',') } ?: "—"

        return buildString {
            append(dt).append('\n')
            append("Waga: ").append(n(m.weightKg, 2)).append(" kg\n")
            append("BMI: ").append(n(m.bmi)).append('\n')
            append("Tłuszcz: ").append(n(m.bodyFatPercent)).append(" %\n")
            append("Tłuszcz trzewny: ").append(n(m.visceralFat, 0)).append('\n')
            append("Masa mięśniowa: ").append(n(m.muscleMassKg, 2)).append(" kg\n")
            append("Jakość mięśni: ").append(n(m.muscleQuality, 0)).append('\n')
            append("Masa kostna: ").append(n(m.boneMassKg, 2)).append(" kg\n")
            append("BMR: ").append(n(m.bmrKcal, 0)).append(" kcal\n")
            append("Wiek metaboliczny: ").append(n(m.metabolicAge, 0)).append('\n')
            append("Woda: ").append(n(m.bodyWaterPercent)).append(" %\n")
            append("Physique rating: ").append(n(m.physiqueRating, 0))
        }
    }

    private fun setBusy(busy: Boolean, message: String) {
        syncButton.isEnabled = !busy
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        if (::garminSendButton.isInitialized) garminSendButton.isEnabled = !busy
        if (message.isNotBlank()) status.text = message
    }

    private fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text
        textSize = 19f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.rgb(30, 30, 30))
        setPadding(0, 0, 0, dp(8))
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.rgb(55, 55, 55))
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val REQ_GARMIN_LOGIN = 2001
    }
}
