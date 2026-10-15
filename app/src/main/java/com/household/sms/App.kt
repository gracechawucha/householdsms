package com.household.sms

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // runs whenever Android starts our process (incl. for an incoming text), cheap and idempotent
        Scheduler.schedulePeriodic(this)
    }
}
