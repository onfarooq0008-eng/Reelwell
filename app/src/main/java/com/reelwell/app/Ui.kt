package com.reelwell.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage

private val Canvas = Color(0xFF101410)
private val Raised = Color(0xFF1E2722)
private val Surface = Color(0xFF28322C)
private val Line = Color(0xFF425047)
private val Fg = Color(0xFFF4F1E8)
private val Muted = Color(0xFF9AA59C)
private val Accent = Color(0xFFD6FF4A)
private val Ink = Color(0xFF142000)
private val Danger = Color(0xFFFF6B57)

@Composable
fun ReelwellTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            onPrimary = Ink,
            background = Canvas,
            onBackground = Fg,
            surface = Raised,
            onSurface = Fg,
        ),
        content = content,
    )
}

private fun formatDuration(seconds: Double?): String? {
    if (seconds == null || seconds.isNaN() || seconds <= 0) return null
    val total = Math.round(seconds).toInt()
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun formatBytes(bytes: Long?): String? {
    if (bytes == null || bytes <= 0) return null
    return if (bytes < 1024 * 1024) "${maxOf(1L, bytes / 1024)} KB" else "%.1f MB".format(bytes / (1024.0 * 1024.0))
}

@Composable
fun ReelwellScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var pending by remember { mutableStateOf<Pair<MediaItem, String>?>(null) }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val p = pending
        pending = null
        if (granted && p != null) {
            Downloader.enqueue(context, p.first, p.second)
        } else {
            Toast.makeText(context, "Storage permission is needed to save files.", Toast.LENGTH_LONG).show()
        }
    }

    fun download(item: MediaItem, name: String) {
        val needsPermission = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            pending = item to name
            permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            Downloader.enqueue(context, item, name)
        }
    }

    Box(Modifier.fillMaxSize().background(Canvas)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Reelwell", color = Fg, fontSize = 34.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Serif)
            Text(
                "Save public Instagram, Facebook and TikTok reels and videos.",
                color = Muted,
                fontSize = 15.sp,
            )

            OutlinedTextField(
                value = vm.input,
                onValueChange = { vm.input = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Paste a reel or video link", color = Muted) },
                minLines = 2,
                maxLines = 4,
                shape = RoundedCornerShape(16.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { vm.resolve() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Fg,
                    unfocusedTextColor = Fg,
                    focusedBorderColor = Accent,
                    unfocusedBorderColor = Line,
                    cursorColor = Accent,
                    focusedContainerColor = Raised,
                    unfocusedContainerColor = Raised,
                ),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { vm.resolve() },
                    enabled = !vm.loading && vm.input.isNotBlank(),
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Accent,
                        contentColor = Ink,
                        disabledContainerColor = Surface,
                        disabledContentColor = Muted,
                    ),
                ) {
                    if (vm.loading) {
                        CircularProgressIndicator(Modifier.size(20.dp), color = Ink, strokeWidth = 2.dp)
                        Spacer(Modifier.size(10.dp))
                        Text("Looking up…", fontWeight = FontWeight.SemiBold)
                    } else {
                        Text("Get media", fontWeight = FontWeight.SemiBold)
                    }
                }
                TextButton(onClick = {
                    clipboard.getText()?.text?.let { vm.input = it }
                }) { Text("Paste", color = Accent) }
                if (vm.input.isNotEmpty()) {
                    TextButton(onClick = { vm.clear() }) { Text("Clear", color = Muted) }
                }
            }

            vm.error?.let { message ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Raised)
                        .padding(16.dp),
                ) { Text(message, color = Danger, fontSize = 14.sp) }
            }

            vm.result?.let { result ->
                ResultCard(result, onDownload = { item -> download(item, Downloader.fileName(result, item)) })
            }

            Text(
                "Only works with public posts. Download content you have the right to save.",
                color = Muted,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun ResultCard(result: ResolvedMedia, onDownload: (MediaItem) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Raised)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (result.thumbnail != null) {
                AsyncImage(
                    model = result.thumbnail,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(88.dp).clip(RoundedCornerShape(12.dp)).background(Surface),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(result.platform.label.uppercase(), color = Accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Text(result.title, color = Fg, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                val sub = listOfNotNull(result.author, formatDuration(result.duration)).joinToString(" · ")
                if (sub.isNotEmpty()) Text(sub, color = Muted, fontSize = 13.sp)
            }
        }

        result.media.forEach { item ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Surface)
                    .padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(item.quality, color = Fg, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val meta = listOfNotNull(item.type.name.lowercase().replaceFirstChar { it.uppercase() }, item.ext.uppercase(), formatBytes(item.bytes))
                    Text(meta.joinToString(" · "), color = Muted, fontSize = 12.sp)
                }
                Button(
                    onClick = { onDownload(item) },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Ink),
                    border = BorderStroke(0.dp, Color.Transparent),
                ) { Text("Download", fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}
