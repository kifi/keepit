package com.keepit.rover.fetcher.apache

import com.keepit.common.net.URI
import org.apache.http.protocol.HttpContext
import org.apache.http.{ HttpRequest, HttpRequestInterceptor }

class SecurityInterceptor extends HttpRequestInterceptor {
  def process(request: HttpRequest, context: HttpContext): Unit = {
    URI.parse(request.getRequestLine.getUri).toOption.flatMap { uri =>
      uri.host.map { host =>
        SecurityInterceptor.bannedHosts.exists(host.toString.startsWith)
      }
    }
    request
  }
}

object SecurityInterceptor {
  val bannedHosts = Set(
    "10.",
    "169.254.169.254",
    "localhost",
    "127.",
    "0.",
    "2130706433",
    "::1" // not that we support IPv6 or anything
  )
}
