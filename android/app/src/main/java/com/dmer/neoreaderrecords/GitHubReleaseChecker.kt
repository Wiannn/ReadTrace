package com.dmer.neoreaderrecords

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

object GitHubReleaseChecker {
    const val RELEASES_URL = "https://github.com/wberry9813/ReadTrace/releases"
    private const val LATEST_RELEASE_API = "https://api.github.com/repos/wberry9813/ReadTrace/releases/latest"
    private const val PREFS_NAME = "github_release_update"
    private const val KEY_LAST_CHECK_MS = "update_last_check_ms"
    private const val KEY_LATEST_TAG = "update_latest_tag"
    private const val KEY_LATEST_URL = "update_latest_url"
    private const val KEY_LATEST_NAME = "update_latest_name"
    private const val KEY_STATUS = "update_status"
    private const val KEY_ERROR = "update_error"
    private const val CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L

    data class State(
        val status: String,
        val latestTag: String,
        val latestUrl: String,
        val latestName: String,
        val lastCheckMs: Long,
        val error: String
    )

    fun cachedState(context: Context): State {
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return State(
            status = p.getString(KEY_STATUS, "尚未检查") ?: "尚未检查",
            latestTag = p.getString(KEY_LATEST_TAG, "") ?: "",
            latestUrl = p.getString(KEY_LATEST_URL, RELEASES_URL) ?: RELEASES_URL,
            latestName = p.getString(KEY_LATEST_NAME, "") ?: "",
            lastCheckMs = p.getLong(KEY_LAST_CHECK_MS, 0L),
            error = p.getString(KEY_ERROR, "") ?: ""
        )
    }

    fun shouldAutoCheck(context: Context, nowMs: Long = System.currentTimeMillis()): Boolean {
        val last = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getLong(KEY_LAST_CHECK_MS, 0L)
        return last <= 0L || nowMs - last >= CHECK_INTERVAL_MS
    }

    fun currentVersionName(context: Context): String {
        return runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        }.getOrDefault("unknown")
    }

    fun check(context: Context): State {
        val localVersion = currentVersionName(context)
        val conn = (URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "ReadTrace-Wallpaper/$localVersion")
        }
        return try {
            val code = conn.responseCode
            if (code !in 200..299) {
                return saveFailure(context, "GitHub 返回 HTTP $code")
            }
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val json = JSONObject(body)
            val tag = json.optString("tag_name", "")
            val url = json.optString("html_url", RELEASES_URL).ifBlank { RELEASES_URL }
            val name = json.optString("name", tag)
            val status = if (isRemoteNewer(tag, localVersion)) {
                "发现新版本：$tag"
            } else {
                "已是最新"
            }
            saveState(context, status, tag, url, name, "")
        } catch (e: Exception) {
            saveFailure(context, "${e.javaClass.simpleName}: ${e.message ?: "检查失败"}")
        } finally {
            conn.disconnect()
        }
    }

    private fun saveFailure(context: Context, error: String): State {
        val prior = cachedState(context)
        return saveState(
            context = context,
            status = "检查失败",
            latestTag = prior.latestTag,
            latestUrl = prior.latestUrl.ifBlank { RELEASES_URL },
            latestName = prior.latestName,
            error = error
        )
    }

    private fun saveState(
        context: Context,
        status: String,
        latestTag: String,
        latestUrl: String,
        latestName: String,
        error: String
    ): State {
        val now = System.currentTimeMillis()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putLong(KEY_LAST_CHECK_MS, now)
            .putString(KEY_STATUS, status)
            .putString(KEY_LATEST_TAG, latestTag)
            .putString(KEY_LATEST_URL, latestUrl.ifBlank { RELEASES_URL })
            .putString(KEY_LATEST_NAME, latestName)
            .putString(KEY_ERROR, error)
            .apply()
        return State(status, latestTag, latestUrl.ifBlank { RELEASES_URL }, latestName, now, error)
    }

    private fun isRemoteNewer(remoteTag: String, localVersion: String): Boolean {
        val remote = versionParts(remoteTag)
        val local = versionParts(localVersion)
        val count = maxOf(remote.size, local.size, 3)
        for (i in 0 until count) {
            val r = remote.getOrNull(i) ?: 0
            val l = local.getOrNull(i) ?: 0
            if (r != l) return r > l
        }
        return false
    }

    private fun versionParts(value: String): List<Int> {
        val normalized = value.lowercase(Locale.US).removePrefix("v")
        return Regex("""\d+""").findAll(normalized).map { it.value.toIntOrNull() ?: 0 }.take(4).toList()
    }
}
