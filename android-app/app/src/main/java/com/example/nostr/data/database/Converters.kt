package com.example.nostr.data.database

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class Converters {
    private val gson = Gson()

    @TypeConverter
    fun fromStringList(value: List<String>?): String? {
        return value?.let { gson.toJson(it) }
    }

    @TypeConverter
    fun toStringList(value: String?): List<String>? {
        return value?.let {
            val type = object : TypeToken<List<String>>() {}.type
            gson.fromJson(it, type)
        }
    }

    @TypeConverter
    fun fromTagsList(value: List<List<String>>?): String? {
        return value?.let { gson.toJson(it) }
    }

    @TypeConverter
    fun toTagsList(value: String?): List<List<String>>? {
        return value?.let {
            val type = object : TypeToken<List<List<String>>>() {}.type
            gson.fromJson(it, type)
        }
    }
}
