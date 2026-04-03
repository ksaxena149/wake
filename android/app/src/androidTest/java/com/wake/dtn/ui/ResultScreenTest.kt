package com.wake.dtn.ui

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wake.dtn.data.ReassembledBundle
import com.wake.dtn.service.RelayController
import com.wake.dtn.ui.theme.WakeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ResultScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val fakeRelay = object : RelayController {
        override fun start(context: Context) = Unit
        override fun stop(context: Context) = Unit
    }

    private val testDispatcher = StandardTestDispatcher()

    /** Real WebView created on the main thread; used by WakeWebViewClient tests. */
    private lateinit var stubView: WebView

    /** Build a ViewModel that captures the queryId sent to the server. */
    private fun makeViewModelWithCapture(onCapture: (String) -> Unit): MainViewModel {
        val sender = SearchRequestSender { _, queryId, _ -> onCapture(queryId) }
        return MainViewModel(fakeRelay, sender, testDispatcher)
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            stubView = WebView(ctx)
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun resultScreen_loading_showsProgressIndicator() {
        // fetchArticle sets Loading synchronously before the coroutine dispatches.
        val noOpSender = SearchRequestSender { _, _, _ -> }
        val vm = MainViewModel(fakeRelay, noOpSender, testDispatcher)
        vm.setNodeId("node-1")
        vm.fetchArticle("/A/Water")

        composeRule.setContent {
            WakeTheme { ResultScreen(vm) }
        }

        composeRule.onNodeWithTag(TAG_RESULT_LOADING).assertIsDisplayed()
    }

    @Test
    fun resultScreen_error_showsErrorMessage() {
        val throwingSender = SearchRequestSender { _, _, _ ->
            throw java.io.IOException("connection refused")
        }
        val vm = MainViewModel(fakeRelay, throwingSender, testDispatcher)
        vm.setNodeId("node-1")
        vm.fetchArticle("/A/Water")

        composeRule.setContent {
            WakeTheme { ResultScreen(vm) }
        }

        // Advance coroutine scheduler so the error propagates, then pump the compose clock.
        testDispatcher.scheduler.advanceUntilIdle()
        composeRule.mainClock.advanceTimeByFrame()

        composeRule.onNodeWithTag(TAG_RESULT_ERROR).assertIsDisplayed()
        composeRule.onNodeWithText("Error: connection refused", substring = true).assertIsDisplayed()
    }

    @Test
    fun resultScreen_loaded_showsWebView() {
        var capturedId: String? = null
        val vm = makeViewModelWithCapture { capturedId = it }
        vm.setNodeId("node-1")
        vm.fetchArticle("/A/Water")
        testDispatcher.scheduler.advanceUntilIdle()

        val html = "<html><body><h1>Water</h1></body></html>"
        vm.onBundleArrived(ReassembledBundle(capturedId!!, "text/html", html.toByteArray()))

        composeRule.setContent {
            WakeTheme { ResultScreen(vm) }
        }

        composeRule.onNodeWithTag(TAG_RESULT_WEBVIEW).assertIsDisplayed()
    }

    @Test
    fun resultScreen_navigateBack_returnsToSearch() {
        val noOpSender = SearchRequestSender { _, _, _ -> }
        val vm = MainViewModel(fakeRelay, noOpSender, testDispatcher)
        vm.setNodeId("node-1")
        vm.fetchArticle("/A/Water")

        composeRule.setContent {
            WakeTheme { ResultScreen(vm) }
        }

        vm.navigateBack()
        composeRule.mainClock.advanceTimeByFrame()

        assert(vm.currentScreen.value == WakeScreen.SEARCH)
        assert(vm.articleState.value is ArticleUiState.Idle)
    }

    // ---- WakeWebViewClient path filtering ----
    //
    // shouldOverrideUrlLoading never accesses `view`, so a real WebView created
    // in setUp suffices; it is only present to satisfy the non-null parameter type.

    private fun fakeRequest(url: String): WebResourceRequest {
        val uri = Uri.parse(url)
        return object : WebResourceRequest {
            override fun getUrl() = uri
            override fun isForMainFrame() = true
            override fun isRedirect() = false
            override fun hasGesture() = false
            override fun getMethod() = "GET"
            override fun getRequestHeaders() = emptyMap<String, String>()
        }
    }

    @Test
    fun webViewClient_articlePath_firesCallback() {
        var received: String? = null
        val client = WakeWebViewClient { received = it }
        client.shouldOverrideUrlLoading(stubView, fakeRequest("http://localhost/A/Water"))
        assertEquals("/A/Water", received)
    }

    @Test
    fun webViewClient_contentPath_firesCallback() {
        var received: String? = null
        val client = WakeWebViewClient { received = it }
        client.shouldOverrideUrlLoading(stubView, fakeRequest("http://localhost/content/images/logo.png"))
        assertEquals("/content/images/logo.png", received)
    }

    @Test
    fun webViewClient_midPathArticleSegment_doesNotFireCallback() {
        // Regression for Bug 2: "/wikipedia_en/A/Water" contains "/A/" but does NOT start with it.
        var received: String? = null
        val client = WakeWebViewClient { received = it }
        client.shouldOverrideUrlLoading(stubView, fakeRequest("http://localhost/wikipedia_en/A/Water"))
        assertNull(received)
    }

    @Test
    fun webViewClient_staticResource_doesNotFireCallback() {
        var received: String? = null
        val client = WakeWebViewClient { received = it }
        client.shouldOverrideUrlLoading(stubView, fakeRequest("http://localhost/static/style.css"))
        assertNull(received)
    }

    @Test
    fun webViewClient_alwaysBlocksNavigation() {
        val client = WakeWebViewClient { }
        // Returns true (override) for all URLs — WebView must never make direct requests.
        val blocked = client.shouldOverrideUrlLoading(stubView, fakeRequest("https://en.wikipedia.org/wiki/Water"))
        assert(blocked)
    }
}
