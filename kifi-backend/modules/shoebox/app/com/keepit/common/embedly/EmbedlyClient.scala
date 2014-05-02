package com.keepit.common.embedly

import com.keepit.model._
import scala.concurrent._
import scala.concurrent.Future
import play.api.http.Status
import play.api.libs.json._
import com.keepit.common.service.RequestConsolidator
import com.keepit.common.db.Id
import play.api.libs.functional.syntax._
import play.api.libs.ws.WS
import java.util.concurrent.atomic.AtomicInteger
import com.keepit.common.concurrent.RetryFuture
import com.keepit.common.logging.Logging
import scala.concurrent.duration._
import com.google.inject.{ImplementedBy, Singleton, Inject}
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import java.net.URLEncoder
import com.keepit.common.strings._
import play.api.libs.ws.Response
import scala.Some

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

case class EmbedlyInfo(
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

  def buildImageInfo(nUriId: Id[NormalizedURI]) = {
    images.zipWithIndex flatMap { case (embedlyImage, priority) =>
      Some(embedlyImage.toImageInfoWithPriority(nUriId, Some(priority)))
    }
  }
}

object EmbedlyInfo {
  val EMPTY = EmbedlyInfo("", None, None, None, None, None, None, None, Seq.empty[EmbedlyImage])

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
    )(EmbedlyInfo.apply _, unlift(EmbedlyInfo.unapply))
}

trait EmbedlyClient {
  def embedlyUrl(url: String): String
  def getEmbedlyInfo(url:String):Future[Option[EmbedlyInfo]]
  def getImageInfos(nUri: NormalizedURI): Future[Seq[ImageInfo]]
}

@Singleton
class EmbedlyClientImpl @Inject() extends EmbedlyClient with Logging {

  private val embedlyKey = "e46ecae2611d4cb29342fddb0e666a29"

  override def embedlyUrl(url: String): String = s"http://api.embed.ly/1/extract?key=$embedlyKey&url=${URLEncoder.encode(url, UTF8)}"

  override def getImageInfos(nUri: NormalizedURI): Future[Seq[ImageInfo]] = {
    getEmbedlyInfo(nUri.url) map {
      infoOpt => nUri.id flatMap { uriId =>
        infoOpt.map(_.buildImageInfo(uriId))
      } getOrElse Seq()
    }
  }

  override def getEmbedlyInfo(url:String):Future[Option[EmbedlyInfo]] = {
    val apiUrl = embedlyUrl(url)
    fetchPageInfoConsolidator(apiUrl) { urlString =>
      fetchWithRetry(apiUrl, 120000) map { resp =>
        log.info(s"getEmbedlyInfo($url)] resp.status=${resp.statusText} body=${resp.body}")
        resp.status match {
          case Status.OK =>
            val js = Json.parse(resp.body) // resp.json has some issues
          val extractRespOpt = js.validate[EmbedlyInfo].fold(
              valid = (res => Some(res)),
              invalid = (e => {
                log.info(s"Failed to parse JSON: body=${resp.body} errors=${e.toString}")
                None
              })
            )
            log.info(s"extractRespOpt=$extractRespOpt")
            extractRespOpt
          case _ => // todo(ray): need better handling of error codes
            log.info(s"ERROR while invoking ($apiUrl): status=${resp.status} body=${resp.body}")
            None
        }
      } recover { case t:Throwable =>
        log.info(s"Caught exception while invoking ($apiUrl): Exception=$t; cause=${t.getCause}")
        None
      }
    }
  }

  private val fetchPageInfoConsolidator = new RequestConsolidator[String,Option[EmbedlyInfo]](2 minutes)

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

class DevEmbedlyClient extends EmbedlyClient {
  override def embedlyUrl(url: String): String = "http://dev.ezkeep.com"
  override def getEmbedlyInfo(url:String):Future[Option[EmbedlyInfo]] = future{None}
  def getImageInfos(nUri: NormalizedURI): Future[Seq[ImageInfo]] = future{Seq()}
}
