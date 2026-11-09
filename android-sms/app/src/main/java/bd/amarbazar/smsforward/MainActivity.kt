package bd.amarbazar.smsforward

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Telephony
import android.telephony.SmsManager
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

const val DEFAULT_SENDERS = "bkash,16247,nagad,16167,rocket,16216"

class MainActivity : android.app.Activity() {
    private lateinit var info: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val p = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 40, 40, 40) }
        fun label(t: String) = TextView(this).apply { text = t; setPadding(0, 24, 0, 4) }
        fun field(k: String, hint: String, def: String = "") = EditText(this).apply {
            this.hint = hint; setText(p.getString(k, def)); inputType = InputType.TYPE_CLASS_TEXT
        }
        box.addView(TextView(this).apply { text = "আমার বাজার — SMS পাঠানো"; textSize = 20f })
        box.addView(label("ওয়েব অ্যাপের /exec লিংক (পাবলিক ডিপ্লয়মেন্ট)"))
        val url = field("url", "https://script.google.com/macros/s/.../exec"); box.addView(url)
        box.addView(label("SMS_KEY (Script properties-এ যেটা দিয়েছেন)"))
        val key = field("key", "গোপন চাবি"); box.addView(key)
        box.addView(label("কোন প্রেরকের SMS নেবে (কমা দিয়ে, হুবহু নাম)"))
        val snd = field("senders", DEFAULT_SENDERS, DEFAULT_SENDERS); box.addView(snd)
        info = TextView(this).apply { setPadding(0, 24, 0, 24) }
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
                    val r = try {
                        val x = Sender.send(this@MainActivity, "ping", "", "")
                        Sender.handleOut(this@MainActivity, x)
                        x
                    } catch (e: Exception) { "ত্রুটি: ${e.message}" }
                    runOnUiThread { info.text = "উত্তর: " + r.take(400) }
                }
            }
        })
        box.addView(info)
        box.addView(TextView(this).apply {
            text = "টেস্টের উত্তরে \"ok\":true এলে সংযোগ ঠিক আছে। \"চাবি ঠিক নয়\" এলে SMS_KEY মেলেনি।\n\n" +
                "ফোনের সেটিংসে এই অ্যাপের ব্যাটারি 'Unrestricted/No restrictions' করুন এবং অ্যাপটা Force stop করবেন না।\n" +
                "শুধু bKash/Nagad-এর লেনদেনের SMS (TrxID বা Balance আছে এমন) পাঠানো হয়, অন্য কোনো SMS পড়া বা পাঠানো হয় না।\n" +
                "অ্যাপ প্রতি ১৫ সেকেন্ডে সার্ভারকে জিজ্ঞেস করে কাস্টমারকে কোনো SMS পাঠাতে হবে কিনা (ওপরে স্থায়ী নোটিফিকেশন দেখাবে, ওটা সরাবেন না)। বন্ধ থাকলে আপনাকে ইমেইল যায়।\n" +
                "অর্ডার নিশ্চিত হলে বা পাঠানো হলে কাস্টমারকে এই ফোনের সিম থেকে ছোট SMS যায় (SMS পাঠানোর অনুমতি দিতে হবে)।"
        })
        setContentView(ScrollView(this).apply { addView(box) })
        val perms = arrayListOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS)
        if (Build.VERSION.SDK_INT >= 33) perms.add("android.permission.POST_NOTIFICATIONS")
        requestPermissions(perms.toTypedArray(), 1)
        Poller.start(this)
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "ping", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<PingWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        )
        refresh()
    }

    private fun refresh() {
        val p = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        info.text = "শেষ অবস্থা: " + (p.getString("last", "এখনো কিছু পাঠানো হয়নি"))
    }

    override fun onResume() { super.onResume(); refresh() }
}

/** শুধু bKash/Nagad-এর নির্দিষ্ট প্রেরকের লেনদেনের SMS (TrxID বা Balance আছে) পাঠায়; বাকি কোনো SMS ছোঁয়া হয় না। */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val msgs = Telephony.Sms.Intents.getMessagesFromIntent(i) ?: return
        if (msgs.isEmpty()) return
        val body = msgs.joinToString("") { it.messageBody ?: "" }
        val from = (msgs[0].originatingAddress ?: "").trim()
        val p = c.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        if (p.getString("url", "").isNullOrBlank() || p.getString("key", "").isNullOrBlank()) return
        var allowed = (p.getString("senders", "") ?: "").lowercase().split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (allowed.isEmpty()) allowed = DEFAULT_SENDERS.split(",")
        if (!allowed.contains(from.lowercase())) return
        if (!Regex("(trx|txn|tnx|transaction)\\s*id|balance", RegexOption.IGNORE_CASE).containsMatchIn(body)) return
        val req = OneTimeWorkRequestBuilder<SendWorker>()
            .setInputData(workDataOf("text" to body, "sender" to from))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(c).enqueue(req)
    }
}

object Sender {
    /** ওয়েব অ্যাপে POST করে; উত্তরের লেখা ফেরত দেয়। নেটওয়ার্ক-ত্রুটিতে exception ছোড়ে। */
    fun send(c: Context, action: String, text: String, sender: String): String {
        val p = c.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val url = (p.getString("url", "") ?: "").trim()
        val key = (p.getString("key", "") ?: "").trim()
        fun e(s: String) = URLEncoder.encode(s, "UTF-8")
        val body = "action=" + e(action) + "&key=" + e(key) + "&text=" + e(text) + "&sender=" + e(sender)
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
        return "HTTP $code $resp"
    }

    /** সার্ভার যে SMS-গুলো কাস্টমারকে পাঠাতে বলল, সেগুলো এই ফোনের সিম থেকে পাঠায়, তারপর সার্ভারকে জানায়। */
    @Suppress("DEPRECATION")
    fun handleOut(c: Context, resp: String) {
        try {
            val i = resp.indexOf('{')
            if (i < 0) return
            val arr = JSONObject(resp.substring(i)).optJSONArray("out") ?: return
            if (arr.length() == 0) return
            if (c.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                log(c, "SMS পাঠানোর অনুমতি নেই (অ্যাপের অনুমতিতে SMS চালু করুন)")
                return
            }
            val sm: SmsManager = if (Build.VERSION.SDK_INT >= 31) c.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
            val sent = ArrayList<String>()
            val failed = ArrayList<String>()
            for (k in 0 until arr.length()) {
                val m = arr.getJSONObject(k)
                val id = m.optString("id")
                val phone = m.optString("phone")
                val text = m.optString("text")
                if (id.isEmpty() || phone.isEmpty() || text.isEmpty()) continue
                try {
                    sm.sendMultipartTextMessage(phone, null, sm.divideMessage(text), null, null)
                    sent.add(id)
                } catch (e: Exception) {
                    failed.add(id)
                }
            }
            if (sent.isNotEmpty()) send(c, "outack", sent.joinToString(","), "sent")
            if (failed.isNotEmpty()) send(c, "outack", failed.joinToString(","), "fail")
            if (sent.isNotEmpty()) log(c, "কাস্টমারকে " + sent.size + "টি SMS পাঠানো হয়েছে ✓")
        } catch (e: Exception) {
            log(c, "কাস্টমারকে SMS পাঠানো যায়নি: " + e.message)
        }
    }

    fun log(c: Context, msg: String) {
        val t = SimpleDateFormat("dd/MM hh:mm:ss a", Locale.US).format(Date())
        c.getSharedPreferences("cfg", Context.MODE_PRIVATE).edit().putString("last", "$t  $msg").apply()
    }
}

class SendWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val text = inputData.getString("text") ?: return Result.failure()
        val sender = inputData.getString("sender") ?: ""
        return try {
            val r = Sender.send(applicationContext, "sms", text, sender)
            if (r.contains("\"ok\":true")) { Sender.log(applicationContext, "পাঠানো হয়েছে ✓"); Sender.handleOut(applicationContext, r); Result.success() }
            else if (r.contains("\"ok\":false")) { Sender.log(applicationContext, "সার্ভার না করেছে: " + r.take(300)); Result.failure() }
            else if (runAttemptCount < 12) { Sender.log(applicationContext, "আবার চেষ্টা হবে: " + r.take(300)); Result.retry() }
            else { Sender.log(applicationContext, "ব্যর্থ: " + r.take(300)); Result.failure() }
        } catch (e: Exception) {
            if (runAttemptCount < 12) { Sender.log(applicationContext, "নেট সমস্যা, আবার চেষ্টা হবে"); Result.retry() }
            else { Sender.log(applicationContext, "ব্যর্থ: ${e.message}"); Result.failure() }
        }
    }
}

/** প্রতি ১৫ মিনিটে "আমি চালু আছি" জানায়। */
class PingWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        try { Sender.handleOut(applicationContext, Sender.send(applicationContext, "ping", "", "")) } catch (e: Exception) { }
        return Result.success()
    }
}

/** ফোন চালু থাকলে প্রতি ১৫ সেকেন্ডে সার্ভারকে জিজ্ঞেস করে: কাস্টমারকে কোনো SMS পাঠাতে হবে কিনা। স্থায়ী নোটিফিকেশন লাগে (অ্যান্ড্রয়েডের নিয়ম)। */
object Poller {
    fun start(c: Context) {
        try {
            val i = Intent(c, PollService::class.java)
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
        } catch (e: Exception) {
            Sender.log(c, "দ্রুত চেক চালু করা যায়নি: " + e.message)
        }
    }
}

class PollService : Service() {
    @Volatile private var running = false

    override fun onBind(i: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        val ch = "poll"
        val b: Notification.Builder
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(ch, "রোবট চালু", NotificationManager.IMPORTANCE_LOW))
            b = Notification.Builder(this, ch)
        } else {
            b = Notification.Builder(this)
        }
        val n = b.setContentTitle("আমার বাজার রোবট চালু")
            .setContentText("কাস্টমারকে SMS পাঠাতে প্রস্তুত")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(1, n)
        if (!running) {
            running = true
            thread {
                while (running) {
                    try {
                        val p = getSharedPreferences("cfg", Context.MODE_PRIVATE)
                        if (!p.getString("url", "").isNullOrBlank() && !p.getString("key", "").isNullOrBlank())
                            Sender.handleOut(applicationContext, Sender.send(applicationContext, "ping", "", ""))
                    } catch (e: Exception) { }
                    try { Thread.sleep(15000) } catch (e: InterruptedException) { }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() { running = false; super.onDestroy() }
}

/** ফোন রিস্টার্ট হলে রোবট নিজে চালু হয়। */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action == Intent.ACTION_BOOT_COMPLETED) Poller.start(c)
    }
}
