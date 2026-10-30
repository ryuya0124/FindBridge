package dev.findbridge.android

import org.json.JSONObject

internal data class FamilyMember(val id: String, val name: String) {
    companion object {
        fun fromUserInfo(userInfo: JSONObject?): List<FamilyMember> {
            val members = userInfo?.optJSONObject("membersInfo") ?: return emptyList()
            return members
                .keys()
                .asSequence()
                .mapNotNull { id ->
                    val member = members.optJSONObject(id) ?: return@mapNotNull null
                    val name =
                        listOf(member.optString("firstName"), member.optString("lastName"))
                            .filter { it.isNotBlank() }
                            .joinToString(" ")
                            .ifBlank { member.optString("displayName", "ファミリーメンバー") }
                    FamilyMember(id, name)
                }
                .toList()
        }
    }
}
