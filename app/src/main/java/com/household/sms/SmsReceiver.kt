package com.household.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

/**
 * Called by Android for every incoming text. M-Pesa confirmations are written to the
 * phone's queue immediately (so they cannot be lost), then sent. Other texts are ignored.
 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return

        // long messages arrive in pieces from the same sender: glue them back together
        val bySender = LinkedHashMap<String, StringBuilder>()
        var time = System.currentTimeMillis()
        for (p in parts) {
            bySender.getOrPut(p.originatingAddress ?: "") { StringBuilder() }.append(p.messageBody ?: "")
            time = p.timestampMillis
        }

        var any = false
        for ((address, text) in bySender) {
            val body = text.toString()
            if (Mpesa.isMpesa(address, body)) {
                Db.get(context).enqueue(Mpesa.code(body), body, time)
                any = true
            }
        }
        if (any) Scheduler.sendSoon(context)
    }
}
