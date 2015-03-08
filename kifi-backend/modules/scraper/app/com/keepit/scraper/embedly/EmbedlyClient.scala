package com.keepit.scraper.embedly

import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger
import com.keepit.common.net.WebServiceUtils
import com.keepit.common.performance._

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import com.google.inject.{ Inject, Singleton }
import com.keepit.common.concurrent.RetryFuture
import com.keepit.common.logging.Logging
import com.keepit.common.service.RequestConsolidator
import com.keepit.common.strings.UTF8
import play.api.http.Status
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.Json
import play.api.libs.ws.{ WSResponse, WS }
import play.api.Play.current
import com.keepit.common.healthcheck.AirbrakeNotifier

trait EmbedlyClient {
  def getEmbedlyInfo(url: String): Future[Option[EmbedlyInfo]]
}

@Singleton
class EmbedlyClientImpl @Inject() (airbrake: AirbrakeNotifier) extends EmbedlyClient with Logging {

  private val embedlyKey = "e46ecae2611d4cb29342fddb0e666a29"

  private def embedlyUrl(url: String): String = s"http://api.embed.ly/1/extract?key=$embedlyKey&url=${URLEncoder.encode(url, UTF8)}"

  private def parseEmbedlyInfo(resp: WSResponse): Option[EmbedlyInfo] = {
    resp.status match {
      case Status.OK =>
        val js = Json.parse(resp.body) // resp.json has some issues
        val extractRespOpt = js.validate[EmbedlyInfo].fold(
          valid = (res => Some(res)),
          invalid = (e => {
            log.info(s"Failed to parse JSON: body=${resp.body} errors=${e.toString}")
            None
          }))
        extractRespOpt
      case _ => None
    }
  }

  override def getEmbedlyInfo(url: String): Future[Option[EmbedlyInfo]] = {
    val watch = Stopwatch(s"embedly infor for $url")
    val apiUrl = embedlyUrl(url)
    fetchExtendedInfoConsolidater(apiUrl) { urlString =>
      val request = WS.url(urlString).withRequestTimeout(120000)
      WebServiceUtils.getWithRetry(request, 2) map { resp =>
        val info = parseEmbedlyInfo(resp)
        watch.stop()
        info
      } recover {
        case t: Throwable =>
          watch.stop()
          log.info(s"Caught exception while invoking ($apiUrl): Exception=$t; cause=${t.getCause}")
          airbrake.notify("Failed getting embedly info", t)
          None
      }
    }
  }

  private val fetchExtendedInfoConsolidater = new RequestConsolidator[String, Option[EmbedlyInfo]](2 minutes)
}

class DevEmbedlyClient extends EmbedlyClient {
  def getEmbedlyInfo(url: String): Future[Option[EmbedlyInfo]] = Future.successful(None)
}
