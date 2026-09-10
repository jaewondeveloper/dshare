package com.dshare.app

import fi.iki.elonen.NanoHTTPD

/**
 * Plain HTTP listener whose only job is redirecting to the real HTTPS server.
 * Lets a user type the address without "https://" (which a browser resolves to
 * http:// by default) and still land on a working, secure connection - the HTTPS
 * port can't itself accept a plain HTTP request since TLS owns the whole socket.
 */
class RedirectServer(private val httpsPort: Int) : NanoHTTPD(0) {

    override fun serve(session: IHTTPSession): Response {
        val host = session.headers["host"]?.substringBefore(":") ?: "localhost"
        val location = "https://$host:$httpsPort${session.uri}"
        return newFixedLengthResponse(Response.Status.FOUND, MIME_PLAINTEXT, "Redirecting to $location").apply {
            addHeader("Location", location)
        }
    }
}
