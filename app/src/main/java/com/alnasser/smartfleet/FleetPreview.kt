package com.alnasser.smartfleet

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Gold = Color(0xFFE3C580)
private val Muted = Color(0xFF9EADBD)
private val Background = Color(0xFF081423)
private val Border = Color(0xFF3D4C58)
private val Panel = Brush.verticalGradient(listOf(Color(0xFF1C3044), Color(0xFF102235)))

private data class Feature(@DrawableRes val icon: Int, val title: String, val english: String, val description: String, val status: String)
private val features = listOf(
    Feature(R.drawable.icon_fleet, "مدیریت ناوگان", "FLEET", "پرونده خودروهای ایسوزو، پلاک و ظرفیت باک در یک نمای یکپارچه.", "ثبت و مشاهده اولیه خودرو برای مدیر در مسیر اتصال واقعی موجود است."),
    Feature(R.drawable.icon_fuel, "مدیریت سوخت", "FUEL", "بررسی سهمیه، ثبت سوخت و تفکیک دولتی و آزاد برای هر خودرو.", "موتور اولیه سرور ساخته شده؛ اتصال و فرآیند کامل هنوز آماده نیست."),
    Feature(R.drawable.icon_gps, "ردیابی هوشمند", "LIVE GPS", "نمایش موقعیت و مسیر بر اساس GPS گوشی راننده.", "در حال توسعه؛ مکان یا داده زنده‌ای نمایش داده نمی‌شود."),
    Feature(R.drawable.icon_mission, "مأموریت‌ها", "MISSIONS", "برنامه‌ریزی توزیع و مدیریت مقصدهای هر مأموریت.", "در حال توسعه؛ مأموریت آزمایشی ایجاد نشده است."),
    Feature(R.drawable.icon_service, "سرویس و نگهداری", "SERVICE", "پیگیری سرویس دوره‌ای خودرو و یادآوری زمان رسیدگی.", "در حال توسعه؛ داده سرویس نمایش داده نمی‌شود."),
    Feature(R.drawable.icon_report, "گزارش‌های مدیریتی", "ANALYTICS", "مرور گزارش‌های ناوگان، مسافت و هزینه در یک نمای مدیریتی.", "در حال توسعه؛ گزارش یا نمودار ساختگی نمایش داده نمی‌شود."),
    Feature(R.drawable.icon_customer, "مشتریان", "CUSTOMERS", "مدیریت مقاصد پخش و اطلاعات مشتریان مجاز.", "در حال توسعه؛ اطلاعات مشتری ثبت نشده است."),
    Feature(R.drawable.icon_cooling, "سیستم سرمایشی", "COLD CHAIN", "نگهداری و پیگیری سرویس تجهیزات اتاق یخچالی.", "در حال توسعه؛ دمای واقعی از GPS قابل استنتاج نیست."),
)

@Composable
fun BrandIntro(onContinue: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF152B40), Background, Color(0xFF050D18))))
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("AL NASSER", color = Gold, letterSpacing = 5.sp, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(20.dp))
        Image(painterResource(R.drawable.fleet_brand), contentDescription = "نشان آل ناصر", modifier = Modifier.size(230.dp).clip(RoundedCornerShape(42.dp)))
        Spacer(Modifier.height(24.dp))
        Text("SMART FLEET", color = Color.White, fontSize = 24.sp, letterSpacing = 4.sp, fontWeight = FontWeight.SemiBold)
        Text("هوشمندی در مسیر اطمینان", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(52.dp))
        HorizontalDivider(Modifier.fillMaxWidth(.45f), color = Gold.copy(alpha = .45f))
        Text("DESIGNED & DEVELOPED BY", color = Muted, fontSize = 9.sp, letterSpacing = 2.sp, modifier = Modifier.padding(top = 18.dp))
        Text("Milad Yaghoobi", color = Gold, fontSize = 25.sp, fontFamily = FontFamily.Serif, letterSpacing = 1.sp)
        TextButton(onClick = onContinue) { Text("ورود به معرفی", color = Color.White.copy(alpha = .75f)) }
    }
}

@Composable
fun FleetPreview(onConnect: () -> Unit) {
    var tab by remember { mutableStateOf("overview") }
    var selected by remember { mutableStateOf<Feature?>(null) }
    BackHandler(enabled = selected != null || tab != "overview") {
        if (selected != null) selected = null else tab = "overview"
    }
    selected?.let { feature ->
        AlertDialog(
            onDismissRequest = { selected = null },
            icon = { Image(painterResource(feature.icon), contentDescription = null, modifier = Modifier.size(64.dp)) },
            title = { Text(feature.title, color = Color.White) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(feature.description, color = Color(0xFFD9E4EA))
                Text(feature.status, color = Gold, style = MaterialTheme.typography.bodySmall)
            } },
            confirmButton = { TextButton(onClick = { selected = null }) { Text("متوجه شدم", color = Gold) } },
            containerColor = Color(0xFF14283A),
        )
    }
    Column(Modifier.fillMaxSize().background(Background)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(20.dp))
            Header(onConnect)
            Spacer(Modifier.height(20.dp))
            when (tab) {
                "overview" -> {
                    Text("مدیریت هوشمند،", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                    Text("در مسیری مطمئن", color = Gold, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                    Text("همراه ناوگان پخش آل ناصر", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp, bottom = 20.dp))
                    HeroCard()
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        Metric("خودروها", "—", Modifier.weight(1f))
                        Metric("مأموریت‌ها", "—", Modifier.weight(1f))
                        Metric("اتصال", "غیرفعال", Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(24.dp))
                    SectionTitle("دسترسی سریع", "امکانات بیشتر", { tab = "modules" })
                    FeatureGrid(features.take(4), onSelect = { selected = it })
                    Spacer(Modifier.height(20.dp))
                    DemoNotice()
                }
                "modules" -> {
                    Text("امکانات هوشمند", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Text("برای آشنایی با هر بخش، آن را انتخاب کنید.", color = Muted, modifier = Modifier.padding(top = 8.dp, bottom = 20.dp), fontSize = 13.sp)
                    FeatureGrid(features, onSelect = { selected = it })
                    Spacer(Modifier.height(20.dp))
                    DemoNotice()
                }
                else -> AboutCard()
            }
            Spacer(Modifier.height(26.dp))
        }
        BottomNavigation(tab = tab, onSelect = { tab = it })
    }
}

@Composable
private fun Header(onConnect: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Image(painterResource(R.drawable.fleet_brand), contentDescription = "نشان آل ناصر", modifier = Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)))
            Column {
                Text("AL NASSER", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                Text("SMART FLEET", color = Gold, fontSize = 9.sp, letterSpacing = 1.sp)
            }
        }
        Text("اتصال واقعی", color = Gold, fontSize = 12.sp, modifier = Modifier.clip(RoundedCornerShape(14.dp)).border(1.dp, Gold.copy(alpha = .45f), RoundedCornerShape(14.dp)).clickable(onClick = onConnect).padding(horizontal = 12.dp, vertical = 10.dp))
    }
}

@Composable
private fun HeroCard() {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Panel).border(1.dp, Border, RoundedCornerShape(24.dp))) {
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("ناوگان یخچال‌دار", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text("COLD CHAIN FLEET", color = Gold, letterSpacing = 1.sp, fontSize = 9.sp, modifier = Modifier.padding(top = 4.dp))
            }
            Text("نمای معرفی", color = Color(0xFFBBD9E6), fontSize = 10.sp, modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0xFF28465A)).padding(horizontal = 10.dp, vertical = 6.dp))
        }
        Image(painterResource(R.drawable.fleet_hero), contentDescription = "تصویر مفهومی خودروی یخچال‌دار", modifier = Modifier.fillMaxWidth().height(185.dp), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        Text("خودروها و وضعیت‌ها پس از اتصال نمایش داده می‌شوند", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp))
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(Panel).border(1.dp, Border.copy(alpha = .7f), RoundedCornerShape(18.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(value, color = Gold, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, color = Muted, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun SectionTitle(title: String, action: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        TextButton(onClick = onClick) { Text(action, color = Gold, fontSize = 12.sp) }
    }
}

@Composable
private fun FeatureGrid(items: List<Feature>, onSelect: (Feature) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { feature ->
                    Column(Modifier.weight(1f).height(145.dp).clip(RoundedCornerShape(20.dp)).background(Panel)
                        .border(1.dp, Border.copy(alpha = .75f), RoundedCornerShape(20.dp)).clickable { onSelect(feature) }.padding(14.dp),
                        verticalArrangement = Arrangement.SpaceBetween) {
                        Image(painterResource(feature.icon), contentDescription = null, modifier = Modifier.size(48.dp))
                        Column {
                            Text(feature.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text(feature.english, color = Gold.copy(alpha = .8f), fontSize = 9.sp, letterSpacing = 1.sp, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun DemoNotice() {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0xFF1A2937)).border(1.dp, Gold.copy(alpha = .3f), RoundedCornerShape(18.dp)).padding(16.dp)) {
        Text("حالت معرفی • بدون اتصال", color = Gold, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text("این صفحه فقط برای آشنایی با طراحی و امکانات است؛ داده عملیاتی و اطلاعات ساختگی نمایش داده نمی‌شود.", color = Color(0xFFC5D1D9), fontSize = 12.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun AboutCard() {
    Spacer(Modifier.height(26.dp))
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Panel).border(1.dp, Gold.copy(alpha = .38f), RoundedCornerShape(28.dp)).padding(26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Image(painterResource(R.drawable.fleet_brand), contentDescription = "نشان آل ناصر", modifier = Modifier.size(132.dp).clip(RoundedCornerShape(24.dp)))
        Spacer(Modifier.height(22.dp))
        Text("AL NASSER SMART FLEET", color = Color.White, fontSize = 14.sp, letterSpacing = 1.2.sp, textAlign = TextAlign.Center)
        Text("سامانه هوشمند مدیریت ناوگان", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        HorizontalDivider(Modifier.padding(vertical = 24.dp), color = Border)
        Text("DESIGNED & DEVELOPED BY", color = Muted, fontSize = 10.sp, letterSpacing = 1.6.sp)
        Text("Milad Yaghoobi", color = Gold, fontFamily = FontFamily.Serif, fontSize = 28.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
        Text("نسخه معرفی محصول", color = Color(0xFFC6D4DD), fontSize = 12.sp, modifier = Modifier.padding(top = 18.dp))
    }
    Spacer(Modifier.height(20.dp))
    DemoNotice()
}

@Composable
private fun BottomNavigation(tab: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().background(Color(0xFF101F30)).padding(vertical = 10.dp, horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceAround) {
        listOf(Triple("overview", "خانه", R.drawable.icon_fleet), Triple("modules", "امکانات", R.drawable.icon_mission), Triple("about", "درباره", R.drawable.icon_customer)).forEach { (key, title, icon) ->
            Column(Modifier.clip(CircleShape).clickable { onSelect(key) }.padding(horizontal = 18.dp, vertical = 5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Image(painterResource(icon), contentDescription = null, modifier = Modifier.size(26.dp))
                Text(title, fontSize = 11.sp, color = if (tab == key) Gold else Muted, modifier = Modifier.padding(top = 3.dp))
            }
        }
    }
}
