package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.network.CloudflareKiller
import org.jsoup.internal.Normalizer.lowerCase
import java.text.SimpleDateFormat
import java.util.Locale

object SoraItalianExtractor : SoraItalianStream() {
    private val interceptor = CloudflareKiller()

    suspend fun invoAnimeUnity(
        title: String?,
        episode: Int?,
        callback: (ExtractorLink) -> Unit
    ) {
        val url = "https://www.animeunity.to/anime/$title"
        val document = app.get(url).document
        val episodeUrl = document.select("a[href*=episode-$episode]").attr("href")
        if (episodeUrl.isNotBlank()) {
            val trueUrl = app.get(episodeUrl).select("video source").attr("src")
            callback.invoke(
                ExtractorLink(
                    "AnimeUnity",
                    "AnimeUnity",
                    trueUrl,
                    referer = url,
                    quality = Qualities.Unknown.value
                )
            )
        }
    }

    suspend fun invoStreamingCommunity(
        title: String?,
        episode: Int?,
        callback: (ExtractorLink) -> Unit
    ) {
        val url = "https://streamingcommunity.foo/anime/$title"
        val document = app.get(url).document
        val episodeUrl = document.select("a[href*=episode-$episode]").attr("href")
        if (episodeUrl.isNotBlank()) {
            val trueUrl = app.get(episodeUrl).select("video source").attr("src")
            callback.invoke(
                ExtractorLink(
                    "StreamingCommunity",
                    "StreamingCommunity",
                    trueUrl,
                    referer = url,
                    quality = Qualities.Unknown.value
                )
            )
        }
    }

    suspend fun loadLinks(
        title: String?,
        episode: Int?,
        callback: (ExtractorLink) -> Unit
    ) {
        invoAnimeUnity(title, episode, callback)
        invoStreamingCommunity(title, episode, callback)
    }
    
    // Other existing functions remain unchanged...
}

private fun isDub(title: String?): Boolean {
    return title?.contains(" (ITA)") ?: false
}

fun fixUrl(url: String, domain: String): String {
    if (url.startsWith("http")) {
        return url
    }
    if (url.isEmpty()) {
        return ""
    }

    val startsWithNoHttp = url.startsWith("//")
    if (startsWithNoHttp) {
        return "https:$url"
    } else {
        if (url.startsWith('/')) {
            return domain + url
        }
        return "$domain/$url"
    }
}

fun convertDateFormat(date: String): String { //from 2017-05-02 to May. 02, 2017
    val inputFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val outputFormat = SimpleDateFormat("MMM. dd, yyyy", Locale.US)
    val convertedDate = inputFormat.parse(date)
    return convertedDate?.let { outputFormat.format(it) } ?: date
}