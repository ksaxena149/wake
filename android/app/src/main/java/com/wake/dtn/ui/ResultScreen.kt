package com.wake.dtn.ui

import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

const val TAG_RESULT_WEBVIEW = "result_webview"
const val TAG_RESULT_LOADING = "result_loading"
const val TAG_RESULT_ERROR = "result_error"

@Composable
fun ResultScreen(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val articleState by viewModel.articleState.collectAsState()

    BackHandler { viewModel.navigateBack() }

    when (val state = articleState) {
        is ArticleUiState.Idle -> Unit
        is ArticleUiState.Loading -> {
            Box(
                modifier = modifier.fillMaxSize().testTag(TAG_RESULT_LOADING),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }
        is ArticleUiState.Loaded -> {
            val html = state.html
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        webViewClient = WakeWebViewClient { path -> viewModel.fetchArticle(path) }
                        settings.javaScriptEnabled = false
                    }
                },
                update = { webView ->
                    webView.loadDataWithBaseURL(
                        "http://localhost/",
                        html,
                        "text/html",
                        "UTF-8",
                        null,
                    )
                },
                modifier = modifier.fillMaxSize().testTag(TAG_RESULT_WEBVIEW),
            )
        }
        is ArticleUiState.Error -> {
            Box(
                modifier = modifier.fillMaxSize().padding(16.dp).testTag(TAG_RESULT_ERROR),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Error: ${state.message}",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/**
 * Intercepts navigation events inside the article WebView.
 * Article links (/A/... or /content/...) are routed back through WAKE.
 * All other navigations are blocked — the WebView never makes direct internet requests.
 */
internal class WakeWebViewClient(
    private val onArticleLinkClicked: (String) -> Unit,
) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val path = request.url.path ?: return true
        if (path.startsWith("/A/") || path.startsWith("/content/")) {
            onArticleLinkClicked(path)
        }
        return true
    }
}
