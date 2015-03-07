package com.keepit.rover.article

import java.net.URLEncoder

import com.google.inject.{Inject, Singleton}
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.net.{WebService, WebServiceUtils}
import com.keepit.common.performance._
import com.keepit.common.service.RequestConsolidator
import com.keepit.common.strings.UTF8
import com.keepit.common.time.{Clock, _}
import play.api.libs.json.Json
import play.api.libs.ws.WSResponse

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}

trait EmbedlyArticleFetcher extends ArticleFetcher[EmbedlyArticle]

object EmbedlyArticleFetcher {
  val key = "e46ecae2611d4cb29342fddb0e666a29"
  val timeout = 120000 // ms
  val attempts = 2
}

@Singleton
class EmbedlyArticleFetcherImpl @Inject() (
    ws: WebService,
    airbrake: AirbrakeNotifier,
    clock: Clock,
    implicit val executionContext: ExecutionContext) extends EmbedlyArticleFetcher with Logging {

  import com.keepit.rover.article.EmbedlyArticleFetcher._

  private val fetchConsolidate = new RequestConsolidator[String, EmbedlyArticle](2 minutes)

  def fetch(url: String): Future[EmbedlyArticle] = fetchConsolidate(url) { originalUrl =>
    val watch = Stopwatch(s"Fetching Embedly content for $originalUrl")
    val request = {
      val embedlyUrl = embedlyExtractAPI(originalUrl)
      ws.url(embedlyUrl).withRequestTimeout(timeout)
    }
    WebServiceUtils.getWithRetry(request, attempts).map { response =>
      watch.logTimeWith(s"Response: $response")
      try {
        val json = Json.parse(response.body) // issues with resp.json ?
        new EmbedlyArticle(originalUrl, clock.now(), json)
      } catch {
        case error: Throwable =>
          throw new InvalidEmbedlyResponseException(originalUrl, response, error)
      }
    }
  }

  private def embedlyExtractAPI(originalUrl: String): String = s"http://api.embed.ly/1/extract?key=$key&url=${URLEncoder.encode(originalUrl, UTF8)}"
}

case class InvalidEmbedlyResponseException(url: String, response: WSResponse, error: Throwable)
  extends Throwable(s"Error: $error \n Original url: $url \n Response body: ${response.body}")
