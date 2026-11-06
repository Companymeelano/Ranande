package com.alnasser.smartfleet.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alnasser.smartfleet.data.sync.OutboxSync
import com.alnasser.smartfleet.data.sync.SendOutcome
import com.alnasser.smartfleet.data.sync.SyncOutcome
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OutboxSyncTest {
    private lateinit var db: FleetDatabase
    private lateinit var dao: OutboxDao
    private fun item(id: String, user: String = "driver") = OutboxCommand(id, "fleet", user, "fuelRequest", "{}", 1)
    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), FleetDatabase::class.java).build()
        dao = db.outbox()
    }
    @After fun tearDown() = db.close()

    @Test fun retryKeepsSameIdAndLaterAcknowledges() = runBlocking {
        dao.insert(item("request"))
        val seen = mutableListOf<String>()
        var attempts = 0
        val sync = OutboxSync(dao, { "driver" }, { c -> seen.add(c.id); if (attempts++ == 0) SendOutcome.Retry("unavailable") else SendOutcome.Accepted }, { 100 })
        assertEquals(SyncOutcome.RETRY, sync.run("fleet", "driver"))
        assertEquals("PENDING", dao.observeForAccount("fleet", "driver").first().single().state)
        assertEquals(SyncOutcome.COMPLETE, sync.run("fleet", "driver"))
        assertEquals(listOf("request", "request"), seen)
        assertEquals("SENT", dao.observeForAccount("fleet", "driver").first().single().state)
    }

    @Test fun wrongAccountNeverSendsAndTerminalErrorsAreVisible() = runBlocking {
        dao.insert(item("request"))
        var sent = false
        val wrong = OutboxSync(dao, { "other" }, { sent = true; SendOutcome.Accepted }, { 100 })
        assertEquals(SyncOutcome.SESSION_CHANGED, wrong.run("fleet", "driver")); assertFalse(sent)
        val rejected = OutboxSync(dao, { "driver" }, { SendOutcome.Rejected("permission_denied") }, { 100 })
        assertEquals(SyncOutcome.COMPLETE, rejected.run("fleet", "driver"))
        val command = dao.observeForAccount("fleet", "driver").first().single()
        assertEquals("FAILED", command.state); assertEquals("permission_denied", command.lastError)
    }

    @Test fun inFlightLeaseIsRetriedRatherThanLost() = runBlocking {
        dao.insert(item("request"))
        dao.claimNext("fleet", "driver", 100, 200, "old")
        val sync = OutboxSync(dao, { "driver" }, { SendOutcome.Accepted }, { 150 })
        assertEquals(SyncOutcome.RETRY, sync.run("fleet", "driver"))
        val recovered = OutboxSync(dao, { "driver" }, { SendOutcome.Accepted }, { 200 })
        assertEquals(SyncOutcome.COMPLETE, recovered.run("fleet", "driver"))
        assertEquals(0, dao.acknowledge("request", "fleet", "driver", "old"))
    }

    @Test fun batchLimitLeavesRemainingCommandScheduledForRetry() = runBlocking {
        dao.insert(item("a")); dao.insert(item("b"))
        val sync = OutboxSync(dao, { "driver" }, { SendOutcome.Accepted }, { 100 })
        assertEquals(SyncOutcome.RETRY, sync.run("fleet", "driver", maxBatch = 1))
        assertEquals(SyncOutcome.COMPLETE, sync.run("fleet", "driver", maxBatch = 1))
    }
}
