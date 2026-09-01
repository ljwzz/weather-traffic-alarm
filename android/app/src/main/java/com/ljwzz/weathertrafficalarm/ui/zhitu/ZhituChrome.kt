package com.ljwzz.weathertrafficalarm.ui.zhitu

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.ljwzz.weathertrafficalarm.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZhituTopBar(title: String, subtitle: String? = null, navigation: (() -> Unit)? = null) {
    TopAppBar(
        title = {
            Column {
                Text(title, color = ZhituColors.Ink, fontWeight = FontWeight.Bold)
                subtitle?.let { Text(it, color = ZhituColors.Muted, style = androidx.compose.material3.MaterialTheme.typography.labelSmall) }
            }
        },
        navigationIcon = {
            if (navigation != null) Text("‹", modifier = Modifier.width(52.dp).clickable(onClick = navigation).padding(start = 22.dp), color = ZhituColors.Ink, style = androidx.compose.material3.MaterialTheme.typography.headlineMedium)
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = ZhituColors.Background),
    )
}

@Composable
fun ZhituNav(selected: ZhituDestination, onNavigate: (ZhituDestination) -> Unit = {}) {
    NavigationBar(containerColor = Color.White) {
        NavItem("今日", R.drawable.ic_figma_home, selected == ZhituDestination.HOME) { onNavigate(ZhituDestination.HOME) }
        NavItem("路线", R.drawable.ic_figma_route, selected == ZhituDestination.ROUTE) { onNavigate(ZhituDestination.ROUTE) }
        NavItem("闹钟", R.drawable.ic_figma_plans, selected == ZhituDestination.PLANS) { onNavigate(ZhituDestination.PLANS) }
        NavItem("设置", R.drawable.ic_figma_settings, selected == ZhituDestination.SETTINGS) { onNavigate(ZhituDestination.SETTINGS) }
    }
}

@Composable
private fun RowScope.NavItem(label: String, icon: Int, selected: Boolean, click: () -> Unit) = NavigationBarItem(
    selected = selected,
    onClick = click,
    icon = { Icon(painterResource(icon), contentDescription = null, tint = if (selected) ZhituColors.Brand else ZhituColors.Muted) },
    label = { Text(label) },
)

@Composable
fun SectionTitle(title: String, action: String? = null, onAction: (() -> Unit)? = null) = Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically) {
    Text(title, color = ZhituColors.Ink, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
    if (action != null && onAction != null) Text(action, color = ZhituColors.Brand, style = androidx.compose.material3.MaterialTheme.typography.labelMedium, modifier = Modifier.clickable(onClick = onAction))
}

@Composable
fun StatusBadge(label: String, bright: Boolean = false) = Text(
    label,
    color = ZhituColors.Brand,
    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
    modifier = Modifier.background(if (bright) ZhituColors.Mint else ZhituColors.Sky, androidx.compose.foundation.shape.RoundedCornerShape(99.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
)

@Composable
fun SafetyNotice(text: String) = Text(text, color = ZhituColors.Muted, style = androidx.compose.material3.MaterialTheme.typography.labelSmall, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
