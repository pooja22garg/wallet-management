package org.example.walletmanagement.filter

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.example.walletmanagement.dto.ErrorResponse
import org.example.walletmanagement.entity.IdempotencyStatus
import org.example.walletmanagement.service.CapturedResponse
import org.example.walletmanagement.service.IdempotencyReplayService
import org.example.walletmanagement.service.IdempotencyService
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.ContentCachingResponseWrapper
import tools.jackson.databind.ObjectMapper
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

@Component
class IdempotencyFilter(
    private val idempotencyService: IdempotencyService,
    private val replayService: IdempotencyReplayService,
    private val objectMapper: ObjectMapper
) : OncePerRequestFilter() {

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        if (request.method != "POST") return true
        return !PATH.matches(request.requestURI)
    }

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val key = request.getHeader("Idempotency-Key")
        if (key.isNullOrBlank()) {
            writeError(request, response, HttpStatus.BAD_REQUEST, "Missing Idempotency-Key header")
            return
        }

        val cached = CachedBodyHttpServletRequest(request)
        val userId = extractUserId(request)
        if (userId == null) {
            chain.doFilter(cached, response)
            return
        }
        val endpoint = extractEndpoint(request)
        val requestHash = sha256(cached.body)

        val existing = idempotencyService.find(key, userId)

        if (existing == null) {
            idempotencyService.initiate(key, userId, endpoint, requestHash)
            processAndCapture(cached, response, chain, key, userId)
            return
        }

        if (existing.endpoint != endpoint || existing.requestHash != requestHash) {
            writeError(request, response, HttpStatus.UNPROCESSABLE_ENTITY, "Idempotency key '$key' was reused with different parameters")
            return
        }

        if (existing.expiresAt.isBefore(Instant.now())) {
            idempotencyService.delete(existing)
            idempotencyService.initiate(key, userId, endpoint, requestHash)
            processAndCapture(cached, response, chain, key, userId)
            return
        }

        when (existing.status) {
            IdempotencyStatus.COMPLETED ->
                writeCaptured(response, CapturedResponse(existing.responseStatus ?: 200, existing.responseContentType, existing.responseBody ?: ""))

            // A prior transient (5xx) failure — the client may retry. Reset the same row to
            // IN_PROGRESS and reprocess; storeResponse then updates that row with the result.
            IdempotencyStatus.FAILED -> {
                idempotencyService.markInProgress(key, userId)
                processAndCapture(cached, response, chain, key, userId)
            }

            IdempotencyStatus.IN_PROGRESS -> {
                val rebuilt = replayService.reconstruct(key, userId)
                if (rebuilt != null) {
                    idempotencyService.storeResponse(key, userId, rebuilt.status, rebuilt.contentType, rebuilt.body, IdempotencyStatus.COMPLETED)
                    writeCaptured(response, rebuilt)
                } else {
                    writeError(request, response, HttpStatus.CONFLICT, "Request with idempotency key '$key' is still being processed")
                }
            }
        }
    }

    private fun processAndCapture(request: CachedBodyHttpServletRequest, response: HttpServletResponse, chain: FilterChain, key: String, userId: UUID) {
        //Enables writting response to buffer instead of directly to client, so we can capture it for storage before sending it out.
        val buffered = ContentCachingResponseWrapper(response)
        chain.doFilter(request, buffered)

        val status = buffered.status
        val body = String(buffered.contentAsByteArray, Charsets.UTF_8)
        if (status >= 500) {
            // Separately identifiable for audit/tracking purposes. Next time duplicate request comes in, it will be allowed
            idempotencyService.storeResponse(key, userId, status, buffered.contentType, body, IdempotencyStatus.FAILED)
        } else {
            idempotencyService.storeResponse(key, userId, status, buffered.contentType, body, IdempotencyStatus.COMPLETED)
        }
        buffered.copyBodyToResponse()
    }

    private fun writeCaptured(response: HttpServletResponse, captured: CapturedResponse) {
        response.status = captured.status
        captured.contentType?.let { response.contentType = it }
        response.writer.write(captured.body)
    }

    private fun writeError(request: HttpServletRequest, response: HttpServletResponse, status: HttpStatus, message: String) {
        response.status = status.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.writer.write(
            objectMapper.writeValueAsString(ErrorResponse(status.value(), message, request.requestURI))
        )
    }

    private fun extractUserId(request: HttpServletRequest): UUID? =
        try {
            UUID.fromString(request.requestURI.split("/")[2])
        } catch (_: Exception) {
            null
        }

    private fun extractEndpoint(request: HttpServletRequest): String =
        request.requestURI.substringAfterLast("/")

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        private val PATH = Regex("""^/wallets/[^/]+/(deposit|trade)$""")
    }
}
