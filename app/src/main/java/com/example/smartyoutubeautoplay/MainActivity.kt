package com.example.smartyoutubeautoplay

import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.example.smartyoutubeautoplay.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // FULLSCREEN imersivo em todas as versões (agora na ordem certa)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }

        val webView = binding.webView
        val retryButton = binding.retryButton

        webView.settings.javaScriptEnabled = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                webView.loadUrl("about:blank")
                retryButton.isEnabled = true
                retryButton.visibility = View.VISIBLE
            }
        }

        // Substitua pelo IP do seu servidor local
        val serverUrl = "http://pcrodrigoxeon:3000"

        retryButton.setOnClickListener {
            webView.loadUrl(serverUrl)
            retryButton.isEnabled = false
            retryButton.visibility = View.GONE
        }

        webView.loadUrl(serverUrl)
    }

    override fun onBackPressed() {
        val webView = binding.webView
        if (webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }
}
