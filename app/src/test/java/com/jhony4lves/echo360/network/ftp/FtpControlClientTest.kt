package com.jhony4lves.echo360.network.ftp

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.BufferedReader
import java.io.StringReader

class FtpControlClientTest {
    @Test
    fun `reads single line reply`() {
        val reply = FtpReplyParser.read(
            BufferedReader(StringReader("220 FtpDll Ready\n")),
        )

        assertEquals(220, reply.code)
        assertEquals(listOf("220 FtpDll Ready"), reply.lines)
    }

    @Test
    fun `reads multiline FEAT reply until matching code`() {
        val reply = FtpReplyParser.read(
            BufferedReader(
                StringReader(
                    "211-Extensions supported:\n XCRC filename\n UTF8\n211 END\n",
                ),
            ),
        )

        assertEquals(211, reply.code)
        assertEquals(4, reply.lines.size)
        assertEquals("211 END", reply.lines.last())
    }
}
