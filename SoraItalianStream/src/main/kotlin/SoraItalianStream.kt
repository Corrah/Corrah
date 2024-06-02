package com.lagradost

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.SoraItalianExtractor.invoStreamingCommunity
import com.lagradost.SoraItalianExtractor.invoAnimeUnity
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
        const val streamingCommunityUrl = "https://streamingcommunity.foo"
        const val animeUnityUrl = "https://www.animeunity.to"
        
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

        fun containsJapaneseCharacters(str: String?): Boolean {
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
        page: Int, request: MainPageRequest
    ): HomePageResponse {
        val type = if (request.data.contains("/movie")) "movie" else "tv"
        val home =
            app.get(request.data + page).parsedSafe<Results>()?.results?.mapNotNull { media ->
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
        ).parsedSafe<Results>()?.results?.mapNotNull { media ->
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
                ), getActorRole(cast.knownForDepartment)
            )
        } ?: return null
        val recommendations =
            res.recommandations?.results?.mapNotNull { media -> media.toSearchResponse() }

        val trailer =
            res.videos?.results?.map { "https://www.youtube.com/watch?v=${it.key}" }?.randomOrNull()

        val isAnime = res.genres?.any { it.id == 16L } == true
        val backdropPath = res.backdropPath
        val posterPath = res.posterPath
        val tvDate = res.tvDate

        return if (type == TvType.Movie) {
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
                    isAnime = isAnime,
                    backdropPath = backdropPath,
                    posterPath = posterPath,
                    tvDate = tvDate
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
        } else {
            val episodes = mutableListOf<Episode>()
            var seasonNum = 0
            val seasonDataList = res.seasons?.filter { it.seasonNumber != 0 }?.map { season ->
                app.get("$tmdbAPI/${data.type}/${data.id}/season/${season.seasonNumber}?api_key=$apiKey&language=it-IT")
                    .parsedSafe<MediaDetailEpisodes>()?.episodes
            }

            seasonDataList?.forEach { mediaList ->
                seasonNum++
                mediaList?.map { episode ->
                    episodes.add(
                        Episode(
                            data.id.toString() + episode.episodeNumber.toString() + seasonNum,
                            season = seasonNum,
                            name = episode.name,
                            episode = episode.episodeNumber,
                            posterUrl = getImageUrl(episode.stillPath),
                            description = episode.overview,
                            date = episode.airDate,
                        )
                    )
                }
            }
            newTvSeriesLoadResponse(
                title,
                url,
                TvType.TvSeries,
                episodes,
                LinkData(
                    data.id,
                    res.externalIds?.imdbId,
                    data.type,
                    title = title,
                    year = year,
                    orgTitle = orgTitle,
                    isAnime = isAnime,
                    backdropPath = backdropPath,
                    posterPath = posterPath,
                    tvDate = tvDate
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
        }
    }

    override suspend fun loadLinks(
        data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit
    ): List<ExtractorLink> {
        val res = mutableListOf<ExtractorLink>()
        val link = parseJson<LinkData>(data)
        
        // Call the functions for the new providers
        res.addAll(invoStreamingCommunity(streamingCommunityUrl, link.title, link.year))
        res.addAll(invoAnimeUnity(animeUnityUrl, link.title, link.year))
        
        return res
    }

    data class LinkData(
        @JsonProperty("id") val id: Int,
        @JsonProperty("imdbId") val imdbId: String?,
        @JsonProperty("type") val type: String,
        @JsonProperty("title") val title: String,
        @JsonProperty("year") val year: Int?,
        @JsonProperty("orgTitle") val orgTitle: String,
        @JsonProperty("isAnime") val isAnime: Boolean?,
        @JsonProperty("backdropPath") val backdropPath: String?,
        @JsonProperty("posterPath") val posterPath: String?,
        @JsonProperty("tvDate") val tvDate: String?,
    )

    data class Data(
        @JsonProperty("id") val id: Int,
        @JsonProperty("type") val type: String
    )

    data class MovieDetails(
        @JsonProperty("id") val id: Int,
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("name") val name: String?,
        @JsonProperty("title") val title: String?,
        @JsonProperty("original_name") val originalName: String?,
        @JsonProperty("original_title") val originalTitle: String?,
        @JsonProperty("release_date") val movieDate: String?,
        @JsonProperty("first_air_date") val tvDate: String?,
        @JsonProperty("genres") val genres: List<Genres>?,
        @JsonProperty("credits") val credits: Credits?,
        @JsonProperty("recommendations") val recommandations: Results?,
        @JsonProperty("videos") val videos: Videos?,
        @JsonProperty("external_ids") val externalIds: External?,
        @JsonProperty("seasons") val seasons: List<Seasons>?,
        @JsonProperty("poster_path") val posterPath: String?,
        @JsonProperty("backdrop_path") val backdropPath: String?,
    )

    data class Credits(
        @JsonProperty("cast") val cast: List<Cast>?
    )

    data class Cast(
        @JsonProperty("name") val name: String?,
        @JsonProperty("original_name") val originalName: String?,
        @JsonProperty("profile_path") val profilePath: String?,
        @JsonProperty("known_for_department") val knownForDepartment: String?,
    )

    data class Results(
        @JsonProperty("results") val results: List<Media>?
    )

    data class External(
        @JsonProperty("imdb_id") val imdbId: String?
    )

    data class Videos(
        @JsonProperty("results") val results: List<Trailer>?
    )

    data class Trailer(
        @JsonProperty("key") val key: String
    )

    data class Media(
        @JsonProperty("id") val id: Int?,
        @JsonProperty("name") val name: String?,
        @JsonProperty("title") val title: String?,
        @JsonProperty("original_name") val originalName: String?,
        @JsonProperty("original_title") val originalTitle: String?,
        @JsonProperty("media_type") val mediaType: String?,
        @JsonProperty("release_date") val movieDate: String?,
        @JsonProperty("first_air_date") val tvDate: String?,
        @JsonProperty("poster_path") val posterPath: String?,
    )

    data class Genres(
        @JsonProperty("id") val id: Long,
        @JsonProperty("name") val name: String?
    )

    data class Seasons(
        @JsonProperty("season_number") val seasonNumber: Int?,
    )

    data class MediaDetailEpisodes(
        @JsonProperty("episodes") val episodes: List<EpisodeData>?,
    )

    data class EpisodeData(
        @JsonProperty("episode_number") val episodeNumber: Int,
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("still_path") val stillPath: String?,
        @JsonProperty("name") val name: String?,
        @JsonProperty("air_date") val airDate: String?,
    )
}