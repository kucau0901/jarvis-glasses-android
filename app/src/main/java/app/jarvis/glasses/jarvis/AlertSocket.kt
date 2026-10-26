package app.jarvis.glasses.jarvis

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Jarvis speaking first: the `/api/v1/events` WebSocket (docs/api.md, "Receiving them on a
 * socket"). An alert counts as delivered here only when this app acknowledges it as *visible*
 * within four seconds; otherwise Jarvis goes on to its other ways. So [onAlert] answers whether
 * someone will get it (said in the glasses, or shown as a notification), and only then is it
 * acknowledged.
 *
 * Reconnects with backoff while [start]ed; close code 4001 means the token was revoked, which stops
 * it for good.
 */
class AlertSocket(
    private val client: JarvisClient,
    private val scope: CoroutineScope,
    private val onAlert: (Alert) -> Boolean,
) {
  enum class State { OFF, CONNECTING, CONNECTED, REVOKED }

  private val _state = MutableStateFlow(State.OFF)
  val state: StateFlow<State> = _state

  private var job: Job? = null
  @Volatile private var socket: WebSocket? = null
  @Volatile private var visible = false

  fun start() {
    if (job?.isActive == true) return
    job =
        scope.launch {
          var backoff = 1_000L
          while (isActive && _state.value != State.REVOKED) {
            val closed = CompletableDeferred<Int>()
            _state.value = State.CONNECTING
            val req = client.request("/api/v1/events?label=glasses").build()
            val ws =
                client.http.newWebSocket(
                    req,
                    object : WebSocketListener() {
                      override fun onOpen(webSocket: WebSocket, response: Response) {
                        _state.value = State.CONNECTED
                        backoff = 1_000L
                        webSocket.send(presence(visible))
                      }

                      override fun onMessage(webSocket: WebSocket, text: String) {
                        if (text == "pong") return
                        val o = runCatching { SseParser.json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                        val alert = Alert.fromMessage(o) ?: return
                        if (onAlert(alert)) webSocket.send(ack(alert.id))
                      }

                      override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(code, null)
                        closed.complete(code)
                      }

                      override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        closed.complete(-1)
                      }
                    },
                )
            socket = ws
            val pinger = launch {
              while (isActive) {
                delay(25_000)
                ws.send("ping")
              }
            }
            val code = closed.await()
            pinger.cancel()
            socket = null
            if (code == 4001) {
              _state.value = State.REVOKED
              break
            }
            _state.value = State.CONNECTING
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(60_000L)
          }
          if (_state.value != State.REVOKED) _state.value = State.OFF
        }
  }

  fun stop() {
    job?.cancel()
    job = null
    socket?.close(1000, null)
    socket = null
    _state.value = State.OFF
  }

  /** Reconnects with the current address and credential. */
  fun restart() {
    stop()
    start()
  }

  /** Whether this app can put an alert in front of someone, told to Jarvis as the screen's "presence". */
  fun setVisible(on: Boolean) {
    if (visible == on) return
    visible = on
    socket?.send(presence(on))
  }

  companion object {
    fun presence(visible: Boolean) = buildJsonObject { put("type", "presence"); put("visible", visible) }.toString()

    fun ack(id: String) = buildJsonObject { put("type", "ack"); put("id", id); put("visible", true) }.toString()
  }
}
