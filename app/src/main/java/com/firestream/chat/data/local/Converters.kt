package com.firestream.chat.data.local

import androidx.room.TypeConverter
import org.json.JSONArray
import org.json.JSONObject

class Converters {

    @TypeConverter
    fun fromList(list: List<String>): String {
        val array = JSONArray()
        list.forEach { array.put(it) }
        return array.toString()
    }

    @TypeConverter
    fun toList(json: String): List<String> {
        return try {
            val array = JSONArray(json)
            List(array.length()) { array.getString(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    @TypeConverter
    fun fromStringMap(map: Map<String, String>): String {
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k, v) }
        return obj.toString()
    }

    @TypeConverter
    fun toStringMap(json: String): Map<String, String> {
        return try {
            val obj = JSONObject(json)
            buildMap { obj.keys().forEach { key -> put(key, obj.getString(key)) } }
        } catch (_: Exception) {
            emptyMap()
        }
    }
}
