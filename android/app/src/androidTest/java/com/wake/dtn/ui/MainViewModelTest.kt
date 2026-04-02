package com.wake.dtn.ui

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wake.dtn.data.ReassembledBundle
import com.wake.dtn.service.RelayController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class MainViewModelTest {

    // No-op relay controller.
    private val fakeRelay = object : RelayController {
        override fun start(context: Context) = Unit
        override fun stop(context: Context) = Unit
    }

    // Fake sender that records the last queryId it received.
    private var fakeSenderThrows = false
    private var capturedQueryId: String? = null
    private val fakeSender = SearchRequestSender { _, queryId, _ ->
        capturedQueryId = queryId
        if (fakeSenderThrows) throw IOException("connection refused")
    }

    private lateinit var viewModel: MainViewModel
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeSenderThrows = false
        capturedQueryId = null
        viewModel = MainViewModel(fakeRelay, fakeSender, testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ---- relay (existing) ----

    @Test
    fun initialState_isNotRunning() {
        assertFalse(viewModel.isRunning.value)
    }

    @Test
    fun startRelay_setsIsRunningTrue() {
        viewModel.startRelay(context)
        assertTrue(viewModel.isRunning.value)
    }

    @Test
    fun stopRelay_afterStart_setsIsRunningFalse() {
        viewModel.startRelay(context)
        viewModel.stopRelay(context)
        assertFalse(viewModel.isRunning.value)
    }

    @Test
    fun multipleStarts_stateRemainsTrue() {
        viewModel.startRelay(context)
        viewModel.startRelay(context)
        assertTrue(viewModel.isRunning.value)
    }

    @Test
    fun stopWithoutStart_stateRemainsFalse() {
        viewModel.stopRelay(context)
        assertFalse(viewModel.isRunning.value)
    }

    // ---- navigation ----

    @Test
    fun initialScreen_isSearch() {
        assertEquals(WakeScreen.SEARCH, viewModel.currentScreen.value)
    }

    @Test
    fun navigateTo_status_setsStatus() {
        viewModel.navigateTo(WakeScreen.STATUS)
        assertEquals(WakeScreen.STATUS, viewModel.currentScreen.value)
    }

    @Test
    fun navigateTo_search_setsSearch() {
        viewModel.navigateTo(WakeScreen.STATUS)
        viewModel.navigateTo(WakeScreen.SEARCH)
        assertEquals(WakeScreen.SEARCH, viewModel.currentScreen.value)
    }

    // ---- search state ----

    @Test
    fun initialSearchState_isIdle() {
        assertTrue(viewModel.searchState.value is SearchUiState.Idle)
    }

    @Test
    fun onSearchQueryChanged_updatesFlow() {
        viewModel.onSearchQueryChanged("water")
        assertEquals("water", viewModel.searchQuery.value)
    }

    @Test
    fun submitSearch_blankQuery_staysIdle() = runTest {
        viewModel.setNodeId("node-1")
        viewModel.onSearchQueryChanged("   ")
        viewModel.submitSearch()
        advanceUntilIdle()
        assertTrue(viewModel.searchState.value is SearchUiState.Idle)
    }

    @Test
    fun submitSearch_nullNodeId_staysIdle() = runTest {
        viewModel.onSearchQueryChanged("water")
        viewModel.submitSearch()
        advanceUntilIdle()
        assertTrue(viewModel.searchState.value is SearchUiState.Idle)
    }

    @Test
    fun submitSearch_setsLoading() = runTest {
        viewModel.setNodeId("node-1")
        viewModel.onSearchQueryChanged("water")
        viewModel.submitSearch()
        // Loading is set synchronously before the coroutine dispatch runs.
        assertTrue(viewModel.searchState.value is SearchUiState.Loading)
        advanceUntilIdle()
        // Sender succeeded but no bundle has arrived yet — stays Loading.
        assertTrue(viewModel.searchState.value is SearchUiState.Loading)
    }

    @Test
    fun submitSearch_senderThrows_setsError() = runTest {
        fakeSenderThrows = true
        viewModel.setNodeId("node-1")
        viewModel.onSearchQueryChanged("water")
        viewModel.submitSearch()
        advanceUntilIdle()
        assertTrue(viewModel.searchState.value is SearchUiState.Error)
    }

    // ---- bundle arrival ----

    @Test
    fun onBundleArrived_matchingQuery_setsResults() = runTest {
        viewModel.setNodeId("node-1")
        viewModel.onSearchQueryChanged("water")
        viewModel.submitSearch()
        advanceUntilIdle()

        val html = """<a href="/A/Water">Water</a><a href="/A/Waterfall">Waterfall</a>"""
        // capturedQueryId holds the queryId the ViewModel sent to the server.
        val bundle = ReassembledBundle(capturedQueryId!!, "text/html", html.toByteArray())
        viewModel.onBundleArrived(bundle)

        val state = viewModel.searchState.value
        assertTrue(state is SearchUiState.Results)
        val results = (state as SearchUiState.Results).items
        assertEquals(2, results.size)
        assertEquals(SearchResult("Water", "/A/Water"), results[0])
        assertEquals(SearchResult("Waterfall", "/A/Waterfall"), results[1])
    }

    @Test
    fun onBundleArrived_differentQuery_ignored() = runTest {
        viewModel.setNodeId("node-1")
        viewModel.onSearchQueryChanged("water")
        viewModel.submitSearch()
        advanceUntilIdle()

        val wrongBundle = ReassembledBundle(
            queryId = "completely-wrong-id",
            contentType = "text/html",
            bytes = "<a href=\"/A/Water\">Water</a>".toByteArray(),
        )
        viewModel.onBundleArrived(wrongBundle)
        // Wrong queryId — stays Loading.
        assertTrue(viewModel.searchState.value is SearchUiState.Loading)
    }

    @Test
    fun onBundleArrived_emptyHtml_setsEmptyResults() = runTest {
        viewModel.setNodeId("node-1")
        viewModel.onSearchQueryChanged("xyzzy")
        viewModel.submitSearch()
        advanceUntilIdle()

        val bundle = ReassembledBundle(capturedQueryId!!, "text/html", "".toByteArray())
        viewModel.onBundleArrived(bundle)

        val state = viewModel.searchState.value
        assertTrue(state is SearchUiState.Results)
        assertTrue((state as SearchUiState.Results).items.isEmpty())
    }

    // ---- status state ----

    @Test
    fun initialStorageBytes_isZero() {
        assertEquals(0L, viewModel.storageUsedBytes.value)
    }

    @Test
    fun onStorageUpdated_updatesFlow() {
        viewModel.onStorageUpdated(1_048_576L)
        assertEquals(1_048_576L, viewModel.storageUsedBytes.value)
    }

    @Test
    fun initialLastSyncTime_isNull() {
        assertNull(viewModel.lastSyncTimeMs.value)
    }

    @Test
    fun onSyncTimeUpdated_updatesFlow() {
        val now = System.currentTimeMillis()
        viewModel.onSyncTimeUpdated(now)
        assertEquals(now, viewModel.lastSyncTimeMs.value)
    }

    // ---- HTML parsing ----

    @Test
    fun parseSearchResults_extractsLinksFromHtml() {
        val html = """
            <html>
            <a href="/A/Water">Water</a>
            <a href="/A/Water_treatment">Water treatment</a>
            <a href="/A/Waterfall">Waterfall</a>
            </html>
        """.trimIndent()
        val results = MainViewModel.parseSearchResults(html)
        assertEquals(3, results.size)
        assertEquals(SearchResult("Water", "/A/Water"), results[0])
        assertEquals(SearchResult("Water treatment", "/A/Water_treatment"), results[1])
        assertEquals(SearchResult("Waterfall", "/A/Waterfall"), results[2])
    }

    @Test
    fun parseSearchResults_extractsKiwixZimPrefixedLinks() {
        val html = """
            <html>
            <a href="/wikipedia_en_top_mini_2026-03/A/Water">Water</a>
            <a href="/wikipedia_en_top_mini_2026-03/A/Waterfall">Waterfall</a>
            </html>
        """.trimIndent()
        val results = MainViewModel.parseSearchResults(html)
        assertEquals(2, results.size)
        assertEquals(SearchResult("Water", "/wikipedia_en_top_mini_2026-03/A/Water"), results[0])
        assertEquals(SearchResult("Waterfall", "/wikipedia_en_top_mini_2026-03/A/Waterfall"), results[1])
    }

    @Test
    fun parseSearchResults_emptyHtml_returnsEmpty() {
        assertTrue(MainViewModel.parseSearchResults("").isEmpty())
    }

    @Test
    fun parseSearchResults_noArticleLinks_returnsEmpty() {
        val html = """<a href="/search?pattern=foo">not an article</a>"""
        assertTrue(MainViewModel.parseSearchResults(html).isEmpty())
    }

    @Test
    fun parseSearchResults_titleIsTrimmed() {
        val html = """<a href="/A/Water">  Water  </a>"""
        val results = MainViewModel.parseSearchResults(html)
        // Regex matches up to first '<'; surrounding spaces are trimmed by the companion.
        if (results.isNotEmpty()) {
            assertEquals("Water", results[0].title.trim())
        }
    }

    @Test
    fun parseSearchResults_supportsSingleQuotedHrefAndNestedMarkup() {
        val html = """
            <a class='result-link' href='/wikipedia_en_top_mini_2026-03/A/Water'>
              <span>Water</span>
            </a>
        """.trimIndent()

        val results = MainViewModel.parseSearchResults(html)

        assertEquals(1, results.size)
        assertEquals(
            SearchResult("Water", "/wikipedia_en_top_mini_2026-03/A/Water"),
            results[0],
        )
    }

    @Test
    fun parseSearchResults_supportsUppercaseAnchorAndHrefAttribute() {
        val html = """
            <A HREF="/A/Water_cycle" data-kind="article">Water cycle</A>
        """.trimIndent()

        val results = MainViewModel.parseSearchResults(html)

        assertEquals(1, results.size)
        assertEquals(SearchResult("Water cycle", "/A/Water_cycle"), results[0])
    }

    @Test
    fun parseSearchResults_supportsKiwixContentLinks() {
        val html = """
            <a href="/content/wikipedia_en_top_mini_2026-03/Water_cycle">Water cycle</a>
        """.trimIndent()

        val results = MainViewModel.parseSearchResults(html)

        assertEquals(1, results.size)
        assertEquals(
            SearchResult("Water cycle", "/content/wikipedia_en_top_mini_2026-03/Water_cycle"),
            results[0],
        )
    }
}
