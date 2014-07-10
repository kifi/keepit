package com.keepit.common.store

import java.io.{ ByteArrayOutputStream, ByteArrayInputStream }
import com.amazonaws.services.s3.AmazonS3
import com.keepit.common.logging.Logging
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import scala.util.{ Failure, Success, Try }
import com.keepit.model.{ ImageFormat, ImageProvider, ImageInfo, NormalizedURI }
import com.keepit.common.net.URI
import com.keepit.common.healthcheck.AirbrakeNotifier

trait S3URIImageStore {
  def storeImage(info: ImageInfo, rawImage: BufferedImage, nUri: NormalizedURI): Try[(String, Int)]
  def getDefaultScreenshotURL(nUri: NormalizedURI): Option[String]
  def getImageURL(imageInfo: ImageInfo, nUri: NormalizedURI): Option[String]
}

class S3URIImageStoreImpl(override val s3Client: AmazonS3, config: S3ImageConfig, airbrake: AirbrakeNotifier) extends S3URIImageStore with S3Helper with Logging {

  def storeImage(info: ImageInfo, rawImage: BufferedImage, nUri: NormalizedURI): Try[(String, Int)] = {
    val os = new ByteArrayOutputStream()
    ImageIO.write(rawImage, "jpg", os)
    os.flush
    val bytes = os.toByteArray
    os.close
    val key = getImageKey(info, nUri)
    log.info(s"Uploading screenshot of ${nUri.url} to S3 key $key")
    streamUpload(config.bucketName, key, new ByteArrayInputStream(bytes), "public, max-age=1800", bytes.length) flatMap { _ =>
      // Return the url of the image in S3
      Try { (getImageURL(info, nUri).get, bytes.length) }
    }
  }

  /**
   * Builds generic screenshot URL for given normalized URI. No check if the screenshot actually exists.
   */
  def getDefaultScreenshotURL(nUri: NormalizedURI): Option[String] = {
    urlFromKey(getScreenshotKey(nUri, None))
  }

  private def urlFromKey(key: String): Option[String] = {
    URI.parse(s"${config.cdnBase}/${key}") match {
      case Success(uri) =>
        Some(URI(uri.scheme orElse None, uri.userInfo, uri.host, uri.port, uri.path, uri.query, uri.fragment).toString)
      case Failure(t) =>
        airbrake.notify(s"Failed to parse $key; Exception: $t; Cause: ${t.getCause}", t)
        None
    }
  }

  def getImageURL(imageInfo: ImageInfo, nUri: NormalizedURI): Option[String] = {
    if (config.isLocal || (imageInfo.provider == ImageProvider.PAGEPEEKER && nUri.screenshotUpdatedAt.isEmpty)) return None
    urlFromKey(getImageKey(imageInfo, nUri))
  }

  private def getImageKey(imageInfo: ImageInfo, nUri: NormalizedURI): String = {
    val provider = imageInfo.provider.getOrElse(ImageProvider.EMBEDLY) // Use Embedly as default provider (backwards compatibility)
    provider match {
      case ImageProvider.EMBEDLY => s"images/${nUri.externalId}/${ImageProvider.getProviderIndex(imageInfo.provider)}/${imageInfo.name}.${imageInfo.getFormatSuffix}"
      case ImageProvider.PAGEPEEKER => getScreenshotKey(nUri, imageInfo.getImageSize)
      case _ => {
        airbrake.notify(s"Unsupported image provider: ${imageInfo.provider}")
        ""
      }
    }
  }

  private val screenshotFormat = ImageFormat.JPG
  private val defaultSize = ImageSize(500, 280)
  private def getScreenshotKey(nUri: NormalizedURI, imageSizeOpt: Option[ImageSize]): String = {
    val size = imageSizeOpt getOrElse defaultSize
    s"screenshot/${nUri.externalId}/${size.width}x${size.height}.${screenshotFormat.value}"
  }
}
