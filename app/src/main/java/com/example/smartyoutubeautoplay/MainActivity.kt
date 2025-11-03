package com.example.smartyoutubeautoplay

import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.ui.R as Media3UiR
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class MainActivity : AppCompatActivity() {

    private lateinit var player: ExoPlayer
    private lateinit var debugText: TextView
    private val logLines = mutableListOf<String>()

    @Volatile private var selectedBaseHost: String? = null

    private val history = mutableListOf<String>()
    private var historyIndex = -1

    private var overlayVisible = false
    private var currentStreamUrl: String? = null

    private val serverHosts = listOf(
        "192.168.15.99:3000",
        "192.168.1.150:3000",
        "pcmaria:3000",
        "pcfavela:3000",
        "steamdeck-1:3000",
        "pcrodrigoxeon:3000",
        "pcrodrigoxeon2:3000"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        debugText = findViewById(R.id.debugText)
        debugText.visibility = View.GONE

        val playerView = findViewById<PlayerView>(R.id.playerView)
        player = ExoPlayer.Builder(this).build()
        playerView.player = player
        playerView.setShowNextButton(true)
        playerView.setShowPreviousButton(true)
        setupNextPrevButtons(playerView)

        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { vis ->
                if (vis == View.VISIBLE) {
                    rewireControls(playerView)
                }
            }
        )

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    log("Fim do vídeo, carregando próximo…")
                    loadAndPlayNext()
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                log("Erro no player: ${error.message}")
                selectedBaseHost = null
                loadAndPlayNext()
            }

            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                rewireControls(playerView)
            }

            override fun onEvents(player: Player, events: Player.Events) {
                rewireControls(playerView)
            }
        })

        setupSettingsButton(playerView)
        enterImmersiveMode()
        loadAndPlayNext()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    private fun enterImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun log(msg: String) {
        logLines.add(msg)
        if (logLines.size > 30) logLines.removeAt(0)
        val text = logLines.joinToString("\n")
        runOnUiThread { if (overlayVisible) debugText.text = text }
        Log.d("SMART_DEBUG", msg)
    }

    private fun loadAndPlayNext() {
        CoroutineScope(Dispatchers.IO).launch {
            val preferred = selectedBaseHost
            if (preferred != null) {
                val next = fetchNext("$preferred/api/next")
                if (!next.isNullOrBlank()) {
                    resolveStream(preferred, next)?.let { stream ->
                        log("Usando host salvo: $preferred")
                        playVideo(stream, pushToHistory = true)
                        return@launch
                    }
                    log("Host salvo falhou, revarrendo hosts…")
                } else {
                    log("Host salvo sem próximo válido, revarrendo hosts…")
                }
                selectedBaseHost = null
            }

            val found = findWorkingHostAndStream()
            if (found != null) {
                val (base, stream) = found
                selectedBaseHost = base
                log("Host selecionado: $base")
                playVideo(stream, pushToHistory = true)
            } else {
                log("Nenhum servidor encontrado")
            }
        }
    }

    private fun handlePrevious() {
        if (player.currentPosition > 5_000) {
            log("Voltar ao início do vídeo")
            player.seekTo(0)
            return
        }
        if (historyIndex > 0) {
            historyIndex -= 1
            val prevUrl = history[historyIndex]
            log("Voltando ao vídeo anterior")
            playVideo(prevUrl, pushToHistory = false)
        } else {
            log("Sem vídeo anterior no histórico")
            player.seekTo(0)
        }
    }

    private fun findWorkingHostAndStream(): Pair<String, String>? {
        for (host in serverHosts) {
            val base = "http://$host"
            val nextUrl = "$base/api/next"
            log("Testando: $nextUrl")
            val nextFile = fetchNext(nextUrl)
            if (!nextFile.isNullOrBlank()) {
                resolveStream(base, nextFile)?.let { stream ->
                    log("✓ Servidor encontrado: $stream")
                    return base to stream
                }
            } else {
                log("✗ Falhou (sem próximo): $nextUrl")
            }
        }
        return null
    }

    private fun resolveStream(base: String, nextFile: String): String? {
        val encPath = Uri.encode(nextFile)
        val encQuery = URLEncoder.encode(nextFile, "UTF-8")
        val candidates = listOf(
            "$base/video/$encPath",
            "$base/$encPath",
            "$base/video?file=$encQuery"
        )
        for (stream in candidates) {
            if (urlExists(stream)) return stream else log("✗ Falhou: $stream")
        }
        return null
    }

    private fun urlExists(urlString: String): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlString).openConnection() as HttpURLConnection)
            conn.connectTimeout = 2000
            conn.readTimeout = 2000
            conn.requestMethod = "HEAD"
            conn.connect()
            conn.responseCode in 200..399
        } catch (e: Exception) {
            false
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun fetchNext(urlString: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlString).openConnection() as HttpURLConnection)
            conn.connectTimeout = 2000
            conn.readTimeout = 2000
            conn.requestMethod = "GET"
            conn.connect()
            if (conn.responseCode in 200..299) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                json.optString("file", null)
            } else null
        } catch (e: Exception) {
            null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun playVideo(url: String, pushToHistory: Boolean) {
        CoroutineScope(Dispatchers.Main).launch {
            log("▶ Reproduzindo: $url")
            if (pushToHistory) {
                if (historyIndex < history.lastIndex) {
                    while (history.size - 1 > historyIndex) history.removeAt(history.size - 1)
                }
                if (historyIndex == -1 || history[historyIndex] != url) {
                    history.add(url)
                    historyIndex = history.lastIndex
                }
            }
            currentStreamUrl = url
            val mediaItem = MediaItem.fromUri(url)
            player.setMediaItem(mediaItem)
            player.prepare()
            player.play()
        }
    }

    private fun setupNextPrevButtons(playerView: PlayerView) {
        val nextBtn = playerView.findViewById<View>(Media3UiR.id.exo_next)
        nextBtn?.let {
            it.visibility = View.VISIBLE
            it.isEnabled = true
            it.isClickable = true
            it.alpha = 1f
            it.setOnClickListener { loadAndPlayNext() }
        }
        val prevBtn = playerView.findViewById<View>(Media3UiR.id.exo_prev)
        prevBtn?.let {
            it.visibility = View.VISIBLE
            it.isEnabled = true
            it.isClickable = true
            it.alpha = 1f
            it.setOnClickListener { handlePrevious() }
        }
    }

    private fun enableNextPrevButtons(playerView: PlayerView) {
        playerView.findViewById<View>(Media3UiR.id.exo_next)?.let {
            it.visibility = View.VISIBLE
            it.isEnabled = true
            it.isClickable = true
            it.alpha = 1f
        }
        playerView.findViewById<View>(Media3UiR.id.exo_prev)?.let {
            it.visibility = View.VISIBLE
            it.isEnabled = true
            it.isClickable = true
            it.alpha = 1f
        }
    }

    private fun ensureBlacklistButton(playerView: PlayerView) {
        val tag = "blacklist_btn"
        val existing = playerView.findViewWithTag<View>(tag) as? ImageButton
        if (existing != null) {
            existing.visibility = View.VISIBLE
            existing.isEnabled = true
            return
        }
        val nextBtn = playerView.findViewById<View>(Media3UiR.id.exo_next)
        val container = (nextBtn?.parent as? ViewGroup)
            ?: playerView.findViewById<ViewGroup>(Media3UiR.id.exo_center_controls)
        container?.let { parent ->
            val btn = ImageButton(this)
            btn.tag = tag
            btn.setImageResource(android.R.drawable.ic_menu_delete)
            btn.setBackgroundColor(Color.TRANSPARENT)
            btn.setColorFilter(Color.WHITE)
            btn.contentDescription = "Não tocar este vídeo"
            val lp = if (nextBtn != null) {
                ViewGroup.LayoutParams(nextBtn.layoutParams.width, nextBtn.layoutParams.height)
            } else {
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            btn.layoutParams = lp
            btn.setOnClickListener { blacklistCurrentAndNext() }
            val insertIndex = if (nextBtn != null) parent.indexOfChild(nextBtn) + 1 else parent.childCount
            parent.addView(btn, insertIndex)
        }
    }

    private fun rewireControls(playerView: PlayerView) {
        playerView.setShowNextButton(true)
        playerView.setShowPreviousButton(true)
        setupNextPrevButtons(playerView)
        enableNextPrevButtons(playerView)
        ensureBlacklistButton(playerView)
    }

    private fun setupSettingsButton(playerView: PlayerView) {
        val settingsBtn = playerView.findViewById<View>(Media3UiR.id.exo_settings)
        settingsBtn?.setOnClickListener { showSettingsDialog() }
    }

    private fun showSettingsDialog() {
        val speeds = arrayOf("0.5x", "1.0x", "1.25x", "1.5x", "2.0x")
        val speedValues = floatArrayOf(0.5f, 1.0f, 1.25f, 1.5f, 2.0f)
        var selected = speedValues.indexOfFirst { it == player.playbackParameters.speed }
        if (selected == -1) selected = 1

        AlertDialog.Builder(this)
            .setTitle("Configurações")
            .setPositiveButton(if (overlayVisible) "Ocultar overlay" else "Mostrar overlay") { dlg, _ ->
                overlayVisible = !overlayVisible
                debugText.visibility = if (overlayVisible) View.VISIBLE else View.GONE
                if (overlayVisible) runOnUiThread { debugText.text = logLines.joinToString("\n") }
                dlg.dismiss()
            }
            .setSingleChoiceItems(speeds, selected) { _, which ->
                val sp = speedValues[which]
                player.setPlaybackSpeed(sp)
            }
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun extractFileFromStreamUrl(url: String): String? {
        return try {
            val uri = Uri.parse(url)
            val q = uri.getQueryParameter("file")
            if (!q.isNullOrEmpty()) Uri.decode(q) else Uri.decode(uri.lastPathSegment)
        } catch (e: Exception) { null }
    }

    private fun postDeleteVideo(base: String, file: String): Boolean {
        // Try JSON, then form-encoded, then GET as fallback
        return httpDeleteVideo(base, file, json = true) || httpDeleteVideo(base, file, json = false) || httpDeleteVideoGet(base, file)
    }

    private fun httpDeleteVideo(base: String, file: String, json: Boolean): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            val endpoint = if (base.endsWith("/")) base + "api/deleteVideo" else base + "/api/deleteVideo"
            conn = (URL(endpoint).openConnection() as HttpURLConnection)
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.requestMethod = "POST"
            conn.doOutput = true
            if (json) {
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                val payload = "{" + "\"file\":\"" + file.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}"
                conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            } else {
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                val payload = "file=" + URLEncoder.encode(file, "UTF-8")
                conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            Log.d("SMART_DEBUG", "deleteVideo POST(" + (if (json) "json" else "form") + ") => " + code)
            code in 200..299
        } catch (e: Exception) {
            false
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun httpDeleteVideoGet(base: String, file: String): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            val endpoint = (if (base.endsWith("/")) base + "api/deleteVideo" else base + "/api/deleteVideo") + "?file=" + URLEncoder.encode(file, "UTF-8")
            conn = (URL(endpoint).openConnection() as HttpURLConnection)
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.requestMethod = "GET"
            val code = conn.responseCode
            Log.d("SMART_DEBUG", "deleteVideo GET => " + code)
            code in 200..299
        } catch (e: Exception) {
            false
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun blacklistCurrentAndNext() {
        CoroutineScope(Dispatchers.IO).launch {
            val url = currentStreamUrl
            if (url.isNullOrEmpty()) {
                log("✗ Sem URL atual para blacklist")
                return@launch
            }
            val file = extractFileFromStreamUrl(url)
            if (file.isNullOrEmpty()) {
                log("✗ Não foi possível extrair arquivo da URL atual")
                return@launch
            }
            var base = selectedBaseHost
            if (base.isNullOrEmpty()) {
                try {
                    val u = Uri.parse(url)
                    base = "${u.scheme}://${u.authority}"
                } catch (_: Exception) {}
            }
            if (!base.isNullOrEmpty()) {
                log("⛔ Blacklist: $file")
                val ok = postDeleteVideo(base!!, file)
                log(if (ok) "🗑️ Deletado no servidor" else "⚠️ Falha ao deletar no servidor")
            } else {
                log("✗ Sem host para enviar blacklist")
            }
            CoroutineScope(Dispatchers.Main).launch { try { player.stop() } catch (_: Exception) {} }
            loadAndPlayNext()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        player.release()
    }
}




