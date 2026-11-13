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
    private val context: android.content.Context,
    private val token: String,
    private val stateProvider: () -> String,
    private val pointHandler: (team: Int, add: Boolean) -> Unit,
    private val setDecisionHandler: (newSet: Boolean) -> Unit,
    private val matchDecisionHandler: (again: Boolean) -> Unit,
    private val sessionStartHandler: () -> Unit,
    private val sessionStopHandler: () -> Unit
) {

    private var serverSocket: ServerSocket? = null

    @Volatile
    private var running = false

    fun start() {
        if (running) return
        running = true
        thread(name = "remote-web-server", isDaemon = true) {
            try {
                // HTTPS обязателен: только на защищённом соединении браузер разрешает
                // удерживать экран включённым (Screen Wake Lock API).
                val socket = createServerSocket()
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

    /** Серверный сокет: HTTPS с самоподписанным сертификатом, при сбое — обычный HTTP. */
    private fun createServerSocket(): ServerSocket = try {
        val keyStore = java.security.KeyStore.getInstance("PKCS12")
        context.resources.openRawResource(R.raw.remote_p12).use { input ->
            keyStore.load(input, KEYSTORE_PASSWORD.toCharArray())
        }
        val keyManagers = javax.net.ssl.KeyManagerFactory.getInstance(
            javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm()
        )
        keyManagers.init(keyStore, KEYSTORE_PASSWORD.toCharArray())
        val sslContext = javax.net.ssl.SSLContext.getInstance("TLS")
        sslContext.init(keyManagers.keyManagers, null, null)
        sslContext.serverSocketFactory.createServerSocket(PORT)
    } catch (e: Exception) {
        Log.e(TAG, "HTTPS не поднялся, работаю по HTTP", e)
        ServerSocket(PORT)
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

                method == "POST" && path == "/session-start" -> {
                    sessionStartHandler()
                    respond(client, 200, "application/json; charset=utf-8", "{\"ok\":true}")
                }

                method == "POST" && path == "/session-stop" -> {
                    sessionStopHandler()
                    respond(client, 200, "application/json; charset=utf-8", "{\"ok\":true}")
                }

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

        /** Пароль встроенного хранилища с сертификатом. */
        private const val KEYSTORE_PASSWORD = "sportstreamer"

        /** Страница пульта: две команды, счёт и кнопки. Всё локально, без внешних скриптов. */
        private val PAGE = """
<!doctype html>
<html lang="ru">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="color-scheme" content="light">
<title>SportStreamer — пульт</title>
<style>
  :root { color-scheme: light; }
  body { margin:0; font-family: system-ui, sans-serif; background:#f2f0f7; color:#1c1b1f; }
  header { padding:16px; text-align:center; font-size:20px; color:#6750a4; }
  #score { text-align:center; font-size:22px; padding:8px 0 4px; }
  #bitrate { text-align:center; font-size:16px; color:#555; padding:0 0 4px; }
  #wake { text-align:center; font-size:14px; color:#777; padding:0 0 12px; }
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
<div id="bitrate">битрейт: —</div>
<div id="wake"></div>
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
<div id="session"></div>
<div id="decision"></div>
<div id="status">—</div>
<style>
  #session, #decision { padding:0 16px 16px; }
  #session button { background:#1b5e20; }
  #decision button { margin-bottom:8px; }
</style>
<script>
const t = new URLSearchParams(location.search).get('t') || '';
async function post(path) {
  try {
    // У путей может не быть '?': тогда код добавляется через '?', иначе через '&'.
    const sep = path.indexOf('?') === -1 ? '?' : '&';
    await fetch(path + sep + 't=' + encodeURIComponent(t), {method:'POST'});
    await tick();
  } catch (e) { el('status').textContent = 'нет связи с источником'; }
}
function sessionButton() {
  if (!s.sessionActive) {
    return '<button onclick="post(\'/session-start\')">Начать</button>';
  }
  return '<button onclick="if (confirm(\'Остановить трансляцию и запись?\')) post(\'/session-stop\')">Остановить</button>';
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
// Колонки партий как на оверлее: завершённые + текущая (после конца матча её нет).
function renderSets(s) {
  const pad = n => String(n).padStart(2, '0');
  const cols = (s.setHistory || '').trim().split(/\s+/).filter(Boolean);
  if (!s.matchOver) {
    const live = s.awaitingNextSet ? '00:00' : pad(s.t1) + ':' + pad(s.t2);
    cols.push(live);
  }
  el('sets').innerHTML = cols
    .map(c => '<span class="col">' + c.replace(':', '<br>') + '</span>')
    .join('');
}

async function tick() {
  try {
    const r = await fetch('/state?t=' + encodeURIComponent(t), {cache:'no-store'});
    const s = await r.json();
    window.s = s;
    el('bitrate').textContent = s.bitrateKbps > 0
        ? 'битрейт: ' + (s.bitrateKbps / 1000).toFixed(2) + ' Мбит/с'
        : 'битрейт: —';
    el('n1').textContent = s.team1Name || 'Команда 1';
    el('n2').textContent = s.team2Name || 'Команда 2';
    el('score').textContent = 'Партии ' + s.s1 + ':' + s.s2 +
        ' · счёт ' + s.t1 + ':' + s.t2 + ' · партия ' + s.game + ' из ' + (s.setsToWin*2-1);
    el('session').innerHTML = sessionButton();

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
// Экран не должен гаснуть: держим wake lock, пока страница открыта.
let wakeLock = null;
async function keepAwake() {
  try {
    if (!('wakeLock' in navigator)) {
      el('wake').textContent = 'браузер не умеет удерживать экран';
      return;
    }
    wakeLock = await navigator.wakeLock.request('screen');
    el('wake').textContent = 'экран не гаснет';
    wakeLock.addEventListener('release', () => {
      el('wake').textContent = 'удержание экрана снято';
      wakeLock = null;
    });
  } catch (e) {
    el('wake').textContent = 'не удалось удержать экран: ' + e.message;
  }
}
document.addEventListener('pointerdown', keepAwake);
document.addEventListener('visibilitychange', () => {
  if (document.visibilityState === 'visible' && !wakeLock) keepAwake();
});
keepAwake();

setInterval(tick, 1000);
tick();
</script>
</body>
</html>
"""
    }
}
