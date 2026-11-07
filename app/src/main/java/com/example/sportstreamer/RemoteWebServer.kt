package com.example.sportstreamer

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

/**
 * HTTP-сервер удалённого пульта: отдаёт страницу в браузере второго телефона.
 *
 * Второму устройству не нужно приложение — достаточно камеры и браузера. Страница
 * опрашивает состояние и шлёт команды обычными HTTP-запросами, поэтому весь обмен
 * идёт поверх локальной сети, без интернета.
 *
 * Доступ защищён токеном в URL: без него страница и данные не отдаются.
 */
class RemoteWebServer(
    private val token: String,
    private val stateProvider: () -> String,
    private val pointHandler: (team: Int, add: Boolean) -> Unit,
    private val setDecisionHandler: (newSet: Boolean) -> Unit,
    private val matchDecisionHandler: (again: Boolean) -> Unit
) {

    private var serverSocket: ServerSocket? = null

    @Volatile
    private var running = false

    fun start() {
        if (running) return
        running = true
        thread(name = "remote-web-server", isDaemon = true) {
            try {
                val socket = ServerSocket(PORT)
                serverSocket = socket
                Log.i(TAG, "Веб-пульт слушает порт $PORT")
                while (running) {
                    val client = socket.accept()
                    thread(name = "remote-web-client", isDaemon = true) {
                        handle(client)
                    }
                }
            } catch (e: Exception) {
                if (running) Log.e(TAG, "Веб-пульт остановлен с ошибкой", e)
            }
        }
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = SOCKET_TIMEOUT_MS
            val reader = BufferedReader(InputStreamReader(client.inputStream))
            val requestLine = reader.readLine() ?: return
            // Пропускаем заголовки: тело запросов не используется.
            while (true) {
                val header = reader.readLine() ?: break
                if (header.isEmpty()) break
            }

            val parts = requestLine.split(" ")
            if (parts.size < 2) {
                respond(client, 400, "text/plain", "bad request")
                return
            }
            val method = parts[0]
            val target = parts[1]
            val path = target.substringBefore('?')
            val query = parseQuery(target.substringAfter('?', ""))

            if (query["t"] != token) {
                respond(client, 403, "text/plain", "forbidden")
                return
            }

            when {
                method == "GET" && path == "/" ->
                    respond(client, 200, "text/html; charset=utf-8", PAGE)

                method == "GET" && path == "/state" ->
                    respond(client, 200, "application/json; charset=utf-8", stateProvider())

                method == "POST" && path == "/set-decision" -> {
                    setDecisionHandler(query["newSet"] == "1" || query["newSet"] == "true")
                    respond(client, 200, "application/json; charset=utf-8", "{\"ok\":true}")
                }

                method == "POST" && path == "/match-decision" -> {
                    matchDecisionHandler(query["again"] == "1" || query["again"] == "true")
                    respond(client, 200, "application/json; charset=utf-8", "{\"ok\":true}")
                }

                method == "POST" && path == "/point" -> {
                    val team = query["team"]?.toIntOrNull() ?: 0
                    val add = query["add"] == "1" || query["add"] == "true"
                    if (team == 1 || team == 2) pointHandler(team, add)
                    respond(client, 200, "application/json; charset=utf-8", "{\"ok\":true}")
                }

                else -> respond(client, 404, "text/plain", "not found")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка обслуживания веб-пульта", e)
        } finally {
            try {
                client.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun respond(client: Socket, code: Int, contentType: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val output: OutputStream = client.getOutputStream()
        val header = buildString {
            append("HTTP/1.1 $code ${if (code == 200) "OK" else "ERR"}\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(header.toByteArray(Charsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }

    private fun parseQuery(raw: String): Map<String, String> =
        raw.split('&').filter { it.contains('=') }.associate {
            val key = it.substringBefore('=')
            val value = URLDecoder.decode(it.substringAfter('='), "UTF-8")
            key to value
        }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка остановки веб-пульта", e)
        }
        serverSocket = null
    }

    companion object {
        /** Порт страницы пульта: по нему второй телефон открывает её в браузере. */
        const val PORT = 8643
        private const val TAG = "RemoteWebServer"
        private const val SOCKET_TIMEOUT_MS = 10_000

        /** Страница пульта: две команды, счёт и кнопки. Всё локально, без внешних скриптов. */
        private val PAGE = """
<!doctype html>
<html lang="ru">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>SportStreamer — пульт</title>
<style>
  :root { color-scheme: light dark; }
  body { margin:0; font-family: system-ui, sans-serif; background:#f2f0f7; color:#1c1b1f; }
  header { padding:16px; text-align:center; font-size:20px; color:#6750a4; }
  #score { text-align:center; font-size:22px; padding:8px 0 16px; }
  .teams { display:flex; gap:12px; padding:0 16px 24px; }
  .team { flex:1; background:#fff; border-radius:20px; padding:16px; text-align:center;
          box-shadow:0 2px 8px rgba(0,0,0,.08); }
  .name { font-size:18px; font-weight:600; margin-bottom:12px; min-height:24px; }
  button { width:100%; padding:22px 0; font-size:26px; border:0; border-radius:16px;
           margin-bottom:10px; background:#6750a4; color:#fff; }
  button.minus { background:#e8e0f0; color:#1c1b1f; }
  #status { text-align:center; padding:0 16px 24px; color:#666; font-size:14px; }
</style>
</head>
<body>
<header>SportStreamer — пульт</header>
<div id="score">загрузка…</div>
<div class="teams">
  <div class="team">
    <div class="name" id="n1">Команда 1</div>
    <button onclick="point(1,1)">+1</button>
    <button class="minus" onclick="point(1,0)">−1</button>
  </div>
  <div class="team">
    <div class="name" id="n2">Команда 2</div>
    <button onclick="point(2,1)">+1</button>
    <button class="minus" onclick="point(2,0)">−1</button>
  </div>
</div>
<div id="decision"></div>
<div id="status">—</div>
<style>
  #decision { padding:0 16px 16px; }
  #decision button { margin-bottom:8px; }
</style>
<script>
const t = new URLSearchParams(location.search).get('t') || '';
async function post(path) {
  try {
    await fetch(path + '&t=' + encodeURIComponent(t), {method:'POST'});
    await tick();
  } catch (e) { el('status').textContent = 'нет связи с источником'; }
}
function decisionButton(label, path) {
  return '<button onclick="post(\'' + path + '\')">' + label + '</button>';
}
const el = id => document.getElementById(id);
async function point(team, add) {
  try {
    await fetch('/point?t=' + encodeURIComponent(t) + '&team=' + team + '&add=' + add, {method:'POST'});
    await tick();
  } catch (e) { el('status').textContent = 'нет связи с источником'; }
}
async function tick() {
  try {
    const r = await fetch('/state?t=' + encodeURIComponent(t), {cache:'no-store'});
    const s = await r.json();
    el('n1').textContent = s.team1Name || 'Команда 1';
    el('n2').textContent = s.team2Name || 'Команда 2';
    el('score').textContent = 'Партии ' + s.s1 + ':' + s.s2 +
        ' · счёт ' + s.t1 + ':' + s.t2 + ' · партия ' + s.game + ' из ' + (s.setsToWin*2-1);
    const d = el('decision');
    if (s.setPending) {
      d.innerHTML = decisionButton('Новая партия', '/set-decision?newSet=1') +
                    decisionButton('Продолжить', '/set-decision?newSet=0');
      el('status').textContent = 'партия завершена — решите здесь или на телефоне';
    } else if (s.matchPending) {
      d.innerHTML = decisionButton('Новая игра', '/match-decision?again=1') +
                    decisionButton('Закрыть', '/match-decision?again=0');
      el('status').textContent = 'матч завершён — решите здесь или на телефоне';
    } else {
      d.innerHTML = '';
      el('status').textContent = s.matchOver ? 'матч завершён' : 'связь есть';
    }
  } catch (e) { el('status').textContent = 'нет связи с источником'; }
}
setInterval(tick, 1000);
tick();
</script>
</body>
</html>
"""
    }
}
