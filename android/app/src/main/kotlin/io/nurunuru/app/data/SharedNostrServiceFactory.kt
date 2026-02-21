package io.nurunuru.app.data

import io.nurunuru.shared.client.INostrClient
import io.nurunuru.shared.client.OkHttpNostrClient
import io.nurunuru.shared.client.NostrRepository as SharedNostrRepository
import io.nurunuru.shared.models.DEFAULT_RELAYS
import io.nurunuru.shared.recommendation.RecommendationEngine
import io.nurunuru.shared.models.ScoredPost as SharedScoredPost

/**
 * Factory for instantiating the KMP shared module's Nostr stack.
 *
 * This bridges the Android app's existing code with the new shared module.
 * Use this when you want the full KMP stack including:
 *   - OkHttpNostrClient (INostrClient implementation)
 *   - SharedNostrRepository (platform-agnostic logic)
 *   - RecommendationEngine (X-style "For You" scoring)
 *
 * Migration path:
 *   Old: NostrClient + NostrRepository (android-only)
 *   New: OkHttpNostrClient + SharedNostrRepository + RecommendationEngine
 */
object SharedNostrServiceFactory {

    /**
     * Create a fully initialized [INostrClient] and start connecting to relays.
     *
     * @param privateKeyHex 32-byte secp256k1 private key as hex string
     * @param relays        Relay URLs to connect to (defaults to [DEFAULT_RELAYS])
     */
    fun createClient(
        privateKeyHex: String,
        relays: List<String> = DEFAULT_RELAYS
    ): INostrClient {
        return OkHttpNostrClient(relays, privateKeyHex).also { it.connect() }
    }

    /**
     * Create a [SharedNostrRepository] from an existing [INostrClient].
     */
    fun createRepository(client: INostrClient): SharedNostrRepository =
        SharedNostrRepository(client)

    /**
     * Apply recommendation scoring to a list of posts.
     *
     * @param posts         Posts to score (fetched via SharedNostrRepository)
     * @param followList    Pubkeys the user follows
     * @param mutedPubkeys  Muted pubkeys (filtered out)
     * @param notInterested Event IDs marked "not interested"
     */
    fun scoreForYou(
        posts: List<SharedScoredPost>,
        followList: Set<String> = emptySet(),
        mutedPubkeys: Set<String> = emptySet(),
        notInterested: Set<String> = emptySet(),
        engagedAuthors: Map<String, Int> = emptyMap()
    ): List<SharedScoredPost> = RecommendationEngine.score(
        posts = posts,
        followList = followList,
        mutedPubkeys = mutedPubkeys,
        notInterested = notInterested,
        engagedAuthors = engagedAuthors
    )
}
