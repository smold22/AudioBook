package com.yourapp.audiobook.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Paid
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.rememberLottieComposition
import com.yourapp.audiobook.R
import dev.chrisbanes.haze.hazeSource

private data class DonateOption(
    val title: String,
    val subtitle: String,
    val url: String?,
    val copyText: String? = null,
)

private val donateOptions = listOf(
    DonateOption(
        title = "Халва",
        subtitle = "Номер карты: 2200271132125221",
        url = null,
        copyText = "2200271132125221",
    ),
    DonateOption(
        title = "Сбер",
        subtitle = "Номер карты: 2202208864624896",
        url = null,
        copyText = "2202208864624896",
    ),
    DonateOption(
        title = "Газпромбанк",
        subtitle = "Номер карты: 2200012779564912",
        url = null,
        copyText = "2200012779564912",
    ),
    DonateOption(
        title = "ВТБ",
        subtitle = "Номер карты: 2200240721059216",
        url = null,
        copyText = "2200240721059216",
    ),
    DonateOption(
        title = "ЮMoney",
        subtitle = "Номер кошелька: 4100118897580208",
        url = "https://yoomoney.ru/to/4100118897580208",
        copyText = "4100118897580208",
    ),
)

@Composable
fun DonateScreen(navController: NavHostController) {
    val context = LocalContext.current
    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .hazeSource(screenHaze())
                .verticalScroll(rememberScrollState())
                .padding(top = GlassHeaderHeight, bottom = GlassBottomClearance, start = 16.dp, end = 16.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Приложение бесплатно и без рекламы. Если оно вам нравится — поддержите разработку любым удобным способом.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            donateOptions.forEach { option ->
                DonateRow(
                    option = option,
                    onClick = {
                        val copyText = option.copyText
                        if (copyText != null) {
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText(option.title, copyText))
                            android.widget.Toast.makeText(
                                context,
                                "${option.title}: номер скопирован",
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        } else if (option.url != null) {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(option.url)).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    },
                                )
                            }
                        }
                    },
                )
            }
            Spacer(Modifier.height(16.dp))
            val catComposition = rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.cat))
            LottieAnimation(
                composition = catComposition.value,
                modifier = Modifier.fillMaxWidth(),
                iterations = Int.MAX_VALUE,
            )
        }
        GlassHeader(
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
            }
            Text("Донаты", style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun DonateRow(
    option: DonateOption,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .tvFocus()
            .padding(horizontal = 4.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Paid,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(option.title, style = MaterialTheme.typography.bodyLarge)
            Text(
                option.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}