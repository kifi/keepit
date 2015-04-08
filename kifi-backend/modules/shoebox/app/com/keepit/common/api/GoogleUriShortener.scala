package com.keepit.common.api

import com.google.inject.{ ImplementedBy, Inject }
import com.keepit.common.logging.Logging
import com.keepit.common.net.{ CallTimeouts, DirectUrl, HttpClient }
import play.api.libs.json.{ Json, JsObject }

import scala.concurrent.{ ExecutionContext, Future }

/**
 * Example from curl:
 * $ curl https://www.googleapis.com/urlshortener/v1/url\?key\=AIzaSyBFTnLEV46_za5dQqGZxSymNIQ0rKFiKN8 \
 * -H 'Content-Type: application/json' \
 * -d '{"longUrl": "https://www.kifi.com/eishay/most-popular-ted-talks"}'
 * {
 * "kind": "urlshortener#url",
 * "id": "http://goo.gl/uxgdgy",
 * "longUrl": "https://www.kifi.com/eishay/most-popular-ted-talks"
 * }
 */

object GoogleUriShortener {
  private val Key = "AIzaSyBFTnLEV46_za5dQqGZxSymNIQ0rKFiKN8"
  val RequestUrl = DirectUrl(s"""https://www.googleapis.com/urlshortener/v1/url?key=$Key""")
}

@ImplementedBy(classOf[GoogleUriShortener])
trait UriShortener {
  def shorten(uri: String): Future[String]
}

class GoogleUriShortener @Inject() (
    implicit val executionContext: ExecutionContext,
    httpClient: HttpClient) extends Logging with UriShortener {

  def shorten(uri: String): Future[String] = {
    if (uri.length < 20) {
      log.warn(s"no need to shorten [$uri]")
      Future.successful(uri)
    } else {
      val payload = Json.obj("longUrl" -> uri)
      httpClient
        .withTimeout(CallTimeouts(Some(60000), Some(60000)))
        .withHeaders("Content-Type" -> "application/json")
        .postFuture(GoogleUriShortener.RequestUrl, payload).map { res =>
          val shortUrl = (res.json \ "id").as[String]
          log.info(s"shorten [$uri] to [$shortUrl]")
          shortUrl
        }
    }
  }
}
