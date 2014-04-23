package com.keepit.common.store

import java.io.{InputStream, ByteArrayOutputStream, ByteArrayInputStream}
import scala.concurrent.Future
import com.amazonaws.services.s3.AmazonS3
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.common.healthcheck.{SystemAdminMailSender, AirbrakeNotifier}
import com.keepit.common.time.Clock
import com.keepit.common.embedly.EmbedlyClient
import com.keepit.common.logging.Logging
import com.keepit.model.{NormalizedURI, URIImage}
import com.keepit.common.db.ExternalId
import com.amazonaws.services.s3.model.{PutObjectResult, ObjectMetadata}
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import scala.util.Try

trait S3URIImageStore {
  def storeImage(token: ExternalId[URIImage], rawImage:BufferedImage): Try[PutObjectResult]
}

class S3URIImageStoreImpl(override val s3Client: AmazonS3, config: S3ImageConfig) extends S3URIImageStore with S3Helper with Logging {

  def storeImage(token: ExternalId[URIImage], rawImage: BufferedImage): Try[PutObjectResult] = {
    val os = new ByteArrayOutputStream()
    ImageIO.write(rawImage, "jpg", os)
    os.flush
    val bytes = os.toByteArray
    os.close
    val key = s"img/${token}.jpg"
    streamUpload(config.bucketName, key, new ByteArrayInputStream(bytes), "public, max-age=1800", bytes.length)
  }
}
