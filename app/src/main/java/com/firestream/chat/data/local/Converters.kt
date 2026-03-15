package com.firestream.chat.data.local

import androidx.room.TypeConverter
import com.firestream.chat.domain.model.GroupPermissions
import com.firestream.chat.domain.model.GroupRole
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

    @TypeConverter
    fun fromLongMap(map: Map<String, Long>): String {
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k, v) }
        return obj.toString()
    }

    @TypeConverter
    fun toLongMap(json: String): Map<String, Long> {
        return try {
            val obj = JSONObject(json)
            buildMap { obj.keys().forEach { key -> put(key, obj.getLong(key)) } }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    @TypeConverter
    fun fromIntFloatMap(map: Map<Int, Float>): String {
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k.toString(), v.toDouble()) }
        return obj.toString()
    }

    @TypeConverter
    fun toIntFloatMap(json: String): Map<Int, Float> {
        return try {
            val obj = JSONObject(json)
            buildMap { obj.keys().forEach { key -> put(key.toInt(), obj.getDouble(key).toFloat()) } }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    @TypeConverter
    fun fromGroupPermissions(permissions: GroupPermissions): String {
        val obj = JSONObject()
        obj.put("sendMessages", permissions.sendMessages.name)
        obj.put("editGroupInfo", permissions.editGroupInfo.name)
        obj.put("addMembers", permissions.addMembers.name)
        obj.put("createPolls", permissions.createPolls.name)
        obj.put("isAnnouncementMode", permissions.isAnnouncementMode)
        return obj.toString()
    }

    @TypeConverter
    fun toGroupPermissions(json: String): GroupPermissions {
        return try {
            val obj = JSONObject(json)
            GroupPermissions(
                sendMessages = runCatching { GroupRole.valueOf(obj.getString("sendMessages")) }.getOrDefault(GroupRole.MEMBER),
                editGroupInfo = runCatching { GroupRole.valueOf(obj.getString("editGroupInfo")) }.getOrDefault(GroupRole.ADMIN),
                addMembers = runCatching { GroupRole.valueOf(obj.getString("addMembers")) }.getOrDefault(GroupRole.ADMIN),
                createPolls = runCatching { GroupRole.valueOf(obj.getString("createPolls")) }.getOrDefault(GroupRole.MEMBER),
                isAnnouncementMode = obj.optBoolean("isAnnouncementMode", false)
            )
        } catch (_: Exception) {
            GroupPermissions()
        }
    }
}
