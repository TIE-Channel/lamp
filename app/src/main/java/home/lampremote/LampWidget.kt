package home.lampremote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.widget.RemoteViews

/** Renders the last known lamp state into the two-button home-screen widget. */
object LampWidget {
  const val COLOR_NORMAL = 0xFFFFFFFF.toInt()
  const val COLOR_SELECTED = 0xFFCFC93F.toInt() // same accent as the blinds widget

  private fun actionIntent(context: Context, action: String, requestCode: Int): PendingIntent {
    val intent = Intent(context, LampActionReceiver::class.java).apply {
      data = Uri.parse("lamp://$action")
      putExtra("action", action)
    }
    return PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  }

  /** How many copies of the widget are on the home screen. */
  fun count(context: Context): Int =
    AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, LampWidgetProvider::class.java)).size

  fun refresh(context: Context) {
    val manager = AppWidgetManager.getInstance(context)
    val ids = manager.getAppWidgetIds(ComponentName(context, LampWidgetProvider::class.java))
    if (ids.isEmpty()) return
    val mode = Store.state(context).mode(Config.load(context)?.dps ?: DpMap.DEFAULT)
    val views = RemoteViews(context.packageName, R.layout.lamp_widget)
    // No button background: the active mode is shown by tinting its icon, as with the blinds.
    views.setInt(R.id.lamp_dim, "setColorFilter", if (mode == Mode.DIM) COLOR_SELECTED else COLOR_NORMAL)
    views.setInt(R.id.lamp_bright, "setColorFilter", if (mode == Mode.BRIGHT) COLOR_SELECTED else COLOR_NORMAL)
    views.setOnClickPendingIntent(R.id.lamp_dim, actionIntent(context, "dim", 1))
    views.setOnClickPendingIntent(R.id.lamp_bright, actionIntent(context, "bright", 2))
    manager.updateAppWidget(ids, views)
  }
}

class LampWidgetProvider : AppWidgetProvider() {
  override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
    LampWidget.refresh(context)
  }
}

/** Receives taps on the widget buttons and hands them to [LampService]. */
class LampActionReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val action = intent.getStringExtra("action") ?: return
    try {
      // A tap on a widget allows starting a foreground service from the background.
      context.startForegroundService(Intent(context, LampService::class.java).putExtra("action", action))
    } catch (e: Exception) {
      // Android refused the service: send the command from the receiver itself, which has
      // about ten seconds, and close the link at once instead of keeping it for the next tap.
      Log.i(TAG, "no foreground service (${e.javaClass.simpleName}), sending from the receiver")
      val pending = goAsync()
      if (!Lamp.act(context, action) { Lamp.drop { pending.finish() } }) pending.finish()
    }
  }
}

/** Connects to the lamp and sends the command for a widget tap; that takes seconds, too long for a receiver. */
class LampService : Service() {
  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val notification = buildNotification()
    if (Build.VERSION.SDK_INT >= 34) {
      startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
    } else {
      startForeground(NOTIFICATION_ID, notification)
    }
    // Stay alive until the Bluetooth link is closed, so that a second tap finds it still open.
    val finish: (String?) -> Unit = { Lamp.whenIdle { stopSelf(startId) } }
    if (!Lamp.act(this, intent?.getStringExtra("action") ?: "", finish)) stopSelf(startId)
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    stopForeground(STOP_FOREGROUND_REMOVE)
    super.onDestroy()
  }

  // shortService may run for ~3 minutes; a command takes seconds, this is a safety net.
  override fun onTimeout(startId: Int) {
    stopSelf()
  }

  /** Android requires a notification while the service runs. Minimum importance: collapsed, never on the lock screen. */
  private fun buildNotification(): Notification {
    val manager = getSystemService(NotificationManager::class.java)
    if (manager.getNotificationChannel(CHANNEL_ID) == null) {
      val channel = NotificationChannel(CHANNEL_ID, "Отправка команд", NotificationManager.IMPORTANCE_MIN).apply {
        description = "Коротко видно, пока команда с виджета уходит на лампу"
        lockscreenVisibility = Notification.VISIBILITY_SECRET
        setShowBadge(false)
      }
      manager.createNotificationChannel(channel)
    }
    return Notification.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_warm)
      .setContentTitle("Лампа: отправляю команду…")
      .setVisibility(Notification.VISIBILITY_SECRET)
      .setOnlyAlertOnce(true)
      .setShowWhen(false)
      .build()
  }

  companion object {
    private const val CHANNEL_ID = "lamp_working"
    private const val NOTIFICATION_ID = 4801
  }
}
