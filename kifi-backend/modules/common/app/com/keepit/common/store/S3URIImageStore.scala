package com.keepit.common.store

import java.io.{ByteArrayOutputStream, ByteArrayInputStream}
import com.amazonaws.services.s3.AmazonS3
import com.keepit.common.logging.Logging
import com.keepit.common.db.ExternalId
import com.amazonaws.services.s3.model.PutObjectResult
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import scala.util.Try
import com.keepit.model.ImageInfo

trait S3URIImageStore {
  def storeImage(token: ExternalId[ImageInfo], rawImage:BufferedImage): Try[PutObjectResult]
}

class S3URIImageStoreImpl(override val s3Client: AmazonS3, config: S3ImageConfig) extends S3URIImageStore with S3Helper with Logging {

  def storeImage(token: ExternalId[ImageInfo], rawImage: BufferedImage): Try[PutObjectResult] = {
    val os = new ByteArrayOutputStream()
    ImageIO.write(rawImage, "jpg", os)
    os.flush
    val bytes = os.toByteArray
    os.close
    val key = s"img/${token}.jpg"
    streamUpload(config.bucketName, key, new ByteArrayInputStream(bytes), "public, max-age=1800", bytes.length)
  }
}
