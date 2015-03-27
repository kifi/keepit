package com.keepit.rover.article

import java.net.URLEncoder

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.net.{ WebService, WebServiceUtils }
import com.keepit.common.performance._
import com.keepit.common.service.RequestConsolidator
import com.keepit.common.strings.UTF8
import com.keepit.common.time.{ Clock, _ }
import com.keepit.rover.article.content.EmbedlyContent
import play.api.libs.json.Json
import play.api.libs.ws.WSResponse
import com.keepit.common.core._

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ ExecutionContext, Future }

object EmbedlyArticleFetcher {
  val key = "e46ecae2611d4cb29342fddb0e666a29"
  val timeout = 120000 // ms
  val attempts = 2

  def embedlyExtractAPI(originalUrl: String): String = s"http://api.embed.ly/1/extract?key=$key&url=${URLEncoder.encode(originalUrl, UTF8)}"
}

@Singleton
class EmbedlyArticleFetcher @Inject() (
    ws: WebService,
    airbrake: AirbrakeNotifier,
    clock: Clock,
    implicit val executionContext: ExecutionContext) extends ArticleFetcher[EmbedlyArticle] with Logging {

  import com.keepit.rover.article.EmbedlyArticleFetcher._

  private val consolidate = new RequestConsolidator[String, EmbedlyArticle](2 minutes)

  def fetch(request: ArticleFetchRequest[EmbedlyArticle]): Future[Option[EmbedlyArticle]] = {
    consolidate(request.url)(doFetch).imap(Some(_))
  }

  private def doFetch(url: String): Future[EmbedlyArticle] = {
    val watch = Stopwatch(s"Fetching Embedly content for $url")
    val request = {
      val embedlyUrl = embedlyExtractAPI(url)
      ws.url(embedlyUrl).withRequestTimeout(timeout)
    }
    WebServiceUtils.getWithRetry(request, attempts).map { response =>
      watch.logTimeWith(s"Response: $response")
      try {
        val json = Json.parse(response.body) // issues with resp.json ?
        val content = new EmbedlyContent(json)
        new EmbedlyArticle(url, clock.now(), content)
      } catch {
        case error: Throwable =>
          throw new InvalidEmbedlyResponseException(url, response, error)
      }
    }
  }
}

case class InvalidEmbedlyResponseException(url: String, response: WSResponse, error: Throwable)
  extends Throwable(s"Error: $error \n Original url: $url \n Response body: ${response.body}")
