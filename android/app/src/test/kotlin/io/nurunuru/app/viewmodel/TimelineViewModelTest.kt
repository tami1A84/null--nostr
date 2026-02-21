package io.nurunuru.app.viewmodel

import io.mockk.*
import io.nurunuru.app.data.NostrRepository
import io.nurunuru.app.data.models.NostrEvent
import io.nurunuru.app.data.models.ScoredPost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TimelineViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: NostrRepository
    private val pubkey = "aabbccdd" + "0".repeat(56)

    private fun makePost(id: String, score: Double = 0.0) = ScoredPost(
        event = NostrEvent(id = id, pubkey = pubkey, kind = 1, content = "post $id"),
        score = score
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── init / loadTimeline ────────────────────────────────────────────────────

    @Test
    fun init_loadsGlobalTimelineByDefault() = runTest {
        val posts = listOf(makePost("p1"), makePost("p2"))
        coEvery { repository.fetchGlobalTimeline(50) } returns posts

        val vm = TimelineViewModel(repository, pubkey)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertEquals(2, state.posts.size)
        assertEquals(FeedType.GLOBAL, state.feedType)
    }

    @Test
    fun loadTimeline_error_setsErrorMessage() = runTest {
        coEvery { repository.fetchGlobalTimeline(50) } throws RuntimeException("timeout")

        val vm = TimelineViewModel(repository, pubkey)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertNotNull(state.error)
        assertTrue(state.error!!.isNotBlank())
    }

    // ── switchFeed ─────────────────────────────────────────────────────────────

    @Test
    fun switchFeed_toFollowing_callsFetchFollowTimeline() = runTest {
        coEvery { repository.fetchGlobalTimeline(50) } returns emptyList()
        val followingPosts = listOf(makePost("f1"), makePost("f2"), makePost("f3"))
        coEvery { repository.fetchFollowTimeline(pubkey, 50) } returns followingPosts

        val vm = TimelineViewModel(repository, pubkey)
        advanceUntilIdle()

        vm.switchFeed(FeedType.FOLLOWING)
        advanceUntilIdle()

        assertEquals(FeedType.FOLLOWING, vm.uiState.value.feedType)
        assertEquals(3, vm.uiState.value.posts.size)
        coVerify { repository.fetchFollowTimeline(pubkey, 50) }
    }

    @Test
    fun switchFeed_toSameFeed_doesNotReload() = runTest {
        coEvery { repository.fetchGlobalTimeline(50) } returns listOf(makePost("g1"))

        val vm = TimelineViewModel(repository, pubkey)
        advanceUntilIdle()

        vm.switchFeed(FeedType.GLOBAL) // already GLOBAL – no-op
        advanceUntilIdle()

        // fetchGlobalTimeline called only once (from init)
        coVerify(exactly = 1) { repository.fetchGlobalTimeline(50) }
    }

    // ── refresh ────────────────────────────────────────────────────────────────

    @Test
    fun refresh_reloadsCurrentFeed() = runTest {
        val initial = listOf(makePost("r1"))
        val refreshed = listOf(makePost("r1"), makePost("r2"))
        coEvery { repository.fetchGlobalTimeline(50) } returnsMany listOf(initial, refreshed)

        val vm = TimelineViewModel(repository, pubkey)
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.posts.size)

        vm.refresh()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isRefreshing)
        assertEquals(2, state.posts.size)
    }

    @Test
    fun refresh_error_setsErrorAndClearsRefreshing() = runTest {
        coEvery { repository.fetchGlobalTimeline(50) } returnsMany listOf(
            emptyList(),
            throw RuntimeException("network error")
        )

        val vm = TimelineViewModel(repository, pubkey)
        advanceUntilIdle()

        vm.refresh()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isRefreshing)
        assertNotNull(state.error)
    }

    // ── search ─────────────────────────────────────────────────────────────────

    @Test
    fun search_withQuery_callsRepositorySearch() = runTest {
        coEvery { repository.fetchGlobalTimeline(50) } returns emptyList()
        val results = listOf(makePost("s1"), makePost("s2"))
        coEvery { repository.searchNotes("hello", 30) } returns results

        val vm = TimelineViewModel(repository, pubkey)
        advanceUntilIdle()

        vm.search("hello")
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals("hello", state.searchQuery)
        assertEquals(2, state.searchResults.size)
        assertFalse(state.isSearching)
    }

    @Test
    fun search_withBlankQuery_clearsResults() = runTest {
        coEvery { repository.fetchGlobalTimeline(50) } returns emptyList()

        val vm = TimelineViewModel(repository, pubkey)
        advanceUntilIdle()

        vm.search("   ")

        val state = vm.uiState.value
        assertTrue(state.searchQuery.isEmpty())
        assertTrue(state.searchResults.isEmpty())
        assertFalse(state.isSearching)
    }

    @Test
    fun clearSearch_resetsSearchState() = runTest {
        coEvery { repository.fetchGlobalTimeline(50) } returns emptyList()
        coEvery { repository.searchNotes("test", 30) } returns listOf(makePost("q1"))

        val vm = TimelineViewModel(repository, pubkey)
        advanceUntilIdle()

        vm.search("test")
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.searchResults.size)

        vm.clearSearch()
        val state = vm.uiState.value
        assertTrue(state.searchQuery.isEmpty())
        assertTrue(state.searchResults.isEmpty())
        assertFalse(state.isSearching)
    }

    // ── likePost ──────────────────────────────────────────────────────────────

    @Test
    fun likePost_success_updatesPostLikeCount() = runTest {
        val post = makePost("like1")
        coEvery { repository.fetchGlobalTimeline(50) } returns listOf(post)
        coEvery { repository.likePost("like1") } returns true

        val vm = TimelineViewModel(repository, pubkey)
        advanceUntilIdle()

        vm.likePost("like1")
        advanceUntilIdle()

        val updated = vm.uiState.value.posts.first { it.event.id == "like1" }
        assertTrue(updated.isLiked)
        assertEquals(1, updated.likeCount)
    }

    @Test
    fun likePost_failure_doesNotUpdateState() = runTest {
        val post = makePost("like2")
        coEvery { repository.fetchGlobalTimeline(50) } returns listOf(post)
        coEvery { repository.likePost("like2") } returns false

        val vm = TimelineViewModel(repository, pubkey)
        advanceUntilIdle()

        vm.likePost("like2")
        advanceUntilIdle()

        val unchanged = vm.uiState.value.posts.first { it.event.id == "like2" }
        assertFalse(unchanged.isLiked)
        assertEquals(0, unchanged.likeCount)
    }

    // ── repostPost ─────────────────────────────────────────────────────────────

    @Test
    fun repostPost_success_updatesPostRepostCount() = runTest {
        val post = makePost("rp1")
        coEvery { repository.fetchGlobalTimeline(50) } returns listOf(post)
        coEvery { repository.repostPost("rp1") } returns true

        val vm = TimelineViewModel(repository, pubkey)
        advanceUntilIdle()

        vm.repostPost("rp1")
        advanceUntilIdle()

        val updated = vm.uiState.value.posts.first { it.event.id == "rp1" }
        assertTrue(updated.isReposted)
        assertEquals(1, updated.repostCount)
    }

    // ── publishNote ────────────────────────────────────────────────────────────

    @Test
    fun publishNote_callsRepositoryAndRefreshes() = runTest {
        val initial = listOf(makePost("init"))
        val afterRefresh = listOf(makePost("init"), makePost("new"))
        coEvery { repository.fetchGlobalTimeline(50) } returnsMany listOf(initial, afterRefresh)
        coEvery { repository.publishNote(any(), replyToId = null) } just runs

        val vm = TimelineViewModel(repository, pubkey)
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.posts.size)

        vm.publishNote("Hello Nostr!")
        advanceUntilIdle()

        coVerify { repository.publishNote("Hello Nostr!", replyToId = null) }
        assertEquals(2, vm.uiState.value.posts.size)
    }

    @Test
    fun publishNote_withReplyToId_passesItToRepository() = runTest {
        coEvery { repository.fetchGlobalTimeline(50) } returns emptyList()
        coEvery { repository.publishNote(any(), replyToId = any()) } just runs

        val vm = TimelineViewModel(repository, pubkey)
        advanceUntilIdle()

        vm.publishNote("Reply!", replyToId = "parentEventId")
        advanceUntilIdle()

        coVerify { repository.publishNote("Reply!", replyToId = "parentEventId") }
    }

    // ── fetchLightningInvoice ─────────────────────────────────────────────────

    @Test
    fun fetchLightningInvoice_delegatesToRepository() = runTest {
        coEvery { repository.fetchGlobalTimeline(50) } returns emptyList()
        val invoice = "lnbc1234..."
        coEvery { repository.fetchLightningInvoice("a@b.com", 100L, "tip") } returns invoice

        val vm = TimelineViewModel(repository, pubkey)
        advanceUntilIdle()

        val result = vm.fetchLightningInvoice("a@b.com", 100L, "tip")
        assertEquals(invoice, result)
    }
}
