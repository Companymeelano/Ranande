package com.alnasser.smartfleet.data.location

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocationDatabaseTest {
    private lateinit var db: LocationDatabase
    private lateinit var dao: LocationDao
    @Before fun setup() { db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LocationDatabase::class.java).build(); dao = db.sessions() }
    @After fun close() = db.close()
    @Test fun summariesAreAccountScopedAndInterruptedSessionsCannotBeUpdated() = runBlocking {
        dao.insert(GpsSession("a", "fleet", "driver", "m1", 100))
        dao.insert(GpsSession("b", "fleet", "other", "m1", 100))
        assertEquals(1, dao.update("a", 3, 120.5, "MOVEMENT"))
        assertEquals(120.5, dao.observe("fleet", "driver", "m1").first().single().meters, 0.001)
        assertTrue(dao.observe("another", "driver", "m1").first().isEmpty())
        dao.interruptOld("fleet", "driver", 200)
        assertEquals("INTERRUPTED", dao.observe("fleet", "driver", "m1").first().single().state)
        assertEquals(0, dao.update("a", 4, 200.0, "MOVEMENT"))
        assertEquals("RUNNING", dao.observe("fleet", "other", "m1").first().single().state)
        assertEquals(1, dao.finish("b", "STOPPED", 250))
    }
}
