package com.alnasser.smartfleet.data.local

import android.database.sqlite.SQLiteConstraintException
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
class OutboxDaoTest {
    private lateinit var db: FleetDatabase
    private lateinit var dao: OutboxDao
    private fun command(id: String, user: String = "driver", created: Long = 1) =
        OutboxCommand(id, "fleet", user, "fuelRequest", "{}", created)

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), FleetDatabase::class.java).build()
        dao = db.outbox()
    }
    @After fun tearDown() = db.close()

    @Test fun claimAndAcknowledgeAreScopedAndTokenProtected() = runBlocking {
        dao.insert(command("one"))
        assertNull(dao.claimNext("fleet", "other", 10, 20, "token"))
        val claimed = dao.claimNext("fleet", "driver", 10, 20, "token")!!
        assertEquals(1, claimed.attempts)
        assertNull(dao.claimNext("fleet", "driver", 11, 21, "other"))
        assertEquals(0, dao.acknowledge("one", "fleet", "driver", "wrong"))
        assertEquals(0, dao.acknowledge("one", "fleet", "other", "token"))
        assertEquals(1, dao.acknowledge("one", "fleet", "driver", "token"))
        assertEquals("SENT", dao.observeForAccount("fleet", "driver").first().single().state)
        assertNull(dao.claimNext("fleet", "driver", 30, 40, "new"))
    }

    @Test fun expiredLeaseCanBeRecoveredButOldWorkerCannotAcknowledge() = runBlocking {
        dao.insert(command("one"))
        dao.claimNext("fleet", "driver", 10, 20, "old")
        assertEquals(2, dao.claimNext("fleet", "driver", 20, 30, "new")!!.attempts)
        assertEquals(0, dao.acknowledge("one", "fleet", "driver", "old"))
        assertEquals(1, dao.retry("one", "fleet", "driver", "new", "unavailable"))
        assertEquals("PENDING", dao.observeForAccount("fleet", "driver").first().single().state)
    }

    @Test fun failureIsNotAutomaticallyRetriedAndOrderIsStable() = runBlocking {
        dao.insert(command("b")); dao.insert(command("a")); dao.insert(command("other", "other"))
        assertEquals("a", dao.claimNext("fleet", "driver", 10, 20, "token")!!.id)
        assertEquals(1, dao.fail("a", "fleet", "driver", "token", "invalid-argument"))
        assertEquals("b", dao.claimNext("fleet", "driver", 10, 20, "next")!!.id)
        assertEquals(2, dao.observeForAccount("fleet", "driver").first().size)
    }

    @Test fun duplicateIdIsRejected() = runBlocking {
        dao.insert(command("one"))
        try {
            dao.insert(command("one"))
            fail("duplicate id was accepted")
        } catch (_: SQLiteConstraintException) { /* expected */ }
        assertEquals(1, dao.observeForAccount("fleet", "driver").first().size)
    }
}
