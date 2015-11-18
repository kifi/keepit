package com.keepit.rover.fetcher.apache

import com.keepit.common.logging.Logging
import com.keepit.common.net.URI
import org.apache.http.HttpHeaders._
import org.apache.http.client.methods.HttpUriRequest
import org.apache.http.protocol.HttpContext
import org.apache.http._

trait SecurityInterceptor extends Logging {
  protected def process(host: String): Boolean = {
    val trimmed = host.trim
    if (SecurityInterceptor.bannedHosts.exists(trimmed.startsWith)) {
      //throw new HttpException(s"Tried to scrape banned domain ${host.toString}")
      log.error(s"[SecurityInterceptor] Blocking $trimmed")
      true
    } else {
      log.info(s"[SecurityInterceptr] Allowing $trimmed")
      false
    }
  }
}

class RequestSecurityInterceptor extends HttpRequestInterceptor with SecurityInterceptor {
  def process(request: HttpRequest, context: HttpContext): Unit = {
    request match {
      case uriRequest: HttpUriRequest =>
        Option(uriRequest.getURI).flatMap(u => Option(u.getHost)).map(process).getOrElse {
          val deets = {
            uriRequest.getClass.getCanonicalName + "  " + uriRequest.getURI.toString + " " + uriRequest.getAllHeaders.map(h => h.getName -> h.getValue).toList.mkString(", ")
          }
          log.warn(s"[SecurityInterceptor-2] Couldn't parse ${request.toString} Allowing. $deets")
        }
      case _ =>
    }
  }
}

class ResponseSecurityInterceptor extends HttpResponseInterceptor with SecurityInterceptor {
  def process(response: HttpResponse, context: HttpContext): Unit = {
    val loc = Option(response.getFirstHeader(LOCATION)).map(_.getValue)
    val host = Option(response.getFirstHeader(HOST)).map(_.getValue)
    loc.orElse(host).map(process).getOrElse {
      val deets = {
        response.getClass.getCanonicalName + "  " + response.getAllHeaders.map(h => h.getName -> h.getValue).toList.mkString(", ")
      }

      log.warn(s"[SecurityInterceptor-1] Couldn't parse ${response.toString} Allowing. $deets")
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
