package io.nurunuru.app.data

import android.content.Context
import android.content.SharedPreferences
import io.nurunuru.app.data.models.NostrEvent
import io.nurunuru.app.data.models.ScoredPost
import io.nurunuru.app.data.models.UserProfile
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Recommendation engine for discovery feed.
 * Synced with web version: lib/recommendation.js
 *
 * Feed mix target:
 * - 50% from 2nd-degree network (friends of friends - discovery focus)
 * - 30% from out-of-network with high engagement (viral content)
 * - 20% from 1st-degree (important follows)
 */
class RecommendationEngine(context: Context) {

    private val tracker = EngagementTracker(context)

    // ─── Time Decay ──────────────────────────────────────────────────────────

    private fun calculateTimeDecay(createdAt: Long): Double {
        val now = System.currentTimeMillis() / 1000
        val ageInHours = max(0.0, (now - createdAt) / 3600.0)

        // Freshness boost for very recent posts (under 1 hour)
        if (ageInHours < 1.0) {
            return Constants.TimeDecay.FRESHNESS_BOOST
        }

        // Exponential decay with half-life
        val decayFactor = (0.5).pow(ageInHours / Constants.TimeDecay.HALF_LIFE_HOURS)
        return max(0.001, decayFactor)
    }

    // ─── Engagement Score ────────────────────────────────────────────────────

    data class EngagementCounts(
        val likes: Int = 0,
        val customReactions: Int = 0,
        val quotes: Int = 0,
        val replies: Int = 0,
        val reposts: Int = 0,
        val bookmarks: Int = 0,
        val zaps: Int = 0
    )

    private fun calculateEngagementScore(counts: EngagementCounts): Double {
        return counts.zaps * Constants.Engagement.WEIGHT_ZAP +
            counts.customReactions * Constants.Engagement.WEIGHT_CUSTOM_REACTION +
            counts.quotes * Constants.Engagement.WEIGHT_QUOTE +
            counts.replies * Constants.Engagement.WEIGHT_REPLY +
            counts.reposts * Constants.Engagement.WEIGHT_REPOST +
            counts.bookmarks * Constants.Engagement.WEIGHT_BOOKMARK +
            counts.likes * Constants.Engagement.WEIGHT_LIKE +
            1.0 // Minimum base score
    }

    // ─── Social Boost ────────────────────────────────────────────────────────

    private fun calculateSocialBoost(
        authorPubkey: String,
        followList: Set<String>,
        secondDegreeFollows: Set<String>,
        followers: Set<String>
    ): Double {
        val history = tracker.getEngagementHistory()
        val likedCount = history.likedAuthors[authorPubkey] ?: 0
        val repostedCount = history.repostedAuthors[authorPubkey] ?: 0
        val repliedCount = history.repliedAuthors[authorPubkey] ?: 0
        val totalEngagements = likedCount + repostedCount * 2 + repliedCount * 3

        val engagementBoost = when {
            totalEngagements >= 10 -> Constants.SocialBoost.HIGH_ENGAGEMENT_AUTHOR
            totalEngagements >= 5 -> 1.5
            else -> 1.0
        }

        return when {
            followList.contains(authorPubkey) -> {
                if (followers.contains(authorPubkey)) {
                    Constants.SocialBoost.MUTUAL_FOLLOW * engagementBoost
                } else {
                    Constants.SocialBoost.FIRST_DEGREE * engagementBoost
                }
            }
            secondDegreeFollows.contains(authorPubkey) ->
                Constants.SocialBoost.SECOND_DEGREE * engagementBoost
            else ->
                Constants.SocialBoost.UNKNOWN * engagementBoost
        }
    }

    // ─── Author Quality ──────────────────────────────────────────────────────

    private fun calculateAuthorQuality(profile: UserProfile?, followerCount: Int = 0): Double {
        var quality = 1.0

        // NIP-05 verification boost
        if (profile?.nip05 != null) {
            quality *= 1.3
        }

        // Follower count boost (logarithmic)
        if (followerCount > 0) {
            val followerBoost = 1.0 + ln(max(1.0, followerCount.toDouble())) / ln(10.0) * 0.1
            quality *= min(followerBoost, 1.5)
        }

        return quality
    }

    // ─── Main Score Calculation ──────────────────────────────────────────────

    data class ScoringContext(
        val followList: Set<String> = emptySet(),
        val secondDegreeFollows: Set<String> = emptySet(),
        val followers: Set<String> = emptySet(),
        val engagements: Map<String, EngagementCounts> = emptyMap(),
        val profiles: Map<String, UserProfile> = emptyMap(),
        val userGeohash: String? = null,
        val mutedPubkeys: Set<String> = emptySet()
    )

    fun calculateScore(post: NostrEvent, context: ScoringContext): Double {
        val authorPubkey = post.pubkey

        // Hard filters
        if (tracker.isNotInterested(post.id)) return -1.0
        if (context.mutedPubkeys.contains(authorPubkey)) return -1.0

        val engagement = context.engagements[post.id] ?: EngagementCounts()
        val engagementScore = calculateEngagementScore(engagement)

        val socialBoost = calculateSocialBoost(
            authorPubkey, context.followList, context.secondDegreeFollows, context.followers
        )

        val profile = context.profiles[authorPubkey]
        val authorQuality = calculateAuthorQuality(profile)

        // Geohash proximity boost
        val geohashBoost = calculateGeohashBoost(context.userGeohash, profile)

        // Author modifier from feedback history
        val authorModifier = tracker.getAuthorScore(authorPubkey)

        // Time decay
        val timeDecay = calculateTimeDecay(post.createdAt)

        return engagementScore * socialBoost * authorQuality * geohashBoost * authorModifier * timeDecay
    }

    private fun calculateGeohashBoost(userGeohash: String?, profile: UserProfile?): Double {
        if (userGeohash == null) return 1.0
        // Profile doesn't store geohash directly in current model;
        // this can be extended when profile geohash data is available
        return 1.0
    }

    // ─── Feed Mixing ─────────────────────────────────────────────────────────

    fun getRecommendedPosts(
        allPosts: List<ScoredPost>,
        context: ScoringContext,
        limit: Int = 50
    ): List<ScoredPost> {
        // Filter out muted and not interested
        val candidates = allPosts.filter { post ->
            !tracker.isNotInterested(post.event.id) &&
            !context.mutedPubkeys.contains(post.event.pubkey)
        }

        // Categorize
        val secondDegreePosts = candidates.filter { context.secondDegreeFollows.contains(it.event.pubkey) }
        val otherPosts = candidates.filter {
            !context.secondDegreeFollows.contains(it.event.pubkey) &&
            !context.followList.contains(it.event.pubkey)
        }
        val firstDegreePosts = candidates.filter { context.followList.contains(it.event.pubkey) }

        // Score and sort each category
        val scored2nd = scoreAndSort(secondDegreePosts, context)
        val scoredOther = scoreAndSort(otherPosts, context)
        val scored1st = scoreAndSort(firstDegreePosts, context)

        // Mix: 50% 2nd-degree, 30% other, 20% 1st-degree
        val result = mutableListOf<ScoredPost>()
        val target2nd = min(scored2nd.size, (limit * 0.5).toInt())
        val targetOther = min(scoredOther.size, (limit * 0.3).toInt())
        val target1st = min(scored1st.size, (limit * 0.2).toInt())

        result.addAll(scored2nd.take(target2nd))
        result.addAll(scoredOther.take(targetOther))
        result.addAll(scored1st.take(target1st))

        // Fill remaining slots
        val used = result.map { it.event.id }.toSet()
        val remaining = limit - result.size
        if (remaining > 0) {
            val available = (scored2nd + scoredOther + scored1st)
                .filter { it.event.id !in used }
                .take(remaining)
            result.addAll(available)
        }

        // Re-sort by score and apply diversity
        val sorted = scoreAndSort(result, context)
        return applyAuthorDiversity(sorted, limit)
    }

    private fun scoreAndSort(posts: List<ScoredPost>, context: ScoringContext): List<ScoredPost> {
        return posts.map { post ->
            val score = calculateScore(post.event, context)
            post.copy(score = score)
        }
            .filter { it.score > 0 }
            .sortedByDescending { it.score }
    }

    private fun applyAuthorDiversity(posts: List<ScoredPost>, limit: Int): List<ScoredPost> {
        val result = mutableListOf<ScoredPost>()
        val pool = posts.toMutableList()
        var lastAuthor: String? = null

        while (result.size < limit && pool.isNotEmpty()) {
            val index = pool.indexOfFirst { it.event.pubkey != lastAuthor }
                .takeIf { it >= 0 } ?: 0

            val next = pool.removeAt(index)
            result.add(next)
            lastAuthor = next.event.pubkey
        }

        return result
    }

    // ─── 2nd-degree Network ──────────────────────────────────────────────────

    companion object {
        fun extract2ndDegreeNetwork(
            myFollows: List<String>,
            followsOfFollows: Map<String, List<String>>
        ): Set<String> {
            val firstDegree = myFollows.toSet()
            val secondDegree = mutableSetOf<String>()

            for ((followerPubkey, theirFollows) in followsOfFollows) {
                if (!firstDegree.contains(followerPubkey)) continue
                for (pubkey in theirFollows) {
                    if (!firstDegree.contains(pubkey)) {
                        secondDegree.add(pubkey)
                    }
                }
            }

            return secondDegree
        }
    }

    // ─── Delegation to tracker ───────────────────────────────────────────────

    fun markNotInterested(eventId: String, authorPubkey: String) =
        tracker.markNotInterested(eventId, authorPubkey)

    fun recordEngagement(type: String, authorPubkey: String) =
        tracker.recordEngagement(type, authorPubkey)

    fun clearNotInterestedData() = tracker.clearNotInterestedData()
    fun clearAllData() = tracker.clearAllData()
}

/**
 * Persistent engagement tracking.
 * Stores not-interested posts, author scores, and engagement history.
 */
class EngagementTracker(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("nurunuru_engagement", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    // ─── Not Interested ──────────────────────────────────────────────────────

    fun getNotInterestedPosts(): Set<String> {
        val data = prefs.getString(KEY_NOT_INTERESTED, null) ?: return emptySet()
        return try { json.decodeFromString<List<String>>(data).toSet() } catch (_: Exception) { emptySet() }
    }

    fun isNotInterested(eventId: String): Boolean = getNotInterestedPosts().contains(eventId)

    fun markNotInterested(eventId: String, authorPubkey: String) {
        // Add to not interested posts (keep max 500)
        val posts = getNotInterestedPosts().toMutableList()
        posts.add(eventId)
        if (posts.size > 500) {
            posts.removeAt(0)
        }
        prefs.edit().putString(KEY_NOT_INTERESTED, json.encodeToString(posts)).apply()

        // Reduce author score by 30%
        val scores = getAuthorScoresMap().toMutableMap()
        val currentScore = scores[authorPubkey] ?: 1.0
        scores[authorPubkey] = max(0.1, currentScore * 0.7)

        // Keep max 200 authors
        if (scores.size > 200) {
            val sorted = scores.entries.sortedByDescending { it.value }.take(200)
            scores.clear()
            sorted.forEach { (k, v) -> scores[k] = v }
        }
        prefs.edit().putString(KEY_AUTHOR_SCORES, json.encodeToString(scores)).apply()
    }

    fun getAuthorScore(pubkey: String): Double =
        getAuthorScoresMap()[pubkey] ?: 1.0

    private fun getAuthorScoresMap(): Map<String, Double> {
        val data = prefs.getString(KEY_AUTHOR_SCORES, null) ?: return emptyMap()
        return try { json.decodeFromString(data) } catch (_: Exception) { emptyMap() }
    }

    // ─── Engagement History ──────────────────────────────────────────────────

    @kotlinx.serialization.Serializable
    data class EngagementHistory(
        val likedAuthors: Map<String, Int> = emptyMap(),
        val repostedAuthors: Map<String, Int> = emptyMap(),
        val repliedAuthors: Map<String, Int> = emptyMap()
    )

    fun getEngagementHistory(): EngagementHistory {
        val data = prefs.getString(KEY_ENGAGEMENT_HISTORY, null) ?: return EngagementHistory()
        return try { json.decodeFromString(data) } catch (_: Exception) { EngagementHistory() }
    }

    fun recordEngagement(type: String, authorPubkey: String) {
        val history = getEngagementHistory()
        val updated = when (type) {
            "like" -> history.copy(
                likedAuthors = incrementMap(history.likedAuthors, authorPubkey)
            )
            "repost" -> history.copy(
                repostedAuthors = incrementMap(history.repostedAuthors, authorPubkey)
            )
            "reply" -> history.copy(
                repliedAuthors = incrementMap(history.repliedAuthors, authorPubkey)
            )
            else -> history
        }
        prefs.edit().putString(KEY_ENGAGEMENT_HISTORY, json.encodeToString(updated)).apply()
    }

    private fun incrementMap(map: Map<String, Int>, key: String): Map<String, Int> {
        val mutable = map.toMutableMap()
        mutable[key] = (mutable[key] ?: 0) + 1

        // Keep only top 500 authors
        if (mutable.size > 500) {
            val sorted = mutable.entries.sortedByDescending { it.value }.take(500)
            mutable.clear()
            sorted.forEach { (k, v) -> mutable[k] = v }
        }

        return mutable
    }

    // ─── Cleanup ─────────────────────────────────────────────────────────────

    fun clearNotInterestedData() {
        prefs.edit()
            .remove(KEY_NOT_INTERESTED)
            .remove(KEY_AUTHOR_SCORES)
            .apply()
    }

    fun clearAllData() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_NOT_INTERESTED = "not_interested"
        private const val KEY_AUTHOR_SCORES = "author_scores"
        private const val KEY_ENGAGEMENT_HISTORY = "engagement_history"
    }
}
