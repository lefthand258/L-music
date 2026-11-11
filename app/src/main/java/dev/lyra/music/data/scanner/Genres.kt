package dev.lyra.music.data.scanner

/** Genre lists: ID3v1 standard table (for numeric tag decoding) and the curated picker list. */
object Genres {
    val ID3V1: List<String> = listOf(
        "Blues", "Classic Rock", "Country", "Dance", "Disco", "Funk", "Grunge", "Hip-Hop", "Jazz", "Metal",
        "New Age", "Oldies", "Other", "Pop", "R&B", "Rap", "Reggae", "Rock", "Techno", "Industrial",
        "Alternative", "Ska", "Death Metal", "Pranks", "Soundtrack", "Euro-Techno", "Ambient", "Trip-Hop", "Vocal", "Jazz+Funk",
        "Fusion", "Trance", "Classical", "Instrumental", "Acid", "House", "Game", "Sound Clip", "Gospel", "Noise",
        "Alternative Rock", "Bass", "Soul", "Punk", "Space", "Meditative", "Instrumental Pop", "Instrumental Rock", "Ethnic", "Gothic",
        "Darkwave", "Techno-Industrial", "Electronic", "Pop-Folk", "Eurodance", "Dream", "Southern Rock", "Comedy", "Cult", "Gangsta",
        "Top 40", "Christian Rap", "Pop/Funk", "Jungle", "Native US", "Cabaret", "New Wave", "Psychedelic", "Rave", "Showtunes",
        "Trailer", "Lo-Fi", "Tribal", "Acid Punk", "Acid Jazz", "Polka", "Retro", "Musical", "Rock & Roll", "Hard Rock",
        "Folk", "Folk-Rock", "National Folk", "Swing", "Fast Fusion", "Bebob", "Latin", "Revival", "Celtic", "Bluegrass",
        "Avantgarde", "Gothic Rock", "Progressive Rock", "Psychedelic Rock", "Symphonic Rock", "Slow Rock", "Big Band", "Chorus", "Easy Listening", "Acoustic",
        "Humour", "Speech", "Chanson", "Opera", "Chamber Music", "Sonata", "Symphony", "Booty Bass", "Primus", "Porn Groove",
        "Satire", "Slow Jam", "Club", "Tango", "Samba", "Folklore", "Ballad", "Power Ballad", "Rhythmic Soul", "Freestyle",
        "Duet", "Punk Rock", "Drum Solo", "A Cappella", "Euro-House", "Dance Hall",
    )

    /** Curated list shown in the genre picker (user can also type a custom genre). */
    val PICKER: List<String> = listOf(
        "流行 Pop", "摇滚 Rock", "民谣 Folk", "古风 Chinese Traditional", "说唱 Hip-Hop", "电子 Electronic",
        "古典 Classical", "爵士 Jazz", "R&B", "轻音乐 Easy Listening", "影视原声 Soundtrack", "纯音乐 Instrumental",
        "儿歌 Children", "戏曲 Opera", "广播剧 Audio Drama", "金属 Metal", "朋克 Punk", "蓝调 Blues", "乡村 Country",
        "雷鬼 Reggae", "拉丁 Latin", "新世纪 New Age", "氛围 Ambient", "舞曲 Dance", "K-Pop", "J-Pop", "动漫 Anime",
        "游戏 Game", "宗教 Gospel", "世界音乐 World", "有声书 Audiobook", "播客 Podcast", "其他 Other",
    )
}
