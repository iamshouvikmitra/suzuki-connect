package dev.mrwick.redline.gnav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedLinkParserTest {

    @Test fun `share sheet text with a short link is a ShortLink`() {
        val t = SharedLinkParser.parseSharedText("Third Wave Coffee\nhttps://maps.app.goo.gl/eD7nSBSuPUpX5sjQ6")
        assertEquals(LinkTarget.ShortLink("https://maps.app.goo.gl/eD7nSBSuPUpX5sjQ6"), t)
    }

    @Test fun `expanded place URL prefers the pin coordinates and picks up the place id`() {
        val url = "https://www.google.com/maps/place/Third+Wave+Coffee/@12.9352,77.6245,17z/data=!3m1!4b1!4m6!3m5!1s0x3bae1450cf3b5f0f:0x0!8m2!3d12.9351234!4d77.6247654!16s%2Fg%2F11abc!19sChIJN1t_tDeuEmsRUsoyG83frY4?entry=ttu"
        val t = SharedLinkParser.parseUrl(url) as LinkTarget.Coordinates
        assertEquals(12.9351234, t.lat, 1e-9)
        assertEquals(77.6247654, t.lng, 1e-9)
        assertEquals("ChIJN1t_tDeuEmsRUsoyG83frY4", t.placeId)
        assertEquals("Third Wave Coffee", t.label)
    }

    @Test fun `q param with coordinates`() {
        val t = SharedLinkParser.parseUrl("https://maps.google.com/?q=12.9716,77.5946") as LinkTarget.Coordinates
        assertEquals(12.9716, t.lat, 1e-9); assertEquals(77.5946, t.lng, 1e-9)
    }

    @Test fun `maps URLs API with query and place id`() {
        val t = SharedLinkParser.parseUrl("https://www.google.com/maps/search/?api=1&query=Cubbon+Park&query_place_id=ChIJabc123") as LinkTarget.PlaceId
        assertEquals("ChIJabc123", t.placeId); assertEquals("Cubbon Park", t.label)
    }

    @Test fun `text-only search URL becomes a Query`() {
        assertEquals(LinkTarget.Query("Cubbon Park Bengaluru"), SharedLinkParser.parseUrl("https://www.google.com/maps/search/Cubbon+Park+Bengaluru/"))
        assertEquals(LinkTarget.Query("Koramangala"), SharedLinkParser.parseUrl("https://maps.google.com/?q=Koramangala"))
    }

    @Test fun `dir URL uses the last leg as the destination`() {
        val t = SharedLinkParser.parseUrl("https://www.google.com/maps/dir/Indiranagar/Whitefield,+Bengaluru/@12.97,77.7,12z/data=!4m2!4m1!3e0")
        assertEquals(LinkTarget.Query("Whitefield, Bengaluru"), t)
    }

    @Test fun `geo URIs`() {
        val a = SharedLinkParser.parseUrl("geo:12.9716,77.5946") as LinkTarget.Coordinates
        assertEquals(12.9716, a.lat, 1e-9)
        val b = SharedLinkParser.parseUrl("geo:0,0?q=12.9,77.6(Home)") as LinkTarget.Coordinates
        assertEquals("Home", b.label)
        assertEquals(LinkTarget.Query("petrol pump"), SharedLinkParser.parseUrl("geo:0,0?q=petrol+pump"))
    }

    @Test fun `viewport centre is used only when the URL names a place`() {
        val t = SharedLinkParser.parseUrl("https://www.google.com/maps/place/Lalbagh/@12.95,77.585,15z")
        assertTrue(t is LinkTarget.Coordinates && t.label == "Lalbagh")
        assertNull(SharedLinkParser.parseUrl("https://www.google.com/maps/@12.95,77.585,15z"))
    }

    @Test fun `non-google URLs and junk are rejected`() {
        assertNull(SharedLinkParser.parseUrl("https://example.com/maps/place/x/@1,2,3z"))
        assertNull(SharedLinkParser.parseSharedText("ok"))
        assertNull(SharedLinkParser.parseSharedText(null))
    }

    @Test fun `plain text without a URL is a Query`() {
        assertEquals(LinkTarget.Query("MG Road Metro Station"), SharedLinkParser.parseSharedText("MG Road Metro Station"))
    }
}
