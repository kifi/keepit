package com.keepit.common.store

import java.net.URLEncoder
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.util.Failure
import scala.util.Success
import org.joda.time.Weeks
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.model.ObjectMetadata
import com.amazonaws.services.s3.model.PutObjectResult
import com.google.inject.ImplementedBy
import com.google.inject.Inject
import com.google.inject.Singleton
import com.keepit.common.db.ExternalId
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.ErrorMessage.toErrorMessage
import com.keepit.common.healthcheck.Healthcheck
import com.keepit.common.healthcheck.HealthcheckError
import com.keepit.common.healthcheck.HealthcheckPlugin
import com.keepit.common.logging.Logging
import com.keepit.common.net.URI
import com.keepit.common.strings.UTF8
import com.keepit.common.time.Clock
import com.keepit.common.time.DEFAULT_DATE_TIME_ZONE
import com.keepit.model.NormalizedURI
import com.keepit.model.NormalizedURIRepo
import com.keepit.model.NormalizedURIRepoImpl
import play.api.libs.ws.WS
import scala.util.Try
import play.modules.statsd.api.Statsd


@ImplementedBy(classOf[S3ScreenshotStoreImpl])
trait S3ScreenshotStore {
  def config: S3ImageConfig
  val blankImage: Array[Byte] = Array(71, 73, 70, 56, 57, 97, 1, 0, 1, 0, -128, -1, 0, -1, -1, -1, 0, 0, 0, 44, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 68, 1, 0, 59)

  def getScreenshotUrl(normalizedUri: NormalizedURI): Option[String]
  def getScreenshotUrl(normalizedUriOpt: Option[NormalizedURI]): Option[String]
  def updatePicture(normalizedUri: NormalizedURI): Future[Option[PutObjectResult]]
}

case class ScreenshotSize(imageCode: String, size: String)

@Singleton
class S3ScreenshotStoreImpl @Inject() (
    db: Database,
    s3Client: AmazonS3,
    normUriRepo: NormalizedURIRepo,
    healthcheckPlugin: HealthcheckPlugin,
    clock: Clock,
    val config: S3ImageConfig
  ) extends S3ScreenshotStore with Logging {
  
  val size = ScreenshotSize("b", "500x280")
  val code = "abf9cd2751"
    
  def screenshotUrl(url: String): String = screenshotUrl(size.imageCode, code, url)
  def screenshotUrl(sizeName: String, code: String, url: String): String =
    s"http://api.pagepeeker.com/v2/thumbs.php?size=$sizeName&code=$code&url=${URLEncoder.encode(url, UTF8)}&wait=30&refresh=1"
  
  def urlByExternalId(extNormalizedURIId: ExternalId[NormalizedURI], protocolDefault: Option[String] = None): String = {
    val uri = URI.parse(s"${config.cdnBase}/${keyByExternalId(extNormalizedURIId)}").get
    URI(uri.scheme orElse protocolDefault, uri.userInfo, uri.host, uri.port, uri.path, uri.query, uri.fragment).toString
  }

  def keyByExternalId(extNormId: ExternalId[NormalizedURI]): String =
    s"screenshot/$extNormId/${size.size}.jpg"
  
  def getScreenshotUrl(normalizedUriOpt: Option[NormalizedURI]): Option[String] =
    normalizedUriOpt.flatMap(getScreenshotUrl)

  def getScreenshotUrl(normalizedUri: NormalizedURI): Option[String] = {
    if (config.isLocal) {
      None
    } else {
      normalizedUri.screenshotUpdatedAt match {
        case Some(updatedAt) =>
          Some(urlByExternalId(normalizedUri.externalId))
        case None =>
          updatePicture(normalizedUri)
          None
      }
    }
  }

  def updatePicture(normalizedUri: NormalizedURI): Future[Option[PutObjectResult]] = {
    if (config.isLocal) {
      Promise.successful(None).future
    } else {
      val url = normalizedUri.url
      val externalId = normalizedUri.externalId
      val future = WS.url(screenshotUrl(url)).get().map { response =>
        Option(response.ahcResponse.getHeader("X-PP-Error")) match {
          case Some("True") =>
            log.warn(s"Failed to take a screenshot of $url")
            Statsd.increment(s"screenshot.fetch.fails")
            None
          case _ =>
            Statsd.increment(s"screenshot.fetch.successes")
            val key = keyByExternalId(externalId)
            log.info(s"Uploading screenshot of $url to S3 key $key")
            val om = new ObjectMetadata()
            om.setContentType("image/jpeg")
            val contentLength = Try { response.ahcResponse.getHeader("Content-Length").toLong }
            if(contentLength.isSuccess)
              om.setContentLength(contentLength.get)
            Some(s3Client.putObject(config.bucketName, key, response.getAHCResponse.getResponseBodyAsStream, om))
        }
      }
      future onComplete {
        case Success(result) =>
          result.map { _ =>
            db.readWrite { implicit s =>
              normUriRepo.save(normalizedUri.copy(screenshotUpdatedAt = Some(clock.now)))
            }
          }
        case Failure(e) =>
          healthcheckPlugin.addError(HealthcheckError(
            error = Some(e),
            callType = Healthcheck.INTERNAL,
            errorMessage = Some(s"Failed to upload url screenshot ($url) to S3")
          ))
      }
      future
    }
  }
}
