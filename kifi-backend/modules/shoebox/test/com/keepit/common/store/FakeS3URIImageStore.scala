package com.keepit.common.store

import com.keepit.model.{ImageProvider, NormalizedURI, ImageInfo}
import java.awt.image.BufferedImage
import scala.util.{Success, Try}
import com.keepit.common.db.ExternalId

case class FakeS3URIImageStore() extends S3URIImageStore {
  def storeImage(info: ImageInfo, rawImage: BufferedImage, nUri: NormalizedURI): Try[(String, Int)] = Success((FakeS3URIImageStore.placeholderImageURL, FakeS3URIImageStore.placeholderSize))
  def mkImgUrl(id: ExternalId[NormalizedURI], providerOpt: Option[ImageProvider], name: String, protocolDefault: Option[String] = None): Option[String] = None
}

object FakeS3URIImageStore {
  val placeholderImageURL = "http://www.testurl.com/testimage.jpg"
  val placeholderSize = 15000
}
