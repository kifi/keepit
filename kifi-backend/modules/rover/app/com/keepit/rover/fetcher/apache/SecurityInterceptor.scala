package com.keepit.rover.fetcher.apache

import com.keepit.common.logging.Logging
import com.keepit.common.net.URI
import org.apache.http.protocol.HttpContext
import org.apache.http.{ HttpException, HttpRequest, HttpRequestInterceptor }

class SecurityInterceptor extends HttpRequestInterceptor with Logging {
  def process(request: HttpRequest, context: HttpContext): Unit = {
    URI.parse(request.getRequestLine.getUri).toOption.flatMap { uri =>
      uri.host.map { host =>
        if (SecurityInterceptor.bannedHosts.exists(host.toString.startsWith)) {
          //throw new HttpException(s"Tried to scrape banned domain ${host.toString}")
          log.error(s"[SecurityInterceptor] Blocking ${request.getRequestLine.getUri}.")
        }
        true
      }
    }.getOrElse {
      log.warn(s"[SecurityInterceptor] Couldn't parse ${request.getRequestLine.getUri}. Allowing.")
    }
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
