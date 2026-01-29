package com.example.nostr.network.relay

import com.example.nostr.nostr.event.NostrEvent
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonParser

/**
 * Messages sent from client to relay
 */
sealed class ClientMessage {
    abstract fun toJson(): String

    /**
     * EVENT message - publish an event
     */
    data class Event(val event: NostrEvent) : ClientMessage() {
        override fun toJson(): String {
            val gson = Gson()
            return "[\"EVENT\",${gson.toJson(event)}]"
        }
    }

    /**
     * REQ message - request events matching filters
     */
    data class Request(
        val subscriptionId: String,
        val filters: List<Filter>
    ) : ClientMessage() {
        override fun toJson(): String {
            val gson = Gson()
            val array = JsonArray().apply {
                add("REQ")
                add(subscriptionId)
                filters.forEach { add(gson.toJsonTree(it)) }
            }
            return gson.toJson(array)
        }
    }

    /**
     * CLOSE message - close a subscription
     */
    data class Close(val subscriptionId: String) : ClientMessage() {
        override fun toJson(): String = "[\"CLOSE\",\"$subscriptionId\"]"
    }

    /**
     * AUTH message - authenticate to relay (NIP-42)
     */
    data class Auth(val event: NostrEvent) : ClientMessage() {
        override fun toJson(): String {
            val gson = Gson()
            return "[\"AUTH\",${gson.toJson(event)}]"
        }
    }

    /**
     * COUNT message - count events matching filters (NIP-45)
     */
    data class Count(
        val subscriptionId: String,
        val filters: List<Filter>
    ) : ClientMessage() {
        override fun toJson(): String {
            val gson = Gson()
            val array = JsonArray().apply {
                add("COUNT")
                add(subscriptionId)
                filters.forEach { add(gson.toJsonTree(it)) }
            }
            return gson.toJson(array)
        }
    }
}

/**
 * Messages received from relay
 */
sealed class RelayMessage {
    /**
     * EVENT message - relay sending an event
     */
    data class Event(
        val subscriptionId: String,
        val event: NostrEvent
    ) : RelayMessage()

    /**
     * OK message - response to EVENT
     */
    data class Ok(
        val eventId: String,
        val accepted: Boolean,
        val message: String
    ) : RelayMessage()

    /**
     * EOSE message - end of stored events
     */
    data class EndOfStoredEvents(val subscriptionId: String) : RelayMessage()

    /**
     * CLOSED message - subscription was closed
     */
    data class Closed(
        val subscriptionId: String,
        val message: String
    ) : RelayMessage()

    /**
     * NOTICE message - human-readable message
     */
    data class Notice(val message: String) : RelayMessage()

    /**
     * AUTH message - authentication challenge (NIP-42)
     */
    data class Auth(val challenge: String) : RelayMessage()

    /**
     * COUNT message - count result (NIP-45)
     */
    data class Count(
        val subscriptionId: String,
        val count: Int
    ) : RelayMessage()

    companion object {
        private val gson = Gson()

        /**
         * Parse a relay message from JSON
         */
        fun fromJson(json: String): RelayMessage? {
            return try {
                val array = JsonParser.parseString(json).asJsonArray
                val type = array[0].asString

                when (type) {
                    "EVENT" -> {
                        val subscriptionId = array[1].asString
                        val event = gson.fromJson(array[2], NostrEvent::class.java)
                        Event(subscriptionId, event)
                    }
                    "OK" -> {
                        val eventId = array[1].asString
                        val accepted = array[2].asBoolean
                        val message = if (array.size() > 3) array[3].asString else ""
                        Ok(eventId, accepted, message)
                    }
                    "EOSE" -> {
                        val subscriptionId = array[1].asString
                        EndOfStoredEvents(subscriptionId)
                    }
                    "CLOSED" -> {
                        val subscriptionId = array[1].asString
                        val message = if (array.size() > 2) array[2].asString else ""
                        Closed(subscriptionId, message)
                    }
                    "NOTICE" -> {
                        val message = array[1].asString
                        Notice(message)
                    }
                    "AUTH" -> {
                        val challenge = array[1].asString
                        Auth(challenge)
                    }
                    "COUNT" -> {
                        val subscriptionId = array[1].asString
                        val countObj = array[2].asJsonObject
                        val count = countObj.get("count").asInt
                        Count(subscriptionId, count)
                    }
                    else -> null
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}

/**
 * Filter for REQ messages (NIP-01)
 */
data class Filter(
    val ids: List<String>? = null,
    val authors: List<String>? = null,
    val kinds: List<Int>? = null,
    val since: Long? = null,
    val until: Long? = null,
    val limit: Int? = null,
    @Transient
    val tags: Map<String, List<String>> = emptyMap()
) {
    // Custom serialization to handle dynamic tag filters (#e, #p, etc.)
    fun toJsonMap(): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        ids?.let { map["ids"] = it }
        authors?.let { map["authors"] = it }
        kinds?.let { map["kinds"] = it }
        since?.let { map["since"] = it }
        until?.let { map["until"] = it }
        limit?.let { map["limit"] = it }
        tags.forEach { (key, values) ->
            map["#$key"] = values
        }
        return map
    }

    companion object {
        /**
         * Create a filter for text notes from specific authors
         */
        fun textNotes(authors: List<String>, limit: Int = 50, since: Long? = null): Filter {
            return Filter(
                authors = authors,
                kinds = listOf(1),
                limit = limit,
                since = since
            )
        }

        /**
         * Create a filter for metadata
         */
        fun metadata(pubkeys: List<String>): Filter {
            return Filter(
                authors = pubkeys,
                kinds = listOf(0)
            )
        }

        /**
         * Create a filter for contact lists
         */
        fun contactList(pubkey: String): Filter {
            return Filter(
                authors = listOf(pubkey),
                kinds = listOf(3),
                limit = 1
            )
        }

        /**
         * Create a filter for reactions to events
         */
        fun reactions(eventIds: List<String>): Filter {
            return Filter(
                kinds = listOf(7),
                tags = mapOf("e" to eventIds)
            )
        }

        /**
         * Create a filter for reposts
         */
        fun reposts(eventIds: List<String>): Filter {
            return Filter(
                kinds = listOf(6),
                tags = mapOf("e" to eventIds)
            )
        }

        /**
         * Create a filter for zaps
         */
        fun zaps(eventIds: List<String>): Filter {
            return Filter(
                kinds = listOf(9735),
                tags = mapOf("e" to eventIds)
            )
        }
    }
}
