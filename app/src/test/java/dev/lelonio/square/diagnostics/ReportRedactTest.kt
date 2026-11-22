package dev.lelonio.square.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class ReportRedactTest {

    @Test
    fun subsonicQueryAuthIsHidden() {
        val line = "GET http://nas:4533/rest/stream.view?id=42&u=admin&t=0123abcd&s=salt99&v=1.16.1&c=Square"
        assertEquals(
            "GET http://nas:4533/rest/stream.view?id=42&u=<redacted>&t=<redacted>&s=<redacted>&v=1.16.1&c=Square",
            Report.redact(line, emptyList()),
        )
        assertEquals("ping.view?p=<redacted>", Report.redact("ping.view?p=hunter2", emptyList()))
    }

    @Test
    fun bridgeJsonAuthIsHidden() {
        assertEquals(
            """{"u":"<redacted>","t":"<redacted>","s":"<redacted>","action":"add"}""",
            Report.redact("""{"u":"admin","t":"abc","s":"xyz","action":"add"}""", emptyList()),
        )
    }
}
