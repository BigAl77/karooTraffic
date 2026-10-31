package com.mybiketraffic.karootraffic

import com.mybiketraffic.karootraffic.upload.MyBikeTrafficUploader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MyBikeTrafficUploaderTest {

    @Test
    fun `parseImportPageHtml extracts CSRF token, hidden fields, action, and file field name`() {
        val sampleHtml = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta name="csrf-param" content="authenticity_token" />
                <meta name="csrf-token" content="secret-csrf-12345" />
            </head>
            <body>
                <form action="/rides/import" enctype="multipart/form-data" method="post">
                    <input type="hidden" name="authenticity_token" value="form-token-67890" />
                    <input type="file" name="ride[fit_file]" />
                    <input type="submit" name="commit" value="Import" />
                </form>
            </body>
            </html>
        """.trimIndent()

        val parsed = MyBikeTrafficUploader.parseImportPageHtml(
            sampleHtml,
            "https://www.mybiketraffic.com/rides/import"
        )

        assertFalse(parsed.requiresLogin)
        assertEquals("https://www.mybiketraffic.com/rides/import", parsed.actionUrl)
        assertEquals("ride[fit_file]", parsed.fileFieldName)
        assertEquals("secret-csrf-12345", parsed.csrfMetaToken)
        assertEquals("form-token-67890", parsed.hiddenFields["authenticity_token"])
    }

    @Test
    fun `parseImportPageHtml detects login redirect`() {
        val loginHtml = "<html><body><h1>Sign in</h1></body></html>"
        val parsed = MyBikeTrafficUploader.parseImportPageHtml(
            loginHtml,
            "https://www.mybiketraffic.com/users/sign_in"
        )
        assertTrue(parsed.requiresLogin)
    }

    @Test
    fun `buildMultipartBody formats hidden fields and binary FIT payload`() {
        val fakeFit = byteArrayOf(0x0E, 0x10, 0x2E, 0x46, 0x49, 0x54)
        val body = MyBikeTrafficUploader.buildMultipartBody(
            boundary = "TestBoundary123",
            hiddenFields = mapOf("authenticity_token" to "abc123token"),
            fileFieldName = "file",
            fileName = "ride_2026.fit",
            fileBytes = fakeFit
        )
        val bodyStr = String(body, Charsets.ISO_8859_1)

        assertTrue(bodyStr.contains("--TestBoundary123\r\nContent-Disposition: form-data; name=\"authenticity_token\"\r\n\r\nabc123token\r\n"))
        assertTrue(bodyStr.contains("Content-Disposition: form-data; name=\"file\"; filename=\"ride_2026.fit\""))
        assertTrue(bodyStr.contains(".FIT"))
        assertTrue(bodyStr.endsWith("--TestBoundary123--\r\n"))
    }
}
