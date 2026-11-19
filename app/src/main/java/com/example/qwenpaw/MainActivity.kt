package com.example.qwenpaw

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.jeziellago.compose.markdowntext.MarkdownText
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONObject

data class ChatMessage(val id: String, val text: String, val isUser: Boolean)

class QwenPawClient(private val baseUrl: String, private val token: String) {
    private val client = OkHttpClient()

    fun streamChat(prompt: String): Flow<String> = callbackFlow {
        val json = JSONObject().apply {
            put("session_id", "android-mobile")
            put("message", prompt)
            put("stream", true)
        }
        val req = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/chat/stream")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .apply { if (token.isNotBlank()) addHeader("Authorization", "Bearer $token") }
            .build()

        val listener = object : EventSourceListener() {
            override fun onEvent(es: EventSource, id: String?, type: String?, data: String) {
                if (data == "[DONE]") { close(); return }
                try {
                    val delta = JSONObject(data).optString("content", "")
                    if (delta.isNotEmpty()) trySend(delta)
                } catch (_: Exception) { trySend(data) }
            }
            override fun onFailure(es: EventSource, t: Throwable?, r: Response?) {
                close(t ?: RuntimeException("HTTP ${r?.code}"))
            }
            override fun onClosed(es: EventSource) { close() }
        }
        val source = EventSources.createFactory(client).newEventSource(req, listener)
        awaitClose { source.cancel() }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { AppScreen() } } }
    }
}

@Composable
fun AppScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("qwenpaw", Context.MODE_PRIVATE) }
    var serverUrl by remember { mutableStateOf(prefs.getString("url", "http://192.168.1.50:8080") ?: "") }
    var apiToken by remember { mutableStateOf(prefs.getString("token", "") ?: "") }
    var showSettings by remember { mutableStateOf(false) }

    val messages = remember { mutableStateListOf<ChatMessage>() }
    var input by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("Настройки сервера QwenPaw") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = serverUrl, onValueChange = { serverUrl = it }, label = { Text("URL сервера") })
                    OutlinedTextField(value = apiToken, onValueChange = { apiToken = it }, label = { Text("API Token (если есть)") })
                }
            },
            confirmButton = {
                Button(onClick = {
                    prefs.edit().putString("url", serverUrl).putString("token", apiToken).apply()
                    showSettings = false
                }) { Text("Сохранить") }
            }
        )
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("QwenPaw Client", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { showSettings = true }) { Text("Настройки") }
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(messages, key = { it.id }) { msg ->
                Box(Modifier.fillMaxWidth(), contentAlignment = if (msg.isUser) Alignment.CenterEnd else Alignment.CenterStart) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (msg.isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.widthIn(max = 310.dp)
                    ) {
                        Box(Modifier.padding(12.dp)) {
                            if (msg.isUser) Text(msg.text)
                            else MarkdownText(markdown = msg.text.ifEmpty { "Думаю..." })
                        }
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f), placeholder = { Text("Запрос...") })
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                if (input.isBlank()) return@Button
                val prompt = input
                input = ""
                messages.add(ChatMessage(System.nanoTime().toString(), prompt, true))
                val botId = (System.nanoTime() + 1).toString()
                messages.add(ChatMessage(botId, "", false))

                scope.launch {
                    try {
                        QwenPawClient(serverUrl, apiToken).streamChat(prompt).collect { chunk ->
                            val idx = messages.indexOfFirst { it.id == botId }
                            if (idx != -1) messages[idx] = messages[idx].copy(text = messages[idx].text + chunk)
                        }
                    } catch (e: Exception) {
                        val idx = messages.indexOfFirst { it.id == botId }
                        if (idx != -1) messages[idx] = messages[idx].copy(text = "Ошибка: ${e.message}")
                    }
                }
            }) { Text("Отпр.") }
        }
    }
}
