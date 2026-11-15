package io.majung.pleos.ui

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import io.majung.pleos.R
import io.majung.pleos.model.PassengerSuggestion
import io.majung.pleos.model.SuggestionKind

/**
 * 동승자 제안을 헤드업 알림으로도 띄운다.
 *
 * 픽업을 시작하면 NaviHelper가 내비 앱을 앞으로 가져오므로, 주행 중에는 마중 화면의 제안 카드가 보이지 않는다.
 * 알림의 [수락]/[나중에]는 화면의 카드와 같은 동작(코디네이터의 resolveSuggestion)으로 이어진다.
 * 알림 권한이 없으면 아무것도 하지 않는다(음성 안내와 화면 카드는 그대로 동작).
 */
class SuggestionNotifier(private val context: Context, private val onAnswer: (accept: Boolean) -> Unit) {

    private val manager = context.getSystemService(NotificationManager::class.java)
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            manager.cancel(NOTIFICATION_ID)
            onAnswer(intent.action == ACTION_ACCEPT)
        }
    }

    fun register() {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "동승자 제안", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "동승자가 보낸 승차 자리 제안과 메시지"
            }
        )
        val filter = IntentFilter().apply { addAction(ACTION_ACCEPT); addAction(ACTION_LATER) }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") context.registerReceiver(receiver, filter)
    }

    fun unregister() {
        runCatching { context.unregisterReceiver(receiver) }
        manager.cancel(NOTIFICATION_ID)
    }

    /** 대기 중인 제안이 바뀔 때마다 호출한다. null이면 알림을 지운다. */
    fun show(suggestion: PassengerSuggestion?, passengerName: String, distanceFromPickupM: Int?) {
        if (suggestion == null) { manager.cancel(NOTIFICATION_ID); return }
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val needsAnswer = suggestion.kind != SuggestionKind.READY
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(titleFor(passengerName))
            .setContentText(textFor(suggestion, distanceFromPickupM))
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(PendingIntent.getActivity(context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE))
        if (needsAnswer) {
            builder.addAction(action("수락", ACTION_ACCEPT, 1))
            builder.addAction(action("나중에", ACTION_LATER, 2))
        }
        manager.notify(NOTIFICATION_ID, builder.build())
    }

    private fun action(label: String, action: String, requestCode: Int): Notification.Action =
        Notification.Action.Builder(null, label, PendingIntent.getBroadcast(context, requestCode,
            Intent(action).setPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE)).build()

    companion object {
        const val CHANNEL_ID = "majung_passenger_suggestion"
        const val NOTIFICATION_ID = 4568
        const val ACTION_ACCEPT = "io.majung.pleos.action.SUGGESTION_ACCEPT"
        const val ACTION_LATER = "io.majung.pleos.action.SUGGESTION_LATER"

        fun titleFor(passengerName: String) = "${passengerName.ifBlank { "동승자" }}님의 제안"

        /** 알림 본문. 고른 자리가 있으면 현재 승차 지점에서 얼마나 떨어졌는지 덧붙인다. */
        fun textFor(s: PassengerSuggestion, distanceFromPickupM: Int?): String =
            if (s.kind == SuggestionKind.PICKUP_HERE && distanceFromPickupM != null) "${s.text} · 승차 지점에서 $distanceFromPickupM m" else s.text
    }
}
