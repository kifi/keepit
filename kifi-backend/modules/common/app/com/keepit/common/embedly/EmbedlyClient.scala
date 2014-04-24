package com.keepit.common.embedly

import com.keepit.model._
import scala.concurrent._
import scala.concurrent.Future
import play.api.http.Status
import play.api.libs.json._
import com.keepit.common.service.RequestConsolidator
import com.keepit.common.db.Id
import play.api.libs.functional.syntax._
import scala.Some
import play.api.libs.ws.{WS, Response}
import java.util.concurrent.atomic.AtomicInteger
import com.keepit.common.concurrent.RetryFuture
import com.keepit.common.mail.{EmailAddresses, ElectronicMail}
import com.keepit.common.logging.Logging
import scala.concurrent.duration._
import com.keepit.common.healthcheck.SystemAdminMailSender
import com.google.inject.Inject
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import java.net.URLEncoder
import com.keepit.common.strings._
import play.api.libs.ws.Response
import scala.Some
import play.api.Play
import play.api.Play.current
import java.io.InputStream
import scala.util.{Success, Failure, Try}
import com.keepit.common.store.{ImageSize, ImageUtils}
import javax.imageio.ImageIO
import java.awt.image.BufferedImage
import com.keepit.common.images.ImageFetcher
import scala.collection.generic.FilterMonadic

case class EmbedlyImage(
  url:String,
  caption:Option[String] = None,
  width:Option[Int]      = None,
  height:Option[Int]     = None,
  size:Option[Int]       = None) extends ImageGenericInfo {
  def toImageInfoWithPriority(nuriId:Id[NormalizedURI], priority: Option[Int]):ImageInfo = ImageInfo(uriId = nuriId, url = Some(this.url), caption = this.caption, width = this.width, height = this.height, size = this.size, provider = Some(ImageProvider.EMBEDLY), priority = priority)
  implicit def toImageInfo(nuriId:Id[NormalizedURI]):ImageInfo = toImageInfoWithPriority(nuriId, None)
}

object EmbedlyImage {
  implicit val format = (
    (__ \ 'url).format[String] and
    (__ \ 'caption).formatNullable[String] and
    (__ \ 'width).formatNullable[Int] and
    (__ \ 'height).formatNullable[Int] and
    (__ \ 'size).formatNullable[Int]
    )(EmbedlyImage.apply _, unlift(EmbedlyImage.unapply))
}

case class EmbedlyExtractResponse(
  originalUrl:String,
  url:Option[String],
  title:Option[String],
  description:Option[String],
  content:Option[String],
  safe:Option[Boolean],
  lang:Option[String],
  faviconUrl:Option[String],
  images:Seq[EmbedlyImage]) extends PageGenericInfo with PageSafetyInfo with PageMediaInfo {
  implicit def toPageInfo(nuriId:Id[NormalizedURI]):PageInfo =
    PageInfo(
      id = None,
      uriId = nuriId,
      title = this.title,
      description = this.description,
      safe = this.safe,
      lang = this.lang,
      faviconUrl = (this.faviconUrl.collect{ case f:String if f.startsWith("http") => f }) // embedly bug
    )
}

object EmbedlyExtractResponse {
  val EMPTY = EmbedlyExtractResponse("", None, None, None, None, None, None, None, Seq.empty[EmbedlyImage])

  implicit val format = (
    (__ \ 'original_url).format[String] and
    (__ \ 'url).formatNullable[String] and
    (__ \ 'title).formatNullable[String] and
    (__ \ 'description).formatNullable[String] and
    (__ \ 'content).formatNullable[String] and
    (__ \ 'safe).formatNullable[Boolean] and
    (__ \ 'language).formatNullable[String] and
    (__ \ 'favicon_url).formatNullable[String] and
    (__ \ 'images).format[Seq[EmbedlyImage]]
    )(EmbedlyExtractResponse.apply _, unlift(EmbedlyExtractResponse.unapply))
}

class EmbedlyClient @Inject() extends Logging {

  private val embedlyKey = "e46ecae2611d4cb29342fddb0e666a29"

  def embedlyUrl(url: String, key: String = embedlyKey) = s"http://api.embed.ly/1/extract?key=$key&url=${URLEncoder.encode(url, UTF8)}"

  def getAllImageInfo(uri: NormalizedURI, size: ImageSize): Future[Seq[ImageInfo]] = {
    uri.id map { nUriId =>
      getExtractResponse(uri.url) map { result =>
        result map {response =>
          response.images.zipWithIndex flatMap { case (embedlyImage, priority) =>
            Some(embedlyImage.toImageInfoWithPriority(nUriId, Some(priority)))
          }
        } getOrElse Seq()
      }
    } getOrElse future { Seq() }
  }

  def getExtractResponse(uri:NormalizedURI):Future[Option[EmbedlyExtractResponse]] =
    getExtractResponse(uri.url, Some(uri))

  def getExtractResponse(url:String, uriOpt:Option[NormalizedURI] = None):Future[Option[EmbedlyExtractResponse]] = {
    val apiUrl = embedlyUrl(url)
    val loggingHint = uriOpt match {
      case Some(uri) => s"(${uri},${url})"
      case None => url
    }
    fetchPageInfoConsolidator(apiUrl) { urlString =>
      fetchWithRetry(apiUrl, 120000) map { resp =>
        log.info(s"[getExtractResponse${loggingHint}] resp.status=${resp.statusText} body=${resp.body}")
        resp.status match {
          case Status.OK =>
            val js = Json.parse(resp.body) // resp.json has some issues
          val extractRespOpt = js.validate[EmbedlyExtractResponse].fold(
              valid = (res => Some(res)),
              invalid = (e => {
                log.info(s"[getExtractResponse${loggingHint}] Failed to parse JSON: body=${resp.body} errors=${e.toString}")
                None
              })
            )
            log.info(s"[getExtractResponse${loggingHint}] extractRespOpt=$extractRespOpt")
            extractRespOpt
          case _ => // todo(ray): need better handling of error codes
            log.info(s"[getExtractResponse${loggingHint}] ERROR while invoking ($apiUrl): status=${resp.status} body=${resp.body}")
            None
        }
      } recover { case t:Throwable =>
        log.info(s"[getExtractResponse${loggingHint}] Caught exception while invoking ($apiUrl): Exception=$t; cause=${t.getCause}")
        None
      }
    }
  }

  private val fetchPageInfoConsolidator = new RequestConsolidator[String,Option[EmbedlyExtractResponse]](2 minutes)

  private def fetchWithRetry(url:String, timeout:Int):Future[Response] = {
    val count = new AtomicInteger()
    val resolver:PartialFunction[Throwable, Boolean] = { case t:Throwable =>
      count.getAndIncrement
      // random delay or backoff
      log.info(s"[fetchWithRetry($url)] attempt#(${count.get}) failed with $t") // intermittent embedly/site failures
      true
    }
    RetryFuture(attempts = 2, resolver){
      WS.url(url).withRequestTimeout(timeout).get()
    }
  }
}
