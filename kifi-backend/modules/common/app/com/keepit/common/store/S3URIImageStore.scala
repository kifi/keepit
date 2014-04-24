package com.keepit.common.store

import java.io.{ByteArrayOutputStream, ByteArrayInputStream}
import com.amazonaws.services.s3.AmazonS3
import com.keepit.common.logging.Logging
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import scala.util.{Failure, Success, Try}
import com.keepit.model.{ImageProvider, ImageInfo, NormalizedURI}
import com.keepit.common.db.ExternalId
import com.keepit.common.net.URI
import com.keepit.common.healthcheck.AirbrakeNotifier

trait S3URIImageStore {
  def storeImage(info: ImageInfo, rawImage: BufferedImage, nUri: NormalizedURI): Try[String]
  def mkImgUrl(id: ExternalId[NormalizedURI], providerOpt: Option[ImageProvider], name: String, protocolDefault: Option[String] = None): Option[String]
}

class S3URIImageStoreImpl(override val s3Client: AmazonS3, config: S3ImageConfig, airbrake: AirbrakeNotifier) extends S3URIImageStore with S3Helper with Logging {

  def storeImage(info: ImageInfo, rawImage: BufferedImage, nUri: NormalizedURI): Try[String] = {
    val os = new ByteArrayOutputStream()
    ImageIO.write(rawImage, "jpg", os)
    os.flush
    val bytes = os.toByteArray
    os.close
    streamUpload(config.bucketName, mkImgKey(nUri.externalId, info.provider, info.name), new ByteArrayInputStream(bytes), "public, max-age=1800", bytes.length) flatMap { _ =>
      // Return the url of the image in S3
      Try{mkImgUrl(nUri.externalId, info.provider, info.name).get}
    }
  }

  private def mkImgBucket(extId: ExternalId[NormalizedURI], providerIdx:Int) = s"images/$extId/$providerIdx"
  private def mkImgKey(id: ExternalId[NormalizedURI], provider: Option[ImageProvider], name: String):String = s"${mkImgBucket(id, ImageProvider.getProviderIndex(provider))}/${name}.jpg"

  def mkImgUrl(id: ExternalId[NormalizedURI], provider: Option[ImageProvider], name: String, protocolDefault: Option[String] = None): Option[String] = {
    val s = s"${config.cdnBase}/${mkImgKey(id, provider, name)}"
    URI.parse(s) match {
      case Success(uri) =>
        Some(URI(uri.scheme orElse protocolDefault, uri.userInfo, uri.host, uri.port, uri.path, uri.query, uri.fragment).toString)
      case Failure(t) =>
        airbrake.notify(s"Failed to parse $s; Exception: $t; Cause: ${t.getCause}", t)
        None
    }
  }
}
