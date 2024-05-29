package com.lagradost

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.SoraItalianExtractor.invoGuardare
import com.lagradost.SoraItalianExtractor.invoGuardaserie
import com.lagradost.SoraItalianExtractor.invoAnimeWorld
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlin.math.roundToInt

open class SoraItalianStream : TmdbProvider() {
    override var name = "SoraStreamItaliano"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val instantLinkLoading = true
    override val useMetaLoadResponse = true
    override var lang = "it"
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
    )

    /** AUTHOR : Adippe & Hexated & Sora */
    companion object {
        private const val tmdbAPI = "https://api.themoviedb.org/3"
        private const val apiKey = "71f37e6dff3b879fa4656f19547c418c" // PLEASE DON'T STEAL
        const val guardaserieUrl = "https://guardaserie.ceo"
        const val animeworldUrl = "https://www.animeworld.so"
        const val tmdb2mal = "https://tmdb2mal.slidemovies.org"
        fun getType(t: String?): TvType {
            return when (t) {
                "movie" -> TvType.Movie
                else -> TvType.TvSeries
            }
        }

        fun getActorRole(t: String?): ActorRole {
            return when (t) {
                "Acting" -> ActorRole.Main
                else -> ActorRole.Background
            }
        }


        fun getStatus(t: String?): ShowStatus {
            return when (t) {
                "Returning Series" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }

        fun base64DecodeAPI(api: String): String {
            return api.chunked(4).map { base64Decode(it) }.reversed().joinToString("")
        }

        fun containsJapaneseCharacters(str: String?): Boolean { //sometimes the it-It translation of names gives the japanese name of the anime
            val japaneseCharactersRegex =
                Regex("[\\u3000-\\u303f\\u3040-\\u309f\\u30a0-\\u30ff\\uff00-\\uff9f\\u4e00-\\u9faf\\u3400-\\u4dbf]")
            return str?.let { japaneseCharactersRegex.containsMatchIn(it) } == true
        }

    }


    override val mainPage = mainPageOf(
        "$tmdbAPI/movie/popular?api_key=$apiKey&region=&language=it-IT&page=" to "Film Popolari",
        "$tmdbAPI/tv/popular?api_key=$apiKey&region=&language=it-IT&page=" to "Serie TV Popolari",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_keywords=210024|222243&page=" to "Anime",
        "$tmdbAPI/movie/top_rated?api_key=$apiKey&region=&language=it-IT&page=" to "Film più votati",
        "$tmdbAPI/tv/top_rated?api_key=$apiKey&region=&language=it-IT&page=" to "Serie TV più votate",
        "$tmdbAPI/discover/tv?api_key=$apiKey&language=it-IT&with_networks=213&page=" to "Netflix",
        "$tmdbAPI/discover/tv?api_key=$apiKey&language=it-IT&with_networks=1024&page=" to "Amazon",
        "$tmdbAPI/discover/tv?api_key=$apiKey&language=it-IT&with_networks=2739&page=" to "Disney+",
        "$tmdbAPI/discover/tv?api_key=$apiKey&language=it-IT&with_networks=453&page=" to "Hulu",
        "$tmdbAPI/discover/tv?api_key=$apiKey&language=it-IT&with_networks=2552&page=" to "Apple TV+"
    )

    private fun getImageUrl(link: String?): String? {
        if (link == null) return null
        return if (link.startsWith("/")) "https://image.tmdb.org/t/p/w500/$link" else link
    }

    private fun getOriImageUrl(link: String?): String? {
        if (link == null) return null
        return if (link.startsWith("/")) "https://image.tmdb.org/t/p/original/$link" else link
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val type = if (request.data.contains("/movie")) "movie" else "tv"
        val home = app.get(request.data + page)
            .parsedSafe<Results>()?.results
            ?.mapNotNull { media ->
                media.toSearchResponse(type)
            } ?: throw ErrorLoadingException("Invalid Json reponse")
        return newHomePageResponse(request.name, home)
    }

    private fun Media.toSearchResponse(type: String? = null): SearchResponse? {
        return newMovieSearchResponse(
            title ?: name ?: originalTitle ?: return null,
            Data(id = id, type = mediaType ?: type).toJson(),
            TvType.Movie,
        ) {
            this.posterUrl = getImageUrl(posterPath)
        }
    }


    override suspend fun search(query: String): List<SearchResponse>? {
        return app.get(
            "$tmdbAPI/search/multi?api_key=$apiKey&language=it-IT&query=$query&page=1&include_adult=false"
        )
            .parsedSafe<Results>()?.results?.mapNotNull { media ->
                media.toSearchResponse()
            }
    }


    override suspend fun load(url: String): LoadResponse? {
        val data = parseJson<Data>(url)

        val type = getType(data.type)

        val typename = data.type

        var res =
            app.get("$tmdbAPI/$typename/${data.id}?api_key=$apiKey&language=it-IT&append_to_response=external_ids,credits,recommendations,videos")
                .parsedSafe<MovieDetails>() ?: throw ErrorLoadingException("Invalid Json Response")
        if (containsJapaneseCharacters(res.name)) {
            res =
                app.get("$tmdbAPI/$typename/${data.id}?api_key=$apiKey&language=en-US&append_to_response=external_ids,credits,recommendations,videos")
                    .parsedSafe() ?: throw ErrorLoadingException("Invalid Json Response")
        }

        val title = res.name ?: res.title ?: return null
        val orgTitle = res.originalName ?: res.originalTitle ?: return null

        val year = (res.tvDate ?: res.movieDate)?.split("-")?.first()?.toIntOrNull()

        val actors = res.credits?.cast?.mapNotNull { cast ->
            ActorData(
                Actor(
                    cast.name ?: cast.originalName ?: return@mapNotNull null,
                    getImageUrl(cast.profilePath)
                ),
                getActorRole(cast.knownForDepartment)
            )
        } ?: return null
        val recommendations =
            res.recommandations?.results?.mapNotNull { media -> media.toSearchResponse() }

        val trailer =
            res.videos?.results?.map { "https://www.youtube.com/watch?v=${it.key}" }
                ?.randomOrNull()

        val isAnime = res.genres?.any { it.id == 16L } == true

        return if (type == TvType.Movie) { //can be a movie or a anime movie
            newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                LinkData(
                    data.id,
                    res.externalIds?.imdbId,
                    data.type,
                    title = title,
                    year = year,
                    orgTitle = orgTitle,
                    isAnime = isAnime
                ).toJson(),
            ) {
                this.posterUrl = getOriImageUrl(res.backdropPath)
                this.year = year
                this.plot = res.overview
                this.tags = res.genres?.mapNotNull { it.name }
                this.recommendations = recommendations
                this.actors = actors
                addTrailer(trailer)
            }
        } else { //can be anime series or tv series
            val episodes = mutableListOf<Episode>()
            var seasonNum = 0
            val seasonDataList = res.seasons?.filter { it.seasonNumber != 0 }?.apmap { season ->
                app.get("$tmdbAPI/tv/${data.id}/season/${season.seasonNumber}?api_key=$apiKey&language=it-IT")
                    .parsedSafe<SeasonData>()
            } ?: emptyList()
            seasonDataList.forEach { season ->
                seasonNum++
                season?.episodes?.mapIndexed { index, episode ->
                    episodes.add(
                        Episode(
                            data = Data(
                                data.id,
                                res.externalIds?.imdbId,
                                data.type,
                                season.seasonNumber,
                                index + 1,
                                title,
                                year,
                                episode.name,
                                orgTitle,
                                isAnime
                            ).toJson(),
                            name = episode.name,
                            episode = episode.episodeNumber,
                            season = season.seasonNumber,
                            posterUrl = getImageUrl(episode.stillPath),
                            description = episode.overview
                        )
                    )
                }
            }
            newTvSeriesLoadResponse(
                title,
                url,
                TvType.TvSeries,
                episodes
            ) {
                this.posterUrl = getOriImageUrl(res.backdropPath)
                this.year = year
                this.plot = res.overview
                this.tags = res.genres?.mapNotNull { it.name }
                this.recommendations = recommendations
                this.actors = actors
                addTrailer(trailer)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val linkData = parseJson<LinkData>(data)
        val name = linkData.title
        val episode = linkData.episode
        val season = linkData.season
        val searchname =
            if (episode == null) "$name streaming" else "$name stagione $season episodio $episode streaming"

        val animesearchname = "$name sub ita streaming"

        val guardaserieQuery =
            "${guardaserieUrl}/?s=$searchname"
        val animeworldQuery =
            "$animeworldUrl/search?keyword=$animesearchname"

        return when {
            (linkData.isAnime == true) -> {
                invoAnimeWorld(animeworldQuery, episode, callback)
            }

            else -> {
                invoGuardaserie(guardaserieQuery, season, episode, callback)
            }
        }
    }


    data class Data(
        @JsonProperty("id") val id: Int?,
        @JsonProperty("imdbId") val imdbId: String?,
        @JsonProperty("type") val type: String,
        @JsonProperty("season") val season: Int? = null,
        @JsonProperty("episode") val episode: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("year") val year: Int? = null,
        @JsonProperty("episodeTitle") val episodeTitle: String? = null,
        @JsonProperty("orgTitle") val orgTitle: String? = null,
        @JsonProperty("isAnime") val isAnime: Boolean? = null
    )

    data class LinkData(
        @JsonProperty("id") val id: Int?,
        @JsonProperty("imdbId") val imdbId: String?,
        @JsonProperty("type") val type: String,
        @JsonProperty("season") val season: Int? = null,
        @JsonProperty("episode") val episode: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("year") val year: Int? = null,
        @JsonProperty("episodeTitle") val episodeTitle: String? = null,
        @JsonProperty("orgTitle") val orgTitle: String? = null,
        @JsonProperty("isAnime") val isAnime: Boolean? = null
    )


    data class Results(
        @JsonProperty("results") val results: List<Media>,
    )

    data class Media(
        @JsonProperty("title") val title: String?,
        @JsonProperty("original_title") val originalTitle: String?,
        @JsonProperty("name") val name: String?,
        @JsonProperty("original_name") val originalName: String?,
        @JsonProperty("id") val id: Int?,
        @JsonProperty("media_type") val mediaType: String?,
        @JsonProperty("poster_path") val posterPath: String?,
    )

    data class MovieDetails(
        @JsonProperty("title") val title: String?,
        @JsonProperty("original_title") val originalTitle: String?,
        @JsonProperty("name") val name: String?,
        @JsonProperty("original_name") val originalName: String?,
        @JsonProperty("id") val id: Int?,
        @JsonProperty("genres") val genres: List<Genres>?,
        @JsonProperty("release_date") val movieDate: String?,
        @JsonProperty("first_air_date") val tvDate: String?,
        @JsonProperty("poster_path") val posterPath: String?,
        @JsonProperty("backdrop_path") val backdropPath: String?,
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("credits") val credits: Credits?,
        @JsonProperty("recommendations") val recommandations: Results?,
        @JsonProperty("videos") val videos: Videos?,
        @JsonProperty("external_ids") val externalIds: ExternalIds?,
        @JsonProperty("seasons") val seasons: List<Seasons>?,
    )

    data class Genres(
        @JsonProperty("id") val id: Long,
        @JsonProperty("name") val name: String,
    )

    data class ExternalIds(
        @JsonProperty("imdb_id") val imdbId: String?
    )

    data class Credits(
        @JsonProperty("cast") val cast: List<Cast>?,
    )

    data class Cast(
        @JsonProperty("name") val name: String?,
        @JsonProperty("original_name") val originalName: String?,
        @JsonProperty("profile_path") val profilePath: String?,
        @JsonProperty("known_for_department") val knownForDepartment: String?,
    )

    data class Videos(
        @JsonProperty("results") val results: List<Trailer>?,
    )

    data class Trailer(
        @JsonProperty("key") val key: String?,
    )

    data class Seasons(
        @JsonProperty("season_number") val seasonNumber: Int,
    )

    data class SeasonData(
        @JsonProperty("episodes") val episodes: List<EpisodeData>?,
    )

    data class EpisodeData(
        @JsonProperty("name") val name: String?,
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("episode_number") val episodeNumber: Int,
        @JsonProperty("still_path") val stillPath: String?,
    )

}