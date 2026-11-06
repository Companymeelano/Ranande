package com.alnasser.smartfleet

import android.content.Context
import android.icu.util.Calendar
import android.icu.util.ULocale
import android.icu.util.TimeZone
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Local-only presentation data. Never used by Firebase, Room or real financial flows. */
class DemoStore(context: Context, preferenceName: String = "isolated-demo-v1", private val clock: () -> Long = System::currentTimeMillis) {
    private val preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
    var error: String? by mutableStateOf(null); private set
    var data: JSONObject by mutableStateOf(load()); private set

    private fun emptyData() = JSONObject().put("version", 1).put("vehicles", JSONArray()).put("customers", JSONArray())
        .put("missions", JSONArray()).put("fuel", JSONArray()).put("services", JSONArray())
        .put("settings", JSONObject().put("quotaMl", 300000L).put("govRate", 300L).put("freeRate", 80000L))
    private fun load(): JSONObject {
      return try {
        val text = preferences.getString("data", null) ?: return emptyData()
        JSONObject(text).also { obj ->
            require(obj.getInt("version") == 1)
            listOf("vehicles", "customers", "missions", "fuel", "services").forEach { obj.getJSONArray(it) }
            obj.getJSONObject("settings")
        }
      } catch (_: Exception) { error = "خواندن داده آزمایشی ناموفق بود؛ از پاک‌سازی آزمایشی استفاده کنید."; emptyData() }
    }
    private fun mutate(block: (JSONObject) -> Unit) {
        check(error == null) { "ابتدا مشکل داده آزمایشی را رفع کنید." }
        val next = JSONObject(data.toString())
        block(next)
        check(preferences.edit().putString("data", next.toString()).commit()) { "ذخیره داده آزمایشی ناموفق بود." }
        data = next
    }
    private fun text(value: String, label: String, max: Int): String = value.trim().also { require(it.isNotEmpty() && it.length <= max) { "$label را معتبر وارد کنید." } }
    private fun append(data: JSONObject, name: String, value: JSONObject) {
        val array = data.getJSONArray(name)
        require(array.length() < 500) { "حداکثر ۵۰۰ رکورد آزمایشی در هر بخش مجاز است." }
        array.put(value)
    }
    fun list(name: String): List<JSONObject> = (0 until data.getJSONArray(name).length()).map { data.getJSONArray(name).getJSONObject(it) }.reversed()
    private fun contains(data: JSONObject, key: String, id: String): Boolean = (0 until data.getJSONArray(key).length()).any { data.getJSONArray(key).getJSONObject(it).getString("id") == id }
    private fun id() = UUID.randomUUID().toString()

    fun addVehicle(plate: String, model: String) = mutate { d ->
        val p = text(plate, "پلاک", 40); val m = text(model, "مدل", 100)
        require((0 until d.getJSONArray("vehicles").length()).none { d.getJSONArray("vehicles").getJSONObject(it).getString("plate") == p }) { "این پلاک قبلاً ثبت شده است." }
        append(d, "vehicles", JSONObject().put("id", id()).put("plate", p).put("model", m))
    }
    fun addCustomer(name: String, address: String, phone: String) = mutate { d ->
        val n = text(name, "نام مشتری", 100); val a = text(address, "نشانی", 200)
        require(phone.trim().length <= 30) { "شماره تماس معتبر نیست." }
        append(d, "customers", JSONObject().put("id", id()).put("name", n).put("address", a).put("phone", phone.trim()))
    }
    fun addMission(vehicle: String, customers: Set<String>, goods: String) = mutate { d ->
        require(contains(d, "vehicles", vehicle)) { "خودرو را انتخاب کنید." }
        require(customers.isNotEmpty() && customers.all { contains(d, "customers", it) }) { "حداقل یک مشتری انتخاب کنید." }
        require(list("missions").none { it.getString("vehicleId") == vehicle && it.getString("status") == "active" }) { "این خودرو مأموریت فعال دارد." }
        val destinations = JSONArray(); customers.sorted().forEach { destinations.put(it) }
        append(d, "missions", JSONObject().put("id", id()).put("vehicleId", vehicle).put("customerIds", destinations)
            .put("goods", text(goods, "شرح کالا", 200)).put("status", "planned").put("createdAt", clock()))
    }
    fun advanceMission(id: String) = mutate { d ->
        val all = d.getJSONArray("missions")
        val mission = (0 until all.length()).map { all.getJSONObject(it) }.firstOrNull { it.getString("id") == id } ?: error("مأموریت یافت نشد.")
        when (mission.getString("status")) {
            "planned" -> {
                require((0 until all.length()).map { all.getJSONObject(it) }.none { it.getString("vehicleId") == mission.getString("vehicleId") && it.getString("status") == "active" }) { "این خودرو مأموریت فعال دارد." }
                mission.put("status", "active").put("startedAt", clock())
            }
            "active" -> mission.put("status", "completed").put("endedAt", clock())
            else -> error("مأموریت پایان یافته است.")
        }
    }
    fun addFuel(vehicle: String, amountText: String) = mutate { d ->
        require(contains(d, "vehicles", vehicle)) { "خودرو را انتخاب کنید." }
        val amount = parseLitersToMl(amountText)?.takeIf { it <= 10_000_000L } ?: throw IllegalArgumentException("حجم معتبر تا ۱۰٬۰۰۰ لیتر وارد کنید.")
        val month = monthKey(clock())
        val settings = d.getJSONObject("settings")
        val used = (0 until d.getJSONArray("fuel").length()).map { d.getJSONArray("fuel").getJSONObject(it) }
            .filter { it.getString("vehicleId") == vehicle && it.getString("month") == month }.sumOf { it.getLong("govMl") }
        val gov = minOf(amount, (settings.getLong("quotaMl") - used).coerceAtLeast(0L))
        val free = amount - gov
        val gr = settings.getLong("govRate"); val fr = settings.getLong("freeRate")
        val total = (gov * gr + 500L) / 1000L + (free * fr + 500L) / 1000L
        append(d, "fuel", JSONObject().put("id", id()).put("vehicleId", vehicle).put("month", month).put("amountMl", amount)
            .put("govMl", gov).put("freeMl", free).put("govRate", gr).put("freeRate", fr).put("total", total).put("createdAt", clock()))
    }
    fun addService(vehicle: String, description: String, cost: String) = mutate { d ->
        require(contains(d, "vehicles", vehicle)) { "خودرو را انتخاب کنید." }
        val amount = asciiNumber(cost).toLongOrNull()?.takeIf { it in 0..1_000_000_000_000L } ?: throw IllegalArgumentException("هزینه معتبر به تومان وارد کنید.")
        append(d, "services", JSONObject().put("id", id()).put("vehicleId", vehicle).put("description", text(description, "شرح سرویس", 150)).put("cost", amount).put("createdAt", clock()))
    }
    fun saveSettings(quota: String, govRate: String, freeRate: String) = mutate { d ->
        val q = parseLitersToMl(quota)?.takeIf { it <= 10_000_000L } ?: throw IllegalArgumentException("سهمیه معتبر تا ۱۰٬۰۰۰ لیتر وارد کنید.")
        val g = asciiNumber(govRate).toLongOrNull()?.takeIf { it in 0..100_000_000L } ?: throw IllegalArgumentException("نرخ دولتی معتبر وارد کنید.")
        val f = asciiNumber(freeRate).toLongOrNull()?.takeIf { it in 0..100_000_000L } ?: throw IllegalArgumentException("نرخ آزاد معتبر وارد کنید.")
        d.put("settings", JSONObject().put("quotaMl", q).put("govRate", g).put("freeRate", f))
    }
    fun clear() {
        check(preferences.edit().remove("data").commit()) { "پاک‌سازی انجام نشد." }
        error = null; data = emptyData()
    }
    fun vehicleName(id: String) = list("vehicles").firstOrNull { it.optString("id") == id }?.optString("plate") ?: "خودروی نامشخص"
    fun customerName(id: String) = list("customers").firstOrNull { it.optString("id") == id }?.optString("name") ?: "مشتری نامشخص"
}

fun asciiNumber(value: String): String = value.trim().map { c -> when (c) {
    in '۰'..'۹' -> '0' + (c - '۰'); in '٠'..'٩' -> '0' + (c - '٠'); else -> c
} }.joinToString("")
fun monthKey(millis: Long): String {
    val calendar = Calendar.getInstance(TimeZone.getTimeZone("Asia/Tehran"), ULocale("fa_IR@calendar=persian"))
    calendar.timeInMillis = millis
    return "%04d%02d".format(java.util.Locale.US, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH) + 1)
}
