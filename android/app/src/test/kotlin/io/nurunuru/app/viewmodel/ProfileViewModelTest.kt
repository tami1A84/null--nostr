package io.nurunuru.app.viewmodel

import io.mockk.*
import io.nurunuru.app.data.NostrRepository
import io.nurunuru.app.data.models.NostrEvent
import io.nurunuru.app.data.models.ScoredPost
import io.nurunuru.app.data.models.UserProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: NostrRepository
    private val myPubkey = "aabbcc" + "0".repeat(58)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── Initial state ──────────────────────────────────────────────────────────

    @Test
    fun initialState_isLoadingTrueAndNoError() {
        val vm = ProfileViewModel(repository, myPubkey)
        val state = vm.uiState.value
        assertNull(state.profile)
        assertNull(state.error)
        assertFalse(state.isFollowing)
        assertNull(state.zapInvoice)
    }

    // ── loadProfile ────────────────────────────────────────────────────────────

    @Test
    fun loadProfile_success_setsProfileAndPosts() = runTest {
        val pubkey = "deadbeef" + "0".repeat(56)
        val profile = UserProfile(pubkey = pubkey, name = "Alice", displayName = "Alice-chan")
        val event = NostrEvent(id = "e1", pubkey = pubkey, kind = 1, content = "Hello")
        val posts = listOf(ScoredPost(event = event, score = 1.0))

        coEvery { repository.fetchProfile(pubkey) } returns profile
        coEvery { repository.fetchUserNotes(pubkey, 30) } returns posts
        coEvery { repository.fetchFollowList(pubkey) } returns listOf("f1", "f2", "f3")
        coEvery { repository.isFollowing(pubkey, myPubkey) } returns false

        val vm = ProfileViewModel(repository, myPubkey)
        vm.loadProfile(pubkey)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertEquals(profile, state.profile)
        assertEquals(1, state.posts.size)
        assertEquals(3, state.followCount)
        assertNull(state.error)
    }

    @Test
    fun loadProfile_setsIsFollowing_whenFollowingTarget() = runTest {
        val pubkey = "cafecafe" + "0".repeat(56)
        coEvery { repository.fetchProfile(pubkey) } returns UserProfile(pubkey = pubkey)
        coEvery { repository.fetchUserNotes(pubkey, 30) } returns emptyList()
        coEvery { repository.fetchFollowList(pubkey) } returns emptyList()
        coEvery { repository.isFollowing(pubkey, myPubkey) } returns true

        val vm = ProfileViewModel(repository, myPubkey)
        vm.loadProfile(pubkey)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.isFollowing)
    }

    @Test
    fun loadProfile_doesNotCheckFollowing_forOwnProfile() = runTest {
        coEvery { repository.fetchProfile(myPubkey) } returns UserProfile(pubkey = myPubkey)
        coEvery { repository.fetchUserNotes(myPubkey, 30) } returns emptyList()
        coEvery { repository.fetchFollowList(myPubkey) } returns emptyList()

        val vm = ProfileViewModel(repository, myPubkey)
        vm.loadProfile(myPubkey)
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.isFollowing(any(), any()) }
        assertFalse(vm.uiState.value.isFollowing)
    }

    @Test
    fun loadProfile_error_setsErrorMessage() = runTest {
        val pubkey = "badbad00" + "0".repeat(56)
        coEvery { repository.fetchProfile(pubkey) } throws RuntimeException("network error")

        val vm = ProfileViewModel(repository, myPubkey)
        vm.loadProfile(pubkey)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertNotNull(state.error)
        assertTrue(state.error!!.isNotBlank())
    }

    // ── toggleFollow ──────────────────────────────────────────────────────────

    @Test
    fun toggleFollow_fromUnfollowed_callsFollowUser() = runTest {
        val pubkey = "1234" + "0".repeat(60)
        coEvery { repository.fetchProfile(pubkey) } returns UserProfile(pubkey = pubkey)
        coEvery { repository.fetchUserNotes(pubkey, 30) } returns emptyList()
        coEvery { repository.fetchFollowList(pubkey) } returns emptyList()
        coEvery { repository.isFollowing(pubkey, myPubkey) } returns false
        coEvery { repository.followUser(pubkey, myPubkey) } returns true

        val vm = ProfileViewModel(repository, myPubkey)
        vm.loadProfile(pubkey)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isFollowing)

        vm.toggleFollow(pubkey)
        advanceUntilIdle()

        coVerify { repository.followUser(pubkey, myPubkey) }
        assertTrue(vm.uiState.value.isFollowing)
    }

    @Test
    fun toggleFollow_fromFollowed_callsUnfollowUser() = runTest {
        val pubkey = "5678" + "0".repeat(60)
        coEvery { repository.fetchProfile(pubkey) } returns UserProfile(pubkey = pubkey)
        coEvery { repository.fetchUserNotes(pubkey, 30) } returns emptyList()
        coEvery { repository.fetchFollowList(pubkey) } returns emptyList()
        coEvery { repository.isFollowing(pubkey, myPubkey) } returns true
        coEvery { repository.unfollowUser(pubkey, myPubkey) } returns true

        val vm = ProfileViewModel(repository, myPubkey)
        vm.loadProfile(pubkey)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.isFollowing)

        vm.toggleFollow(pubkey)
        advanceUntilIdle()

        coVerify { repository.unfollowUser(pubkey, myPubkey) }
        assertFalse(vm.uiState.value.isFollowing)
    }

    @Test
    fun toggleFollow_whenFollowFails_stateUnchanged() = runTest {
        val pubkey = "9999" + "0".repeat(60)
        coEvery { repository.fetchProfile(pubkey) } returns UserProfile(pubkey = pubkey)
        coEvery { repository.fetchUserNotes(pubkey, 30) } returns emptyList()
        coEvery { repository.fetchFollowList(pubkey) } returns emptyList()
        coEvery { repository.isFollowing(pubkey, myPubkey) } returns false
        coEvery { repository.followUser(pubkey, myPubkey) } returns false

        val vm = ProfileViewModel(repository, myPubkey)
        vm.loadProfile(pubkey)
        advanceUntilIdle()

        vm.toggleFollow(pubkey)
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isFollowing)
        assertFalse(vm.uiState.value.isFollowLoading)
    }

    // ── fetchZapInvoice ───────────────────────────────────────────────────────

    @Test
    fun fetchZapInvoice_success_setsZapInvoice() = runTest {
        val invoice = "lnbc1000n1..."
        coEvery { repository.fetchLightningInvoice("alice@lightning.io", 100L, "test") } returns invoice

        val vm = ProfileViewModel(repository, myPubkey)
        vm.fetchZapInvoice("alice@lightning.io", 100L, "test")
        advanceUntilIdle()

        assertEquals(invoice, vm.uiState.value.zapInvoice)
        assertFalse(vm.uiState.value.zapLoading)
    }

    @Test
    fun fetchZapInvoice_failure_setsNullInvoice() = runTest {
        coEvery { repository.fetchLightningInvoice(any(), any(), any()) } returns null

        val vm = ProfileViewModel(repository, myPubkey)
        vm.fetchZapInvoice("noone@example.com", 50L, "")
        advanceUntilIdle()

        assertNull(vm.uiState.value.zapInvoice)
        assertFalse(vm.uiState.value.zapLoading)
    }

    @Test
    fun clearZapInvoice_clearsInvoiceField() = runTest {
        val invoice = "lnbc500..."
        coEvery { repository.fetchLightningInvoice(any(), any(), any()) } returns invoice

        val vm = ProfileViewModel(repository, myPubkey)
        vm.fetchZapInvoice("x@y.com", 50L, "")
        advanceUntilIdle()
        assertEquals(invoice, vm.uiState.value.zapInvoice)

        vm.clearZapInvoice()
        assertNull(vm.uiState.value.zapInvoice)
    }

    // ── fetchZapInvoiceSync ────────────────────────────────────────────────────

    @Test
    fun fetchZapInvoiceSync_returnsRepositoryResult() = runTest {
        val invoice = "lnbc99..."
        coEvery { repository.fetchLightningInvoice("b@c.io", 200L, "sync") } returns invoice

        val vm = ProfileViewModel(repository, myPubkey)
        val result = vm.fetchZapInvoiceSync("b@c.io", 200L, "sync")
        assertEquals(invoice, result)
    }
}
