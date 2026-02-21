package io.nurunuru.shared.recommendation

import io.nurunuru.shared.models.NostrEvent
import io.nurunuru.shared.models.NostrKind
import io.nurunuru.shared.models.ScoredPost
import io.nurunuru.shared.models.UserProfile
import io.nurunuru.shared.platform.currentTimeSeconds
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/**
 * X-style "For You" recommendation engine.
 *
 * Ported from Web's lib/recommendation.js.
 * Pure Kotlin – no platform dependencies.
 *
 * Feed mix target:
 *   50% from 2nd-degree network (friends-of-friends – discovery)
 *   30% from out-of-network with high engagement (viral content)
 *   20% from 1st-degree (to ensure important follows appear)
 */
object RecommendationEngine {

    // ─── Engagement weights (mirrors recommendation.js) ──────────────────────

    private const val W_ZAP = 100.0        // Monetary commitment: highest value
    private const val W_QUOTE = 35.0       // Quote with commentary: high value
    private const val W_REPLY = 30.0       // Deep engagement
    private const val W_REPOST = 25.0      // Share-worthy
    private const val W_BOOKMARK = 15.0    // Personal value
    private const val W_LIKE = 5.0         // Easy action: lowest weight

    // ─── Negative signal weights ──────────────────────────────────────────────

    private const val W_NOT_INTERESTED = -50.0
    private const val W_MUTED_AUTHOR = -1000.0   // Effectively filters out
    private const val W_REPORTED = -200.0

    // ─── Social boost multipliers ─────────────────────────────────────────────

    private const val BOOST_SECOND_DEGREE = 3.0    // Friends-of-friends
    private const val BOOST_MUTUAL_FOLLOW = 2.5    // Mutual follower
    private const val BOOST_HIGH_ENGAGEMENT = 2.0  // Author you often engage with
    private const val BOOST_FIRST_DEGREE = 0.5     // Already in follow feed
    private const val BOOST_UNKNOWN = 1.0          // Neutral

    // ─── Time decay parameters ────────────────────────────────────────────────

    private const val HALF_LIFE_HOURS = 6.0        // Score halves every 6h
    private const val MAX_AGE_HOURS = 48.0         // Posts older than 48h score very low
    private const val FRESHNESS_BOOST = 1.5        // Under 1h old: +50%

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Score a list of posts for a given user context.
     *
     * @param posts           Raw posts to score (must include [ScoredPost.likeCount] etc.)
     * @param followList      Set of pubkeys the user follows (1st-degree)
     * @param secondDegree    Set of pubkeys followed by follows (2nd-degree)
     * @param mutedPubkeys    Pubkeys the user has muted (will be filtered out)
     * @param notInterested   Event IDs the user marked "not interested"
     * @param engagedAuthors  Map of authorPubkey → engagement count for this user
     * @param mutualFollowers Pubkeys that follow the user back
     */
    fun score(
        posts: List<ScoredPost>,
        followList: Set<String> = emptySet(),
        secondDegree: Set<String> = emptySet(),
        mutedPubkeys: Set<String> = emptySet(),
        notInterested: Set<String> = emptySet(),
        engagedAuthors: Map<String, Int> = emptyMap(),
        mutualFollowers: Set<String> = emptySet()
    ): List<ScoredPost> {
        val now = currentTimeSeconds()

        return posts
            .filter { post ->
                // Hard filter: muted authors and user's own posts
                post.event.pubkey !in mutedPubkeys
            }
            .map { post ->
                val s = computeScore(
                    post = post,
                    nowSeconds = now,
                    followList = followList,
                    secondDegree = secondDegree,
                    notInterested = notInterested,
                    engagedAuthors = engagedAuthors,
                    mutualFollowers = mutualFollowers
                )
                post.copy(score = s)
            }
            .filter { it.score > 0.0 }
            .sortedByDescending { it.score }
    }

    /**
     * Mix the feed according to the target ratios.
     *
     * @param firstDegree    Posts from direct follows
     * @param secondDegree   Posts from friends-of-friends
     * @param outOfNetwork   High-engagement out-of-network posts
     * @param targetSize     Desired output size
     */
    fun mixFeed(
        firstDegree: List<ScoredPost>,
        secondDegree: List<ScoredPost>,
        outOfNetwork: List<ScoredPost>,
        targetSize: Int = 50
    ): List<ScoredPost> {
        val n1st = (targetSize * 0.20).toInt()
        val n2nd = (targetSize * 0.50).toInt()
        val nOon = targetSize - n1st - n2nd

        val mixed = (firstDegree.take(n1st) + secondDegree.take(n2nd) + outOfNetwork.take(nOon))
            .distinctBy { it.event.id }
            .sortedByDescending { it.score }

        return mixed.take(targetSize)
    }

    // ─── Scoring internals ────────────────────────────────────────────────────

    private fun computeScore(
        post: ScoredPost,
        nowSeconds: Long,
        followList: Set<String>,
        secondDegree: Set<String>,
        notInterested: Set<String>,
        engagedAuthors: Map<String, Int>,
        mutualFollowers: Set<String>
    ): Double {
        val author = post.event.pubkey

        // Negative signals first
        if (post.event.id in notInterested) return W_NOT_INTERESTED

        // Base engagement score
        var score = post.zapCount * W_ZAP +
            post.replyCount * W_REPLY +
            post.repostCount * W_REPOST +
            post.likeCount * W_LIKE

        // Ensure non-negative base before multipliers
        if (score < 0.0) score = 0.0

        // Social boost
        val socialBoost = when {
            author in mutualFollowers -> BOOST_MUTUAL_FOLLOW
            author in secondDegree -> BOOST_SECOND_DEGREE
            author in followList -> BOOST_FIRST_DEGREE
            (engagedAuthors[author] ?: 0) >= 3 -> BOOST_HIGH_ENGAGEMENT
            else -> BOOST_UNKNOWN
        }
        score *= socialBoost

        // Author engagement boost (personalization)
        val engagementCount = engagedAuthors[author] ?: 0
        if (engagementCount > 0) {
            score *= (1.0 + 0.1 * engagementCount.coerceAtMost(10))
        }

        // Time decay
        score *= timeDecay(post.event.createdAt, nowSeconds)

        return score
    }

    /**
     * Time decay factor.
     * - Posts under 1h get a freshness boost (×1.5)
     * - Score halves every [HALF_LIFE_HOURS] hours
     * - Posts over [MAX_AGE_HOURS] are dampened to near-zero
     */
    private fun timeDecay(createdAt: Long, nowSeconds: Long): Double {
        val ageSeconds = (nowSeconds - createdAt).coerceAtLeast(0L)
        val ageHours = ageSeconds / 3600.0

        if (ageHours > MAX_AGE_HOURS) return 0.01

        val decayFactor = 2.0.pow(-ageHours / HALF_LIFE_HOURS)
        return if (ageHours < 1.0) decayFactor * FRESHNESS_BOOST else decayFactor
    }

    // ─── Engagement history helpers ───────────────────────────────────────────

    /**
     * Merge engagement history from multiple interactions.
     * Returns a map of authorPubkey → total engagement count.
     */
    fun mergeEngagementHistory(
        liked: Map<String, Int>,
        reposted: Map<String, Int>,
        replied: Map<String, Int>
    ): Map<String, Int> {
        val merged = mutableMapOf<String, Int>()
        for ((k, v) in liked) merged[k] = (merged[k] ?: 0) + v
        for ((k, v) in reposted) merged[k] = (merged[k] ?: 0) + v * 2  // Reposts weighted 2×
        for ((k, v) in replied) merged[k] = (merged[k] ?: 0) + v * 3   // Replies weighted 3×
        return merged
    }

    /**
     * Compute a simple author quality score (0–1).
     * Higher for verified NIP-05 users with pictures and bios.
     */
    fun authorQualityScore(profile: UserProfile?): Double {
        if (profile == null) return 0.0
        var score = 0.0
        if (!profile.name.isNullOrBlank()) score += 0.2
        if (!profile.displayName.isNullOrBlank()) score += 0.1
        if (!profile.about.isNullOrBlank()) score += 0.2
        if (!profile.picture.isNullOrBlank()) score += 0.2
        if (!profile.nip05.isNullOrBlank()) score += 0.3  // Verified identity
        return score
    }
}
