package com.fanduck.agent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 日志页：把鸭子"看到 / 听到 / 做过"的全部上下文、每一轮发给云端的东西、模型每一轮的回话
 * 摊开看。数据全部来自 `filesDir/agent/` 下的落盘文件（不是内存），所以重启后照样能翻。
 *
 * 三个页签：
 *  1. 上下文 —— `pack(transcript)`：这一轮真会发出去的消息。**图片不在里面**（§8 不上传媒体），
 *     所以图片单独在「事件」页看。
 *  2. 事件 —— episodes.jsonl 的原始流（kind + 时间 + 文字 + 该条带的媒体）。
 *  3. 轨迹 —— transcript.json 的消息 + 摘要 + 归档条数。
 *
 * 用 Compose 写的（emin 要的）。这是 app 模块唯一的第三方依赖，其余仍是纯 View。
 */
class LogActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dir = File(filesDir, AGENT_DIR)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                LogScreen(dir = dir, onClose = { finish() })
            }
        }
    }

    private companion object {
        const val AGENT_DIR = "agent"
    }
}

/** 一次读取得到的快照。文件都不大（episodes 有 2000 条上限，§2），一次读完最简单。 */
private class Snapshot(
    val episodes: List<Episode>,
    val packed: List<Map<String, String>>,
    val messages: List<TranscriptMessage>,
    val summary: String,
    val archiveLines: Int,
    val files: List<Pair<String, Long>>,
) {
    companion object {
        fun read(dir: File): Snapshot {
            val transcript = readTranscript(File(dir, "transcript.json"))
            val names = listOf(
                "episodes.jsonl",
                "transcript.json",
                "transcript-archive.jsonl",
                "seen/latest.jpg",
            )
            return Snapshot(
                episodes = readEpisodes(File(dir, "episodes.jsonl")),
                packed = pack(transcript),
                messages = transcript.messages,
                summary = transcript.summary,
                archiveLines = countLines(File(dir, "transcript-archive.jsonl")),
                files = names.map { it to File(dir, it).let { f -> if (f.isFile) f.length() else -1L } },
            )
        }

        private fun countLines(file: File): Int =
            if (!file.isFile) 0 else file.useLines { lines -> lines.count { it.isNotBlank() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogScreen(dir: File, onClose: () -> Unit) {
    var reload by remember { mutableStateOf(0) }
    var snapshot by remember { mutableStateOf<Snapshot?>(null) }
    var tab by remember { mutableStateOf(0) }

    LaunchedEffect(reload) {
        snapshot = withContext(Dispatchers.IO) { Snapshot.read(dir) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("鸭子日志", fontSize = 18.sp) },
                actions = {
                    TextButton(onClick = { reload++ }) { Text("刷新") }
                    TextButton(onClick = onClose) { Text("关闭") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                TABS.forEachIndexed { index, title ->
                    Tab(
                        selected = tab == index,
                        onClick = { tab = index },
                        text = { Text(title) },
                    )
                }
            }
            val current = snapshot
            if (current == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                when (tab) {
                    0 -> ContextTab(current)
                    1 -> EventsTab(current, dir)
                    else -> TranscriptTab(current)
                }
            }
        }
    }
}

/** 这一轮会发出去的东西：`pack(transcript)` 的原样。 */
@Composable
private fun ContextTab(snapshot: Snapshot) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Hint(
                "这就是这一轮发给云端的全部内容：${snapshot.packed.size} 条消息。" +
                    "图片不上传（§8），上下文里只有文字。",
            )
        }
        items(snapshot.packed) { message ->
            Card(
                title = roleLabel(message["role"].orEmpty()),
                badgeColor = roleColor(message["role"].orEmpty()),
                body = message["content"].orEmpty(),
            )
        }
    }
}

/** 原始事件流，新的在上面。 */
@Composable
private fun EventsTab(snapshot: Snapshot, dir: File) {
    val rows = snapshot.episodes.reversed()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Hint("episodes.jsonl，新→旧，共 ${rows.size} 条（读盘上限 2000）。") }
        item {
            // 只留最新一帧（§4.1 第 4 条），所以单独放在最上面，不然要翻到很久以前那条才看得见图片
            if (File(dir, LATEST_FRAME).isFile) {
                Hint("最近留存的那一帧（$LATEST_FRAME，不上传）。按面板的「拍一张」更新它。")
                Frame(dir, LATEST_FRAME)
            }
        }
        items(rows) { episode ->
            Card(
                title = "${kindLabel(episode.kind)}  ${shortTime(episode.at)}",
                badgeColor = kindColor(episode.kind),
                body = episode.text,
                footer = episode.id,
            ) {
                if (episode.media.isNotEmpty()) {
                    Frame(dir, episode.media)
                }
            }
        }
    }
}

/** 轨迹：模型看到的对话史，以及被压缩掉的那部分。 */
@Composable
private fun TranscriptTab(snapshot: Snapshot) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Hint(
                "transcript.json：${snapshot.messages.size} 条消息" +
                    if (snapshot.summary.isBlank()) "，还没有摘要。"
                    else "，另有摘要 ${snapshot.summary.length} 字。",
            )
        }
        if (snapshot.summary.isNotBlank()) {
            item {
                Card(
                    title = "更早轨迹的摘要",
                    badgeColor = Color(0xFF6D7B8D),
                    body = snapshot.summary,
                )
            }
        }
        items(snapshot.messages) { message ->
            Card(
                title = buildString {
                    append(roleLabel(message.role))
                    if (message.name.isNotBlank()) append("  ·  ${message.name}")
                },
                badgeColor = roleColor(message.role),
                body = message.content,
            )
        }
        item {
            Hint(
                "归档（transcript-archive.jsonl）：${snapshot.archiveLines} 条，已经不在上下文里。\n" +
                    snapshot.files.joinToString("\n") { (name, size) ->
                        "$name  ${if (size < 0) "（没有）" else "$size 字节"}"
                    },
            )
        }
    }
}

// ---------- 小零件 ----------

private val TABS = listOf("上下文", "事件", "轨迹")

/** 唯一会落盘的帧（§4.1 第 4 条）。 */
private const val LATEST_FRAME = "seen/latest.jpg"

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = Color(0xFF9AA7B4),
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF141A21), RoundedCornerShape(8.dp))
            .padding(10.dp),
    )
}

@Composable
private fun Card(
    title: String,
    badgeColor: Color,
    body: String,
    footer: String = "",
    extra: @Composable (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A222B), RoundedCornerShape(10.dp))
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = badgeColor,
            )
        }
        Text(
            text = body,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            color = Color(0xFFE6EDF5),
            modifier = Modifier.padding(top = 6.dp),
        )
        if (footer.isNotEmpty()) {
            Text(
                text = footer,
                fontSize = 10.sp,
                color = Color(0xFF6D7B8D),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        extra?.invoke()
    }
}

/** 本地留存的帧（§4.1 第 4 条：`seen/latest.jpg`，不上传）。 */
@Composable
private fun Frame(dir: File, media: String) {
    val file = File(dir, media)
    if (!file.isFile) return
    val bitmap = remember(file.path, file.lastModified()) {
        runCatching { android.graphics.BitmapFactory.decodeFile(file.path) }.getOrNull()
    }
    val image = bitmap ?: return
    Column(Modifier.padding(top = 8.dp)) {
        Text(
            text = "$media  ${file.length()} 字节",
            fontSize = 10.sp,
            color = Color(0xFF6D7B8D),
        )
        Image(
            bitmap = image.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .padding(top = 4.dp)
                .fillMaxWidth()
                .aspectRatio(4f / 3f)
                .background(Color.Black, RoundedCornerShape(6.dp)),
        )
    }
}

private fun roleLabel(role: String): String = when (role) {
    "system" -> "系统提示词"
    "assistant" -> "模型回话"
    "tool" -> "工具结果"
    else -> "发给模型"
}

private fun roleColor(role: String): Color = when (role) {
    "system" -> Color(0xFF7F9CC4)
    "assistant" -> Color(0xFF7FD1A8)
    "tool" -> Color(0xFFD8B473)
    else -> Color(0xFF9AA7B4)
}

private fun kindLabel(kind: Kind): String = when (kind) {
    Kind.HEARD -> "听到"
    Kind.TEXT -> "打字"
    Kind.SEEN -> "看到"
    Kind.DID -> "做过"
}

private fun kindColor(kind: Kind): Color = when (kind) {
    Kind.HEARD -> Color(0xFF8FC7FF)
    Kind.TEXT -> Color(0xFF7FD1A8)
    Kind.SEEN -> Color(0xFFD8B473)
    Kind.DID -> Color(0xFFB8A0E8)
}

/** ISO-8601 → 只留时分秒。解析失败就原样返回，日志页不该因为一行时间崩掉。 */
private fun shortTime(iso: String): String = runCatching {
    val millis = java.time.OffsetDateTime.parse(iso, ISO_OFFSET).toInstant().toEpochMilli()
    SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(millis))
}.getOrDefault(iso)
