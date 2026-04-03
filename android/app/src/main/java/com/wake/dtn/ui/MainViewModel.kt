package com.wake.dtn.ui

import android.content.Context
import android.text.Html
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wake.dtn.data.ReassembledBundle
import com.wake.dtn.service.RelayController
import com.wake.dtn.service.WakeHttpClient
import com.wake.dtn.service.WakeService
import com.wake.dtn.service.WakeServiceController
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

// ---- Screen navigation ----

enum class WakeScreen { SEARCH, STATUS, RESULT }

// ---- Article UI state ----

sealed class ArticleUiState {
    object Idle : ArticleUiState()
    object Loading : ArticleUiState()
    data class Loaded(val html: String) : ArticleUiState()
    data class Error(val message: String) : ArticleUiState()
}

// ---- Search UI state ----

data class SearchResult(val title: String, val path: String)

sealed class SearchUiState {
    object Idle : SearchUiState()
    object Loading : SearchUiState()
    data class Results(val items: List<SearchResult>) : SearchUiState()
    data class Error(val message: String) : SearchUiState()
}

// ---- Abstraction over HTTP search submission, injectable for tests ----

fun interface SearchRequestSender {
    suspend fun send(nodeId: String, queryId: String, queryString: String)
}

class MainViewModel(
    private val relay: RelayController = WakeServiceController(),
    private val searchSender: SearchRequestSender = SearchRequestSender { nodeId, queryId, query ->
        WakeHttpClient(WakeService.SERVER_BASE_URL).submitRequest(nodeId, queryId, query)
    },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _currentScreen = MutableStateFlow(WakeScreen.SEARCH)
    val currentScreen: StateFlow<WakeScreen> = _currentScreen.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchState = MutableStateFlow<SearchUiState>(SearchUiState.Idle)
    val searchState: StateFlow<SearchUiState> = _searchState.asStateFlow()

    private val _storageUsedBytes = MutableStateFlow(0L)
    val storageUsedBytes: StateFlow<Long> = _storageUsedBytes.asStateFlow()

    private val _lastSyncTimeMs = MutableStateFlow<Long?>(null)
    val lastSyncTimeMs: StateFlow<Long?> = _lastSyncTimeMs.asStateFlow()

    private val _articleState = MutableStateFlow<ArticleUiState>(ArticleUiState.Idle)
    val articleState: StateFlow<ArticleUiState> = _articleState.asStateFlow()

    /** The node ID provided by the bound WakeService. Null when service is not connected. */
    private var nodeId: String? = null

    /** The query ID of the most recently submitted search or article fetch, awaiting a response bundle. */
    private var pendingQueryId: String? = null

    private enum class PendingQueryType { SEARCH, ARTICLE }
    private var pendingQueryType: PendingQueryType = PendingQueryType.SEARCH

    fun startRelay(context: Context) {
        relay.start(context)
        _isRunning.value = true
    }

    fun stopRelay(context: Context) {
        relay.stop(context)
        _isRunning.value = false
    }

    fun navigateTo(screen: WakeScreen) {
        _currentScreen.value = screen
    }

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
    }

    /** Called by MainActivity when the service connection is established or lost. */
    fun setNodeId(id: String?) {
        nodeId = id
    }

    /**
     * Submit the current search query to the WAKE server.
     * No-op if the query is blank or the service is not yet connected (nodeId == null).
     */
    fun submitSearch() {
        val query = _searchQuery.value.trim()
        val id = nodeId ?: return
        if (query.isBlank()) return

        val queryId = UUID.randomUUID().toString()
        pendingQueryId = queryId
        pendingQueryType = PendingQueryType.SEARCH
        _searchState.value = SearchUiState.Loading

        viewModelScope.launch(ioDispatcher) {
            try {
                searchSender.send(id, queryId, query)
            } catch (e: Exception) {
                _searchState.value = SearchUiState.Error(e.message ?: "Network error")
                pendingQueryId = null
            }
        }
    }

    /**
     * Fetch an article from the WAKE server by its kiwix path (e.g. "/A/Water").
     * Navigates to [WakeScreen.RESULT] immediately and shows a loading indicator until
     * the reassembled bundle arrives.
     * No-op if the service is not yet connected (nodeId == null).
     */
    fun fetchArticle(path: String) {
        val id = nodeId ?: return
        val queryId = UUID.randomUUID().toString()
        pendingQueryId = queryId
        pendingQueryType = PendingQueryType.ARTICLE
        _articleState.value = ArticleUiState.Loading
        _currentScreen.value = WakeScreen.RESULT

        viewModelScope.launch(ioDispatcher) {
            try {
                searchSender.send(id, queryId, path)
            } catch (e: Exception) {
                _articleState.value = ArticleUiState.Error(e.message ?: "Network error")
                pendingQueryId = null
            }
        }
    }

    /** Navigate back to the Search screen and reset article state. */
    fun navigateBack() {
        _currentScreen.value = WakeScreen.SEARCH
        _articleState.value = ArticleUiState.Idle
        pendingQueryId = null
    }

    /**
     * Called by MainActivity when WakeService emits a [ReassembledBundle].
     * Ignored if the bundle's queryId does not match [pendingQueryId].
     */
    fun onBundleArrived(bundle: ReassembledBundle) {
        if (bundle.queryId != pendingQueryId) {
            Log.d(
                TAG,
                "Ignoring bundle for queryId=${bundle.queryId}; waitingFor=$pendingQueryId",
            )
            return
        }

        val html = bundle.bytes.decodeToString()
        Log.i(
            TAG,
            "Bundle received queryId=${bundle.queryId} type=$pendingQueryType contentType=${bundle.contentType} bytes=${bundle.bytes.size}",
        )

        when (pendingQueryType) {
            PendingQueryType.SEARCH -> {
                val results = parseSearchResults(html)
                if (results.isEmpty()) {
                    val preview = html.replace("\n", " ").replace("\r", " ").take(180)
                    Log.w(TAG, "Parsed zero results for queryId=${bundle.queryId}. HTML preview=$preview")
                }
                _searchState.value = SearchUiState.Results(results)
            }
            PendingQueryType.ARTICLE -> {
                _articleState.value = ArticleUiState.Loaded(html)
            }
        }
        pendingQueryId = null
    }

    /** Called by MainActivity when the service reports a successful sync. */
    fun onSyncTimeUpdated(ms: Long) {
        _lastSyncTimeMs.value = ms
    }

    /** Called by MainActivity when the service reports the current bundle store size. */
    fun onStorageUpdated(bytes: Long) {
        _storageUsedBytes.value = bytes
    }

    companion object {
        private const val TAG = "MainViewModel"

        /** Extract article links of the form /A/... from kiwix search result HTML. */
        internal fun parseSearchResults(html: String): List<SearchResult> {
            val anchorRegex = Regex(
                pattern = """<a\b[^>]*\bhref\s*=\s*(['\"])([^'\"]+)\1[^>]*>(.*?)</a>""",
                options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
            )

            return anchorRegex
                .findAll(html)
                .mapNotNull { match ->
                    val href = match.groupValues[2].trim()
                    val isArticlePath = href.contains("/A/") || href.startsWith("/content/")
                    if (!isArticlePath) {
                        return@mapNotNull null
                    }

                    val title = Html.fromHtml(
                        match.groupValues[3],
                        Html.FROM_HTML_MODE_LEGACY,
                    ).toString().trim()

                    if (title.isEmpty()) null else SearchResult(title = title, path = href)
                }
                .toList()
        }
    }
}
