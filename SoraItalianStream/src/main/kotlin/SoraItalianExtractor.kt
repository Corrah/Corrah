package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ShortLink.unshorten
import com.lagradost.nicehttp.Requests

object SoraItalianExtractor : SoraItalianStream() {

    suspend fun invoGuardare(
        id: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val res = app.get(
            "https://guardahd.stream/movie/$id",
            referer = "/"
        ).document
        res.select("ul._player-mirrors > li").forEach { source ->
            loadExtractor(
                fixUrl(source.attr("data-link")),
                "https://guardahd.stream",
                subtitleCallback,
                callback
            )
        }
    }

    suspend fun invoGuardaserie(
        id: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val url = app.post(
            guardaserieUrl, data = mapOf(
                "do" to "search",
                "subaction" to "search",
                "story" to id!!
            )
        ).document.selectFirst("h2>a")?.attr("href") ?: return

        val document = app.get(url).document
        document.select("div.tab-content > div").forEachIndexed { seasonData, data ->
            data.select("li").forEachIndexed { epNum, epData ->
                if (season == seasonData + 1 && episode == epNum + 1) {
                    epData.select("div.mirrors > a.mr").forEach {
                        loadExtractor(
                            fixUrl(it.attr("data-link")),
                            guardaserieUrl,
                            subtitleCallback,
                            callback
                        )
                    }
                }
            }
        }
    }

    // Removed functions invoFilmpertutti, invoCb01, invoAnimeWorld, invoAniPlay, invoAnimeSaturn
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