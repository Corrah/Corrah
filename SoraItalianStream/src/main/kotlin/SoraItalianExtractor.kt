package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.network.CloudflareKiller
import org.jsoup.internal.Normalizer.lowerCase
import java.text.SimpleDateFormat
import java.util.Locale

object SoraItalianExtractor : SoraItalianStream() {
    private val interceptor = CloudflareKiller()

    // Funzione per StreamingCommunity
    suspend fun invoStreamingCommunity(
        baseUrl: String,
        title: String,
        year: Int?,
        callback: (ExtractorLink) -> Unit
    ) {
        val url = "$baseUrl/search?query=${title.replace(" ", "%20")}&year=$year"
        val res = app.get(url).document
        res.select("div.result").forEach { result ->
            val link = result.select("a").attr("href")
            loadExtractor(fixUrl(link, baseUrl), baseUrl, {}, callback)
        }
    }

    // Funzione per AnimeUnity
    suspend fun invoAnimeUnity(
        baseUrl: String,
        title: String,
        year: Int?,
        callback: (ExtractorLink) -> Unit
    ) {
        val url = "$baseUrl/search?query=${title.replace(" ", "%20")}&year=$year"
        val res = app.get(url).document
        res.select("div.result").forEach { result ->
            val link = result.select("a").attr("href")
            loadExtractor(fixUrl(link, baseUrl), baseUrl, {}, callback)
        }
    }

    // Override della funzione per caricare i link
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit
    ): List<ExtractorLink> {
        val res = mutableListOf<ExtractorLink>()
        val link = parseJson<LinkData>(data)
        
        // Call the functions for the new providers
        invoStreamingCommunity(streamingCommunityUrl, link.title, link.year) { link ->
            res.add(link)
        }
        invoAnimeUnity(animeUnityUrl, link.title, link.year) { link ->
            res.add(link)
        }
        
        return res
    }

    // Funzioni di utilità
    private fun fixUrl(url: String, domain: String): String {
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

    private fun convertDateFormat(date: String): String { // from 2017-05-02 to May. 02, 2017
        val inputFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val outputFormat = SimpleDateFormat("MMM. dd, yyyy", Locale.US)
        val convertedDate = inputFormat.parse(date)
        return convertedDate?.let { outputFormat.format(it) } ?: date
    }

    // Dati del link
    data class LinkData(
        val id: Int,
        val imdbId: String?,
        val type: String,
        val title: String,
        val year: Int?,
        val orgTitle: String,
        val isAnime: Boolean?,
        val backdropPath: String?,
        val posterPath: String?,
        val tvDate: String?
    )
}