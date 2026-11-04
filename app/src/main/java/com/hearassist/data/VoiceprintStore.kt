package com.hearassist.data

import android.content.Context
import com.hearassist.audio.VoiceprintProcessor
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 声纹库持久化：JSON 存储于应用私有目录，重装 App 才需要重新录入 */
object VoiceprintStore {

    private fun file(ctx: Context): File = File(ctx.filesDir, "voiceprints.json")

    fun save(ctx: Context, entries: List<VoiceprintProcessor.SpeakerEntry>) {
        val arr = JSONArray()
        entries.forEach { e ->
            val embArr = JSONArray()
            e.embeddings.forEach { emb ->
                val v = JSONArray()
                emb.forEach { f -> v.put(f.toDouble()) }
                embArr.put(v)
            }
            arr.put(
                JSONObject()
                    .put("name", e.name)
                    .put("color", e.color)
                    .put("embeddings", embArr)
            )
        }
        file(ctx).writeText(JSONObject().put("speakers", arr).toString())
    }

    fun load(ctx: Context): List<VoiceprintProcessor.SpeakerEntry> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        return try {
            val root = JSONObject(f.readText())
            val arr = root.optJSONArray("speakers") ?: return emptyList()
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val embArr = o.optJSONArray("embeddings") ?: JSONArray()
                val embeddings = (0 until embArr.length()).map { j ->
                    val v = embArr.getJSONArray(j)
                    FloatArray(v.length()).also { out ->
                        for (k in 0 until v.length()) out[k] = v.getDouble(k).toFloat()
                    }
                }
                VoiceprintProcessor.SpeakerEntry(
                    name = o.getString("name"),
                    color = o.getInt("color"),
                    embeddings = embeddings,
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
