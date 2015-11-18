package com.keepit.rover.fetcher.apache

import com.keepit.common.logging.Logging
import com.keepit.common.net.URI
import org.apache.http.client.methods.HttpUriRequest
import org.apache.http.protocol.HttpContext
import org.apache.http.{ HttpException, HttpRequest, HttpRequestInterceptor }

class SecurityInterceptor extends HttpRequestInterceptor with Logging {
  def process(request: HttpRequest, context: HttpContext): Unit = {
    request match {
      case uriRequest: HttpUriRequest =>
        Option(uriRequest.getURI).flatMap(u => Option(u.getHost)).map(_.trim).map { host =>
          if (SecurityInterceptor.bannedHosts.exists(host.startsWith)) {
            //throw new HttpException(s"Tried to scrape banned domain ${host.toString}")
            log.error(s"[SecurityInterceptor] Blocking $host ${request.getRequestLine.getUri}")
          } else {
            log.info(s"[SecurityInterceptr] Allowing $host ${request.getRequestLine.getUri}")
          }
          true
        }.getOrElse {
          log.warn(s"[SecurityInterceptor] Couldn't parse ${request.toString} Allowing.")
        }
      case _ =>
        log.info(s"[SecurityInterceptor] No clue what to do with ${request.toString} ${request.getClass.getCanonicalName}")
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
