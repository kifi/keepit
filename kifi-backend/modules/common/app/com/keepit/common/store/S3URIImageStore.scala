package com.keepit.common.store

import java.io.{ ByteArrayOutputStream, ByteArrayInputStream }
import com.amazonaws.services.s3.AmazonS3
import com.keepit.common.db.ExternalId
import com.keepit.common.logging.Logging
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import scala.util.{ Failure, Success, Try }
import com.keepit.model.{ ImageFormat, ImageProvider, ImageInfo, NormalizedURI }
import com.keepit.common.net.URI
import com.keepit.common.healthcheck.AirbrakeNotifier

trait S3URIImageStore {
  def storeImage(info: ImageInfo, rawImage: BufferedImage, extNormUriId: ExternalId[NormalizedURI]): Try[(String, Int)]
  def getImageURL(imageInfo: ImageInfo, extNormUriId: ExternalId[NormalizedURI]): Option[String]
  def getImageKey(imageInfo: ImageInfo, extNormUriId: ExternalId[NormalizedURI], forceAllProviders: Boolean = false): String
  def getEmbedlyImageKey(extNormUriId: ExternalId[NormalizedURI], name: String, suffix: String): String
}

class S3URIImageStoreImpl(override val s3Client: AmazonS3, config: S3ImageConfig, airbrake: AirbrakeNotifier) extends S3URIImageStore with S3Helper with Logging {

  def storeImage(info: ImageInfo, rawImage: BufferedImage, extNormUriId: ExternalId[NormalizedURI]): Try[(String, Int)] = {
    val os = new ByteArrayOutputStream()
    ImageIO.write(rawImage, "jpg", os)
    os.flush
    val bytes = os.toByteArray
    os.close
    val key = getImageKey(info, extNormUriId)
    log.info(s"Uploading screenshot of ${extNormUriId} to S3 key $key")
    streamUpload(config.bucketName, key, new ByteArrayInputStream(bytes), "public, max-age=1800", bytes.length) flatMap { _ =>
      // Return the url of the image in S3
      Try { (getImageURL(info, extNormUriId).get, bytes.length) }
    }
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

  //setting the path for the image info
  def getImageURL(imageInfo: ImageInfo, extNormUriId: ExternalId[NormalizedURI]): Option[String] = {
    if (config.isLocal || imageInfo.provider == Some(ImageProvider.PAGEPEEKER)) return None
    urlFromKey(getImageKey(imageInfo, extNormUriId))
  }

  def getEmbedlyImageKey(extNormUriId: ExternalId[NormalizedURI], name: String, suffix: String): String =
    s"images/${extNormUriId}/${ImageProvider.getProviderIndex(Some(ImageProvider.EMBEDLY))}/${name}.${suffix}"

  def getImageKey(imageInfo: ImageInfo, extNormUriId: ExternalId[NormalizedURI], forceAllProviders: Boolean = false): String = {
    val provider = imageInfo.provider.getOrElse(ImageProvider.EMBEDLY) // Use Embedly as default provider (backwards compatibility)
    provider match {
      case ImageProvider.EMBEDLY => getEmbedlyImageKey(extNormUriId, imageInfo.name, imageInfo.getFormatSuffix)
      case ImageProvider.PAGEPEEKER => getScreenshotKey(extNormUriId, imageInfo.getImageSize)
      case _ => {
        if (forceAllProviders) {
          s"images/${extNormUriId}/0/${imageInfo.name}.jpg"
        } else {
          airbrake.notify(s"Unsupported image provider: ${imageInfo.provider}")
          ""
        }
      }
    }
  }

  private val screenshotFormat = ImageFormat.JPG
  private val defaultSize = ImageSize(500, 280)
  private def getScreenshotKey(extNormUriId: ExternalId[NormalizedURI], imageSizeOpt: Option[ImageSize]): String = {
    val size = imageSizeOpt getOrElse defaultSize
    s"screenshot/${extNormUriId}/${size.width}x${size.height}.${screenshotFormat.value}"
  }
}
