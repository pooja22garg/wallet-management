package org.example.walletmanagement.filter

import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader

/**
 * Reads the request body eagerly into memory so the idempotency filter can hash it,
 * while still letting the downstream controller read it again.
 */
class CachedBodyHttpServletRequest(request: HttpServletRequest) : HttpServletRequestWrapper(request) {

    val body: ByteArray = request.inputStream.readBytes()

    override fun getInputStream(): ServletInputStream {
        val source = ByteArrayInputStream(body)
        return object : ServletInputStream() {
            override fun read(): Int = source.read()
            override fun isFinished(): Boolean = source.available() == 0
            override fun isReady(): Boolean = true
            override fun setReadListener(listener: ReadListener?) {}
        }
    }

    override fun getReader(): BufferedReader =
        BufferedReader(InputStreamReader(ByteArrayInputStream(body)))
}
