package eu.elraro.babytracker.data

import eu.elraro.babytracker.data.remote.ServerUrl
import eu.elraro.babytracker.data.remote.ServerUrl.Parsed
import org.junit.Assert.assertEquals
import org.junit.Test

class ServerUrlTest {
    private fun parse(
        input: String,
        allowCleartext: Boolean = false,
    ) = ServerUrl.parse(input, allowCleartext)

    @Test
    fun `adds https and removes trailing slashes`() {
        assertEquals(Parsed.Valid("https://baby.example.com"), parse(" baby.example.com/ "))
        assertEquals(Parsed.Valid("https://baby.example.com:8443"), parse("https://baby.example.com:8443"))
        assertEquals(Parsed.Valid("https://example.com/baby"), parse("example.com/baby//"))
    }

    @Test
    fun `plain http is only accepted when cleartext is allowed`() {
        val lan = "http://192.168.1.10:8080"
        assertEquals(Parsed.Insecure, parse(lan, allowCleartext = false))
        assertEquals(Parsed.Valid(lan), parse(lan, allowCleartext = true))
    }

    @Test
    fun `rejects what is not a server address`() {
        listOf(
            "",
            "   ",
            "ftp://example.com",
            "https://",
            "https://user@example.com",
            "example.com?x=1",
            "exa mple.com",
        ).forEach { assertEquals(it, Parsed.Invalid, parse(it, allowCleartext = true)) }
    }

    @Test
    fun `api base keeps a path prefix`() {
        assertEquals("https://example.com/baby/api/v1/", ServerUrl.apiBase("https://example.com/baby").toString())
    }
}
