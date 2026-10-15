package com.household.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Sends whatever is waiting in the queue; WorkManager retries it with backoff until it works. */
class SendWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) { if (Sender.flush(applicationContext)) Result.success() else Result.retry() }
}

/** Reads the phone's SMS inbox and sends any M-Pesa message the server does not have. */
class SweepWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val days = inputData.getInt("days", 3)
        Sweeper.run(applicationContext, days)
        if (Sender.flush(applicationContext)) Result.success() else Result.retry()
    }
}

object Sweeper {
    fun run(ctx: Context, days: Int) {
        if (ctx.checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            Cfg.sweepDone(ctx, "No permission to read messages yet")
            return
        }
        if (!Cfg.configured(ctx)) {
            Cfg.sweepDone(ctx, "Not set up yet")
            return
        }
        val db = Db.get(ctx)
        val since = System.currentTimeMillis() - days * 86_400_000L

        // 1. every M-Pesa message in the inbox for the period, that we have not already confirmed
        val found = LinkedHashMap<String, Pair<String, Long>>() // code -> (body, time)
        ctx.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf("address", "body", "date"),
            "date >= ?",
            arrayOf(since.toString()),
            "date ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val address = c.getString(0)
                val body = c.getString(1) ?: continue
                val date = c.getLong(2)
                if (!Mpesa.isMpesa(address, body)) continue
                val code = Mpesa.code(body)
                if (code.isBlank() || db.isSent(code) || found.containsKey(code)) continue
                found[code] = body to date
            }
        }

        if (found.isEmpty()) {
            Cfg.sweepDone(ctx, "Last $days days: nothing new to check")
            return
        }

        // 2. ask the server which of them it is missing
        val missing = HashSet<String>()
        for (chunk in found.keys.chunked(100)) {
            val m = Net.missing(ctx, chunk)
            if (m == null) {
                Cfg.sweepDone(ctx, "Could not ask the server, will try again")
                return
            }
            missing.addAll(m)
        }

        // 3. the server already has the rest: remember that, and queue only what is missing
        var queued = 0
        for ((code, bm) in found) {
            if (code in missing) {
                if (db.enqueue(code, bm.first, bm.second)) queued++
            } else {
                db.markSent(code)
            }
        }
        Cfg.sweepDone(ctx, "Last $days days: checked ${found.size}, $queued were missing and sent")
    }
}

object Scheduler {
    private fun online() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun sendSoon(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<SendWorker>()
            .setConstraints(online())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("send", ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }

    /** The background check: every 15 minutes (Android's minimum), re-read the last 3 days of the inbox. */
    fun schedulePeriodic(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<SweepWorker>(15, TimeUnit.MINUTES)
            .setConstraints(online())
            .setInputData(workDataOf("days" to 3))
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("sweep", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun sweepNow(ctx: Context, days: Int) {
        val req = OneTimeWorkRequestBuilder<SweepWorker>()
            .setConstraints(online())
            .setInputData(workDataOf("days" to days))
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("sweepNow", ExistingWorkPolicy.REPLACE, req)
    }
}
