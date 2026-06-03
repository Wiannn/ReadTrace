package com.dmer.neoreaderrecords

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

object GitHubReleaseChecker {
    const val RELEASES_URL = "https://github.com/wberry9813/ReadTrace/releases"
    private const val LATEST_RELEASE_API = "https://api.github.com/repos/wberry9813/ReadTrace/releases/latest"
    private const val RELEASES_API = "https://api.github.com/repos/wberry9813/ReadTrace/releases"
    private const val TAGS_API = "https://api.github.com/repos/wberry9813/ReadTrace/tags"
    private const val LATEST_RELEASE_PAGE = "https://github.com/wberry9813/ReadTrace/releases/latest"
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
        AutoRefreshLog.i(context, "update check start local=$localVersion")
        return try {
            val release = fetchLatestRelease(context, localVersion)
                ?: fetchFirstReleaseFromList(context, localVersion)
                ?: fetchFirstTag(context, localVersion)
                ?: fetchLatestReleasePage(context, localVersion)
                ?: return saveFailure(context, "GitHub 未返回可用 Release 或 Tag")
            val status = if (isRemoteNewer(release.tag, localVersion)) {
                "发现新版本：${release.tag}"
            } else {
                "已是最新"
            }
            AutoRefreshLog.i(context, "update check success tag=${release.tag} source=${release.source} status=$status")
            saveState(context, status, release.tag, release.url, release.name, "")
        } catch (e: Exception) {
            AutoRefreshLog.e(context, "update check failed", e)
            saveFailure(context, "${e.javaClass.simpleName}: ${e.message ?: "检查失败"}")
        }
    }

    private data class ReleaseInfo(
        val tag: String,
        val url: String,
        val name: String,
        val source: String
    )

    private data class HttpResult(
        val code: Int,
        val body: String,
        val finalUrl: String
    )

    private fun fetchLatestRelease(context: Context, localVersion: String): ReleaseInfo? {
        val result = httpGet(context, LATEST_RELEASE_API, localVersion, "latest")
        if (result.code !in 200..299) return null
        val json = JSONObject(result.body)
        return releaseFromJson(json, "latest")
    }

    private fun fetchFirstReleaseFromList(context: Context, localVersion: String): ReleaseInfo? {
        val result = httpGet(context, RELEASES_API, localVersion, "releases")
        if (result.code !in 200..299) return null
        val array = JSONArray(result.body)
        for (i in 0 until array.length()) {
            val json = array.optJSONObject(i) ?: continue
            if (json.optBoolean("draft", false)) continue
            val release = releaseFromJson(json, "releases")
            if (release != null) return release
        }
        AutoRefreshLog.i(context, "update check releases list empty")
        return null
    }

    private fun fetchFirstTag(context: Context, localVersion: String): ReleaseInfo? {
        val result = httpGet(context, TAGS_API, localVersion, "tags")
        if (result.code !in 200..299) return null
        val array = JSONArray(result.body)
        val json = array.optJSONObject(0) ?: return null
        val tag = json.optString("name", "").trim()
        if (tag.isBlank()) return null
        val url = "https://github.com/wberry9813/ReadTrace/releases/tag/$tag"
        return ReleaseInfo(tag = tag, url = url, name = tag, source = "tags")
    }

    private fun fetchLatestReleasePage(context: Context, localVersion: String): ReleaseInfo? {
        val result = httpGet(context, LATEST_RELEASE_PAGE, localVersion, "release_page", accept = "text/html")
        if (result.code !in 200..299) return null
        val tag = Regex("""/releases/tag/([^/?#]+)""").find(result.finalUrl)?.groupValues?.getOrNull(1)
            ?: Regex("""/releases/tag/([^"'<>?#]+)""").find(result.body)?.groupValues?.getOrNull(1)
            ?: return null
        val url = "https://github.com/wberry9813/ReadTrace/releases/tag/$tag"
        return ReleaseInfo(tag = tag, url = url, name = tag, source = "release_page")
    }

    private fun releaseFromJson(json: JSONObject, source: String): ReleaseInfo? {
        val tag = json.optString("tag_name", "").trim()
        if (tag.isBlank()) return null
        val url = json.optString("html_url", RELEASES_URL).ifBlank { RELEASES_URL }
        val name = json.optString("name", tag).ifBlank { tag }
        return ReleaseInfo(tag = tag, url = url, name = name, source = source)
    }

    private fun httpGet(
        context: Context,
        api: String,
        localVersion: String,
        label: String,
        accept: String = "application/vnd.github+json"
    ): HttpResult {
        val conn = (URL(api).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 8_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", "ReadTrace-Wallpaper/$localVersion")
        }
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val finalUrl = conn.url?.toString().orEmpty()
            AutoRefreshLog.i(context, "update check http $label code=$code bytes=${body.length} final=$finalUrl")
            HttpResult(code, body, finalUrl)
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
