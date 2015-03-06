package com.keepit.scraper.embedly

import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger
import com.keepit.common.performance._
import com.keepit.common.store.S3URIImageStore
import org.apache.commons.lang3.RandomStringUtils

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import com.google.inject.{ Inject, Singleton }
import com.keepit.common.concurrent.RetryFuture
import com.keepit.common.logging.Logging
import com.keepit.common.service.RequestConsolidator
import com.keepit.common.strings.UTF8
import com.keepit.model.{ ImageFormat, ImageInfo, NormalizedURI }
import play.api.http.Status
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.Json
import play.api.libs.ws.{ WSResponse, WS }
import com.keepit.common.db.Id
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
      fetchWithRetry(apiUrl, 120000) map { resp =>
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

  private def fetchWithRetry(url: String, timeout: Int): Future[WSResponse] = {
    val count = new AtomicInteger()
    val resolver: PartialFunction[Throwable, Boolean] = {
      case t: Throwable =>
        count.getAndIncrement
        // random delay or backoff
        log.info(s"[fetchWithRetry($url)] attempt#(${count.get}) failed with $t") // intermittent embedly/site failures
        true
    }
    RetryFuture(attempts = 2, resolver) {
      WS.url(url).withRequestTimeout(timeout).get()
    }
  }
}

class DevEmbedlyClient extends EmbedlyClient {
  def getEmbedlyInfo(url: String): Future[Option[EmbedlyInfo]] = Future.successful(None)
}
