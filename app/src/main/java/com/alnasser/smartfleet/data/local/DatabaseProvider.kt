package com.alnasser.smartfleet.data.local

import android.content.Context
import androidx.room.Room

object DatabaseProvider {
    @Volatile private var instance: FleetDatabase? = null
    fun get(context: Context): FleetDatabase = instance ?: synchronized(this) {
        instance ?: Room.databaseBuilder(context.applicationContext, FleetDatabase::class.java, "fleet.db")
            .build().also { instance = it }
    }
}
