package com.mdos.auto

/** VIDEO and WEB are hidden while the car moves; MUSIC stays usable. */
enum class Kind { VIDEO, MUSIC, WEB }

data class Service(
    val id: String,
    val name: String,
    val glyph: String,
    val url: String,
    val color: Int,
    val kind: Kind,
    /** Desktop sites: needed for Netflix/Prime/Spotify web players and avoids "get the app" walls. */
    val desktop: Boolean,
    /** Search URL with %s for the query, or null if the service has none we can rely on. */
    val searchUrl: String? = null,
    val loginUrl: String? = null,
)

object Services {
    val all: List<Service> = listOf(
        Service("youtube", "YouTube", "▶", "https://m.youtube.com/", 0xFFE0002A.toInt(), Kind.VIDEO, false,
            "https://m.youtube.com/results?search_query=%s"),
        Service("netflix", "Netflix", "N", "https://www.netflix.com/browse", 0xFFB20710.toInt(), Kind.VIDEO, true,
            "https://www.netflix.com/search?q=%s", "https://www.netflix.com/login"),
        Service("prime", "Prime Video", "prime", "https://www.primevideo.com/", 0xFF0F79AF.toInt(), Kind.VIDEO, true,
            "https://www.primevideo.com/search/?phrase=%s"),
        Service("hotstar", "JioHotstar", "JH", "https://www.hotstar.com/in/home", 0xFF1F3C88.toInt(), Kind.VIDEO, true),
        Service("sonyliv", "SonyLIV", "LIV", "https://www.sonyliv.com/", 0xFF5B2A86.toInt(), Kind.VIDEO, true),
        Service("zee5", "ZEE5", "Z5", "https://www.zee5.com/", 0xFF7B1FA2.toInt(), Kind.VIDEO, true),
        Service("mxplayer", "MX Player", "MX", "https://www.mxplayer.in/", 0xFF1565C0.toInt(), Kind.VIDEO, true),
        Service("spotify", "Spotify", "♫", "https://open.spotify.com/", 0xFF1AA34A.toInt(), Kind.MUSIC, true,
            "https://open.spotify.com/search/%s", "https://accounts.spotify.com/login"),
        Service("ytmusic", "YouTube Music", "♪", "https://music.youtube.com/", 0xFFA3001B.toInt(), Kind.MUSIC, true,
            "https://music.youtube.com/search?q=%s"),
        Service("jiosaavn", "JioSaavn", "JS", "https://www.jiosaavn.com/", 0xFF1E9E91.toInt(), Kind.MUSIC, true,
            "https://www.jiosaavn.com/search/%s"),
        Service("amazonmusic", "Amazon Music", "aM", "https://music.amazon.in/", 0xFF0D7F93.toInt(), Kind.MUSIC, true,
            "https://music.amazon.in/search/%s"),
        Service("gaana", "Gaana", "G", "https://gaana.com/", 0xFFC62828.toInt(), Kind.MUSIC, true),
        Service("web", "Browser", "WWW", "https://www.google.com/", 0xFF2F5BD3.toInt(), Kind.WEB, false,
            "https://www.google.com/search?q=%s"),
    )

    val youtube: Service get() = all.first()

    fun byId(id: String?): Service? = all.firstOrNull { it.id == id }
}
