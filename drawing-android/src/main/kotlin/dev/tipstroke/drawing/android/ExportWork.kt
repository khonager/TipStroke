package dev.tipstroke.drawing.android

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.*
import java.util.concurrent.CopyOnWriteArraySet

/** One user-initiated export at a time; callbacks and UI state are delivered on main. */
object ExportWork {
    data class State(val running: Boolean = false, val progress: Float? = null, val message: String = "")
    @Volatile var state = State()
        private set
    private val listeners = CopyOnWriteArraySet<(State) -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    internal var pending: (((Float) -> Unit) -> Result<Unit>)? = null
    internal var complete: ((Result<Unit>) -> Unit)? = null
    fun observe(listener: (State) -> Unit) { listeners += listener; listener(state) }
    fun removeObserver(listener: (State) -> Unit) { listeners -= listener }
    internal fun publish(value: State) { state = value; listeners.forEach { it(value) } }
    fun start(context: Context, work: ((Float) -> Unit) -> Result<Unit>, onComplete: (Result<Unit>) -> Unit = {}): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (state.running) { onComplete(Result.failure(IllegalStateException("An export is already running"))); return false }
        pending = work
        complete = onComplete
        publish(State(true, null, "Preparing export…"))
        try { context.applicationContext.startForegroundService(Intent(context, ExportService::class.java)) }
        catch (error: Exception) {
            pending = null; complete = null
            publish(State(message = error.message ?: "Could not start export"))
            onComplete(Result.failure(error)); return false
        }
        return true
    }
    internal fun progress(value: Float) { main.post {
        if (state.running) publish(State(true, value.coerceIn(0f, 1f), "Exporting…"))
    } }
}

class ExportService : Service() {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var timedOut = false
    private var lastPercent = -2
    private val observer: (ExportWork.State) -> Unit = { state ->
        val percent = state.progress?.times(100)?.toInt() ?: -1
        if (state.running && percent != lastPercent) {
            lastPercent = percent
            getSystemService(NotificationManager::class.java).notify(41, notification(state))
        }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("exports", "Exports", NotificationManager.IMPORTANCE_LOW))
    }
    private fun notification(state: ExportWork.State): Notification = Notification.Builder(this, "exports")
        .setSmallIcon(android.R.drawable.stat_sys_upload)
        .setContentTitle(if (state.running) "Exporting TipStroke drawing" else "TipStroke export")
        .setContentText(if (state.running && state.progress != null) "${(state.progress * 100).toInt()}%" else state.message)
        .setOngoing(state.running).setOnlyAlertOnce(true)
        .apply {
            if (state.running) setProgress(100, ((state.progress ?: 0f) * 100).toInt(), state.progress == null)
            packageManager.getLaunchIntentForPackage(packageName)?.let {
                setContentIntent(PendingIntent.getActivity(this@ExportService, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            }
        }.build()
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(41, notification(ExportWork.state),
            if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        val work = ExportWork.pending
        ExportWork.pending = null
        if (work == null) { stopSelf(startId); return START_NOT_STICKY }
        ExportWork.observe(observer)
        ProjectPersistence.executor.execute {
            val result = runCatching { work { progress ->
                check(!timedOut) { "Export exceeded Android's background time limit." }
                ExportWork.progress(progress)
            }.getOrThrow() }
            main.post {
                val state = ExportWork.State(message = result.fold({ "Export saved." }, { it.message ?: "Export failed." }))
                ExportWork.publish(state)
                ExportWork.removeObserver(observer)
                stopForeground(STOP_FOREGROUND_REMOVE)
                getSystemService(NotificationManager::class.java).notify(42, notification(state))
                val complete = ExportWork.complete; ExportWork.complete = null
                complete?.invoke(result)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        timedOut = true
        ExportWork.publish(ExportWork.State(true, null, "Stopping export: Android background time limit reached."))
        stopSelf()
    }
    override fun onDestroy() { ExportWork.removeObserver(observer); super.onDestroy() }
}
