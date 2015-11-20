package com.keepit.rover.fetcher.apache

import com.keepit.common.logging.Logging
import com.keepit.common.net.URI
import org.apache.http.HttpHeaders._
import org.apache.http.client.methods.{ HttpRequestWrapper, HttpUriRequest }
import org.apache.http.protocol.HttpContext
import org.apache.http._

trait SecurityInterceptor extends Logging {
  def throwIfBannedHost(host: String): Unit = {
    val trimmed = host.trim
    if (SecurityInterceptor.bannedHosts.exists(trimmed.startsWith)) {
      //throw new HttpException(s"Tried to scrape banned domain ${host.toString}")
      log.error(s"[SI] Blocking $trimmed")
    }
  }
}

class RequestSecurityInterceptor extends HttpRequestInterceptor with SecurityInterceptor {
  def process(request: HttpRequest, context: HttpContext): Unit = {
    request match {
      case uriRequest: HttpUriRequest =>
        Option(uriRequest.getFirstHeader("Host")).map(_.getValue).foreach(throwIfBannedHost)
      case _ =>
    }
  }
}

class ResponseSecurityInterceptor extends HttpResponseInterceptor with SecurityInterceptor {
  def process(response: HttpResponse, context: HttpContext): Unit = {
    val loc = Option(response.getFirstHeader(LOCATION)).flatMap(l => URI.parse(l.getValue).toOption).flatMap(_.host).map(_.name)
    val host = Option(response.getFirstHeader(HOST)).map(_.getValue)
    loc.orElse(host).foreach(throwIfBannedHost)
  }
}

object SecurityInterceptor extends SecurityInterceptor {
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
