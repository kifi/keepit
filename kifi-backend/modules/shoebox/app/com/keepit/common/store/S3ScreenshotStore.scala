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
import org.imgscalr.Scalr
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream


@ImplementedBy(classOf[S3ScreenshotStoreImpl])
trait S3ScreenshotStore {
  def config: S3ImageConfig
  val blankImage: Array[Byte] = Array(71, 73, 70, 56, 57, 97, 1, 0, 1, 0, -128, -1, 0, -1, -1, -1, 0, 0, 0, 44, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 68, 1, 0, 59)

  def getScreenshotUrl(normalizedUri: NormalizedURI): Option[String]
  def getScreenshotUrl(normalizedUriOpt: Option[NormalizedURI]): Option[String]
  def updatePicture(normalizedUri: NormalizedURI): Future[Option[Seq[Option[PutObjectResult]]]]
}

case class ScreenshotSize(width: Int, height: Int)
case class ScreenshotConfig(imageCode: String, targetSizes: Seq[ScreenshotSize])

@Singleton
class S3ScreenshotStoreImpl @Inject() (
    db: Database,
    s3Client: AmazonS3,
    normUriRepo: NormalizedURIRepo,
    healthcheckPlugin: HealthcheckPlugin,
    clock: Clock,
    val config: S3ImageConfig
  ) extends S3ScreenshotStore with Logging {
  
  val screenshotConfig = ScreenshotConfig("c", Seq(ScreenshotSize(1000, 560), ScreenshotSize(500, 280), ScreenshotSize(250, 140)))
  val linkedSize = ScreenshotSize(500, 280) // which size to link to, by default; todo: user configurable
  
  val code = "abf9cd2751"
    
  def screenshotUrl(url: String): String = screenshotUrl(screenshotConfig.imageCode, code, url)
  def screenshotUrl(sizeName: String, code: String, url: String): String =
    s"http://api.pagepeeker.com/v2/thumbs.php?size=$sizeName&code=$code&url=${URLEncoder.encode(url, UTF8)}&wait=30&refresh=1"
  
  def urlByExternalId(extNormalizedURIId: ExternalId[NormalizedURI], protocolDefault: Option[String] = None): String = {
    val uri = URI.parse(s"${config.cdnBase}/${keyByExternalId(extNormalizedURIId, linkedSize)}").get
    URI(uri.scheme orElse protocolDefault, uri.userInfo, uri.host, uri.port, uri.path, uri.query, uri.fragment).toString
  }

  def keyByExternalId(extNormId: ExternalId[NormalizedURI], size: ScreenshotSize): String =
    s"screenshot/$extNormId/${size.width}x${size.height}.jpg"
  
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
  
  private def resizeImage(rawImage: BufferedImage, size: ScreenshotSize) = {
    val resized = Try { Scalr.resize(rawImage, Math.max(size.height, size.width)) }
    val os = new ByteArrayOutputStream()
    ImageIO.write(resized.getOrElse(rawImage), "jpeg", os)

    (os.size(), new ByteArrayInputStream(os.toByteArray()))
  }

  def updatePicture(normalizedUri: NormalizedURI): Future[Option[Seq[Option[PutObjectResult]]]] = {
    if (config.isLocal) {
      Promise.successful(None).future
    } else {
      val url = normalizedUri.url
      val externalId = normalizedUri.externalId
      val future = WS.url(screenshotUrl(url)).get().map { response =>
        Option(response.ahcResponse.getHeader("X-PP-Error")) match {
          case Some("True") =>
            log.warn(s"Failed to take a screenshot of $url. Reported error from provider.")
            Statsd.increment(s"screenshot.fetch.fails")
            None
          case _ =>
            
            val originalStream = response.getAHCResponse.getResponseBodyAsStream
            val rawImageTry = Try { ImageIO.read(originalStream) }
            
            val resizedImages = screenshotConfig.targetSizes.map { size =>
              for {
                rawImage <- rawImageTry
                resized <- Try { resizeImage(rawImage, size) }
              } yield (resized._1, resized._2, size)
            }
            
            val storedObjects = resizedImages map { case resizeAttempt =>
              resizeAttempt match {
                case Success((contentLength, imageStream, size)) =>
                  Statsd.increment(s"screenshot.fetch.successes")
                  
                  val om = new ObjectMetadata()
                  om.setContentType("image/jpeg")
                  om.setContentLength(contentLength)
                  val key = keyByExternalId(externalId, size)
                  val s3obj = s3Client.putObject(config.bucketName, key, imageStream, om)
                  log.info(s"Uploading screenshot of $url to S3 key $key")
                  
                  imageStream.close
                  Some(s3obj)
                case Failure(ex) =>
                  Statsd.increment(s"screenshot.fetch.fails")
                  ex match {
                    case e: java.lang.IllegalArgumentException =>
                      // This happens when the image stream is null, coming from javax.imageio.ImageIO
                    case _ =>
                      healthcheckPlugin.addError(HealthcheckError(
                        error = Some(ex),
                        callType = Healthcheck.INTERNAL,
                        errorMessage = Some(s"Problem resizing screenshot image from $url. ")
                      ))
                  }
                  None
              }
            }
            
            originalStream.close()
            
            Some(storedObjects)
        }
      }
      future onComplete {
        case Success(result) =>
          result.map { s =>
            if(s.exists(_.isDefined)) { // *an* image persisted successfully
              // todo(andrew): create Screenshot model, track what sizes we have and when they were captured
              db.readWrite { implicit s =>
                normUriRepo.save(normalizedUri.copy(screenshotUpdatedAt = Some(clock.now)))
              }
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
