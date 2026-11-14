package dev.lyra.music.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineMetadataTest {

    @Test fun netease_candidates() {
        val r = MetadataParsers.netease("""{"result":{"songs":[
          {"id":186016,"name":"晴天","dt":269000,"publishTime":1059580800000,"ar":[{"name":"周杰伦"}],
           "al":{"name":"叶惠美","picUrl":"http://p1.music.126.net/abc==/123.jpg"}},
          {"id":2,"name":"坏封面","ar":[],"al":{"name":"x","picUrl":"http://evil.example.com/a.jpg"},"publishTime":0}
        ]},"code":200}""")
        assertEquals(2, r.size)
        assertEquals("叶惠美", r[0].album); assertEquals(2003, r[0].year)
        assertEquals("https://p1.music.126.net/abc==/123.jpg?param=800y800", r[0].coverUrl)
        assertNull(r[1].coverUrl); assertNull(r[1].year)
    }

    @Test fun qq_candidates() {
        val r = MetadataParsers.qq("""{"code":0,"data":{"song":{"list":[
          {"songmid":"0039MnYb0qxYhV","songname":"晴天","singer":[{"name":"周杰伦"}],"albumname":"叶惠美",
           "albummid":"000MkMni19ClKG","interval":269,"pubtime":1059580800},
          {"songmid":"abc","songname":"无专辑","singer":[],"albummid":"../x","interval":10}
        ]}}}""")
        assertEquals("https://y.gtimg.cn/music/photo_new/T002R800x800M000000MkMni19ClKG.jpg", r[0].coverUrl)
        assertEquals(2003, r[0].year); assertEquals(269_000L, r[0].durationMs)
        assertNull(r[1].coverUrl)
    }

    @Test fun musicbrainz_candidates_prefer_original_album() {
        val r = MetadataParsers.musicBrainz("""{"created":"x","count":1,"offset":0,"recordings":[
          {"id":"0a8e8d55-4b83-4f8a-9732-fbb5ded9f344","score":100,"title":"Hello","length":295000,
           "artist-credit":[{"name":"Adele","artist":{"id":"x","name":"Adele"}}],
           "first-release-date":"2015-10-23",
           "releases":[
             {"id":"11111111-1111-1111-1111-111111111111","title":"Now 92","date":"2015-11-20","release-group":{"primary-type":"Album","secondary-types":["Compilation"]}},
             {"id":"22222222-2222-2222-2222-222222222222","title":"25","date":"2015-11-20","release-group":{"primary-type":"Album"}}],
           "tags":[{"count":1,"name":"soul"},{"count":5,"name":"pop"}]},
          {"id":"not-a-uuid","title":"x"}]}""")
        val c = r.single()
        assertEquals("25", c.album); assertEquals("Adele", c.artist); assertEquals(2015, c.year)
        assertEquals("pop", c.genre); assertEquals(295_000L, c.durationMs)
        assertEquals("https://coverartarchive.org/release/22222222-2222-2222-2222-222222222222/front-500", c.coverUrl)
        assertTrue(MetadataParsers.musicBrainz("nope").isEmpty())
    }

    @Test fun musicbrainz_query_escaping() {
        assertEquals("recording:\"What\\?\" AND artist:\"AC\\/DC\"", MetadataParsers.musicBrainzQuery("What?", "AC/DC"))
        assertEquals("recording:\"a \\\"b\\\"\"", MetadataParsers.musicBrainzQuery("a \"b\"", ""))
    }

    @Test fun image_url_allow_list() {
        assertTrue(MetadataParsers.isAllowedImageHost("ia800.us.archive.org"))
        assertTrue(MetadataParsers.isAllowedImageHost("P2.music.126.net"))
        assertFalse(MetadataParsers.isAllowedImageHost("evilarchive.org"))
        assertFalse(MetadataParsers.isAllowedImageHost("archive.org.evil.com"))
        assertNull(MetadataParsers.safeImageUrl("javascript:alert(1)"))
        assertNull(MetadataParsers.safeImageUrl("https://y.gtimg.cn/a.jpg?x=1"))
        assertNull(MetadataParsers.safeImageUrl("https://user@y.gtimg.cn/a.jpg"))
    }

    private val cur = SongTags("track01", "", "", "", null, null)
    private val cand = MetadataCandidate(OnlineSource.QQ, "1", "晴天", "周杰伦", "叶惠美", 2003, null, 269_000, "https://y.gtimg.cn/x.jpg")

    @Test fun merge_only_selected_and_known_fields() {
        val m = MetadataMerge.merge(cur, cand, setOf(MetaField.TITLE, MetaField.ARTIST, MetaField.GENRE))
        assertEquals(SongTags("晴天", "周杰伦", "", "周杰伦", null, null), m)
        val all = MetadataMerge.merge(cur.copy(genre = "Pop"), cand, MetaField.entries.toSet())
        assertEquals(SongTags("晴天", "周杰伦", "叶惠美", "周杰伦", 2003, "Pop"), all)
        // Existing album artist survives when the album does not change.
        val keep = MetadataMerge.merge(SongTags("t", "a", "叶惠美", "群星", null, null), cand, setOf(MetaField.ARTIST, MetaField.ALBUM))
        assertEquals("群星", keep.albumArtist)
        // Blank candidate values never erase.
        val blank = MetadataMerge.merge(SongTags("t", "a", "b", "a", 1999, "g"), cand.copy(artist = "", album = "", year = null), MetaField.entries.toSet())
        assertEquals(SongTags("晴天", "a", "b", "a", 1999, "g"), blank)
    }

    @Test fun missing_fields_never_include_filled_ones() {
        assertEquals(setOf(MetaField.ARTIST, MetaField.ALBUM, MetaField.YEAR, MetaField.COVER), MetadataMerge.missingFields(cur, false, cand))
        assertEquals(emptySet<MetaField>(), MetadataMerge.missingFields(SongTags("t", "a", "b", "a", 2000, "g"), true, cand))
        assertEquals(setOf(MetaField.COVER), MetadataMerge.missingFields(SongTags("t", "a", "b", "a", 2000, "g"), false, cand))
    }
}
