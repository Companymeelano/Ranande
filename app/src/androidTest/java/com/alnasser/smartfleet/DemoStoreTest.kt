package com.alnasser.smartfleet

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DemoStoreTest {
    private lateinit var context: Context
    private val name = "demo-instrumentation-test"
    private var time = 1742515200000L // 1404-01-01 in Tehran
    private lateinit var store: DemoStore
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        store = DemoStore(context, name) { time }
    }
    @After fun cleanup() { context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit() }

    @Test fun storesUserDataAndPreventsDuplicatePlate() {
        store.addVehicle("۱۲۳", "ایسوزو")
        try { store.addVehicle("۱۲۳", "دیگر"); fail("duplicate plate") } catch (_: IllegalArgumentException) { }
        assertEquals("۱۲۳", DemoStore(context, name) { time }.list("vehicles").single().getString("plate"))
    }
    @Test fun splitQuotaSnapshotsRatesAndResetsPerVehicleAndMonth() {
        store.addVehicle("الف", "ایسوزو"); store.addVehicle("ب", "ایسوزو")
        val a = store.list("vehicles").first { it.getString("plate") == "الف" }.getString("id")
        val b = store.list("vehicles").first { it.getString("plate") == "ب" }.getString("id")
        store.saveSettings("70", "300", "80000")
        store.addFuel(a, "۱۰۰")
        val first = store.list("fuel").first()
        assertEquals(70000L, first.getLong("govMl")); assertEquals(30000L, first.getLong("freeMl")); assertEquals(2421000L, first.getLong("total"))
        store.saveSettings("70", "900", "100000")
        assertEquals(300L, store.list("fuel").first().getLong("govRate"))
        store.addFuel(a, "1"); assertEquals(0L, store.list("fuel").first().getLong("govMl"))
        store.addFuel(b, "1"); assertEquals(1000L, store.list("fuel").first().getLong("govMl"))
        time = 1745193600000L // subsequent Persian month
        store.addFuel(a, "1"); assertEquals(1000L, store.list("fuel").first().getLong("govMl"))
    }
    @Test fun missionRequiresRealInputsAndHasControlledTransitions() {
        store.addVehicle("الف", "ایسوزو"); store.addCustomer("فروشگاه", "تهران", "")
        val v = store.list("vehicles").single().getString("id"); val c = store.list("customers").single().getString("id")
        try { store.addMission(v, emptySet(), "برنج"); fail("missing destination") } catch (_: IllegalArgumentException) { }
        store.addMission(v, setOf(c), "برنج")
        val id = store.list("missions").single().getString("id")
        store.advanceMission(id); assertEquals("active", store.list("missions").single().getString("status"))
        store.advanceMission(id); assertEquals("completed", store.list("missions").single().getString("status"))
    }
    @Test fun corruptedDataIsNotOverwrittenUntilExplicitClear() {
        context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().putString("data", "bad json").commit()
        val broken = DemoStore(context, name) { time }
        assertNotNull(broken.error)
        try { broken.addVehicle("الف", "ایسوزو"); fail("corrupt data overwritten") } catch (_: IllegalStateException) { }
        assertEquals("bad json", context.getSharedPreferences(name, Context.MODE_PRIVATE).getString("data", ""))
        broken.clear(); assertNull(broken.error); assertTrue(broken.list("vehicles").isEmpty())
    }
}
