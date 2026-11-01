package bd.amarbazar.smsforward

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Telephony
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : android.app.Activity() {
    private lateinit var info: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val p = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 40, 40, 40) }
        fun label(t: String) = TextView(this).apply { text = t; setPadding(0, 24, 0, 4) }
        fun field(k: String, hint: String, def: String = "") = EditText(this).apply {
            this.hint = hint; setText(p.getString(k, def)); inputType = InputType.TYPE_CLASS_TEXT; setSingleLine(false)
        }
        box.addView(TextView(this).apply { text = "আমার বাজার — SMS পাঠানো"; textSize = 20f })
        box.addView(label("ওয়েব অ্যাপের /exec লিংক (পাবলিক ডিপ্লয়মেন্ট)"))
        val url = field("url", "https://script.google.com/macros/s/.../exec"); box.addView(url)
        box.addView(label("SMS_KEY (Script properties-এ যেটা দিয়েছেন)"))
        val key = field("key", "গোপন চাবি"); box.addView(key)
        box.addView(label("কোন প্রেরকের SMS নেবে (কমা দিয়ে; ফাঁকা = সবার)"))
        val snd = field("senders", "bkash,nagad,rocket,16216", "bkash,nagad,rocket,16216,upay"); box.addView(snd)
        info = TextView(this).apply { setPadding(0, 24, 0, 24) }
        fun refresh() { info.text = "শেষ অবস্থা: " + (p.getString("last", "এখনো কিছু পাঠানো হয়নি")) }
        fun save() {
            p.edit().putString("url", url.text.toString().trim()).putString("key", key.text.toString().trim())
                .putString("senders", snd.text.toString().trim()).apply()
        }
        box.addView(Button(this).apply { text = "সেভ করুন"; setOnClickListener { save(); info.text = "সেভ হয়েছে ✓" } })
        box.addView(Button(this).apply {
            text = "যাচাই করুন (টেস্ট)"
            setOnClickListener {
                save(); info.text = "পাঠানো হচ্ছে..."
                thread {
                    val r = try { Sender.post(this@MainActivity, "TEST from app") } catch (e: Exception) { "ত্রুটি: ${e.message}" }
                    runOnUiThread { info.text = "উত্তর: $r" }
                }
            }
        })
        box.addView(info); refresh()
        box.addView(TextView(this).apply {
            text = "নোট: ফোনের সেটিংসে এই অ্যাপের ব্যাটারি 'Unrestricted/No restrictions' করুন এবং অ্যাপটি Force stop করবেন না। " +
                "শুধু টাকা-পাওয়ার SMS (TrxID আছে) পাঠানো হয়, অন্য SMS পড়া বা পাঠানো হয় না।"
        })
        setContentView(ScrollView(this).apply { addView(box) })
        requestPermissions(arrayOf(Manifest.permission.RECEIVE_SMS), 1)
    }

    override fun onResume() {
        super.onResume()
        val p = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        info.text = "শেষ অবস্থা: " + (p.getString("last", "এখনো কিছু পাঠানো হয়নি"))
    }
}

/** শুধু টাকা-পাওয়ার SMS (TrxID + received আছে) পাঠায়; বাকি কোনো SMS ছোঁয়া হয় না। */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val msgs = Telephony.Sms.Intents.getMessagesFromIntent(i) ?: return
        if (msgs.isEmpty()) return
        val body = msgs.joinToString("") { it.messageBody ?: "" }
        val from = (msgs[0].originatingAddress ?: "").lowercase()
        val p = c.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        if (p.getString("url", "").isNullOrBlank() || p.getString("key", "").isNullOrBlank()) return
        val allowed = (p.getString("senders", "") ?: "").lowercase().split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (allowed.isNotEmpty() && allowed.none { from.contains(it) }) return
        if (!Regex("(trx|txn|tnx|transaction)\\s*id", RegexOption.IGNORE_CASE).containsMatchIn(body)) return
        if (!body.contains("receiv", ignoreCase = true)) return
        val req = OneTimeWorkRequestBuilder<SendWorker>()
            .setInputData(workDataOf("text" to body))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(c).enqueue(req)
    }
}

object Sender {
    /** ওয়েব অ্যাপে POST করে; উত্তরের লেখা ফেরত দেয়। নেটওয়ার্ক-ত্রুটিতে exception ছোড়ে। */
    fun post(c: Context, text: String): String {
        val p = c.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val url = (p.getString("url", "") ?: "").trim()
        val key = (p.getString("key", "") ?: "").trim()
        val body = "action=sms&key=" + URLEncoder.encode(key, "UTF-8") + "&text=" + URLEncoder.encode(text, "UTF-8")
        val con = URL(url).openConnection() as HttpURLConnection
        con.requestMethod = "POST"
        con.doOutput = true
        con.connectTimeout = 15000
        con.readTimeout = 30000
        con.instanceFollowRedirects = true
        con.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        con.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = con.responseCode
        val stream = if (code in 200..399) con.inputStream else con.errorStream
        val resp = stream?.bufferedReader()?.use { it.readText() } ?: ""
        con.disconnect()
        return "HTTP $code $resp".take(400)
    }

    fun log(c: Context, msg: String) {
        val t = SimpleDateFormat("dd/MM hh:mm:ss a", Locale.US).format(Date())
        c.getSharedPreferences("cfg", Context.MODE_PRIVATE).edit().putString("last", "$t  $msg").apply()
    }
}

class SendWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val text = inputData.getString("text") ?: return Result.failure()
        return try {
            val r = Sender.post(applicationContext, text)
            if (r.contains("\"ok\":true")) { Sender.log(applicationContext, "পাঠানো হয়েছে ✓"); Result.success() }
            else if (r.contains("\"ok\":false")) { Sender.log(applicationContext, "সার্ভার না করেছে: $r"); Result.failure() }
            else if (runAttemptCount < 8) { Sender.log(applicationContext, "আবার চেষ্টা হবে: $r"); Result.retry() }
            else { Sender.log(applicationContext, "ব্যর্থ: $r"); Result.failure() }
        } catch (e: Exception) {
            if (runAttemptCount < 8) { Sender.log(applicationContext, "নেট সমস্যা, আবার চেষ্টা হবে"); Result.retry() }
            else { Sender.log(applicationContext, "ব্যর্থ: ${e.message}"); Result.failure() }
        }
    }
}
