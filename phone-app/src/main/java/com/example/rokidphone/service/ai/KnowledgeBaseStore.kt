package com.example.rokidphone.service.ai

import android.content.Context

object KnowledgeBaseStore {
    const val AUTO_ID = "auto"
    const val NONE_ID = "none"
    const val SOFTWARE_ENGINEERING_ID = "software_engineering"
    const val FRENCH_ID = "french"

    private const val PREFS_NAME = "rokid_photo_ai"
    private const val KEY_SELECTED_KNOWLEDGE_BASE_ID = "selected_knowledge_base_id"

    fun getSelectedKnowledgeBaseId(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SELECTED_KNOWLEDGE_BASE_ID, AUTO_ID)
            ?.takeIf { it.isNotBlank() }
            ?: AUTO_ID
    }

    fun saveSelectedKnowledgeBaseId(context: Context, knowledgeBaseId: String) {
        val normalized = when (knowledgeBaseId) {
            AUTO_ID,
            NONE_ID,
            SOFTWARE_ENGINEERING_ID,
            FRENCH_ID -> knowledgeBaseId
            else -> AUTO_ID
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SELECTED_KNOWLEDGE_BASE_ID, normalized)
            .apply()
    }
}
