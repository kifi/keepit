package com.keepit.common.store

import com.keepit.model.{ ImageProvider, NormalizedURI, ImageInfo }
import java.awt.image.BufferedImage
import scala.util.{ Success, Try }
import com.keepit.common.db.ExternalId

case class FakeS3URIImageStore() extends S3URIImageStore {
  def storeImage(info: ImageInfo, rawImage: BufferedImage, extNormUriId: ExternalId[NormalizedURI]): Try[(String, Int)] = Success((FakeS3URIImageStore.placeholderImageURL, FakeS3URIImageStore.placeholderSize))
  def getImageURL(imageInfo: ImageInfo, extNormUriId: ExternalId[NormalizedURI]): Option[String] = None
  def getImageKey(imageInfo: ImageInfo, extNormUriId: ExternalId[NormalizedURI], forceAllProviders: Boolean = false): String = ""
}

object FakeS3URIImageStore {
  val placeholderImageURL = "http://www.testurl.com/testimage.jpg"
  val placeholderScreenshotURL = "http://www.theplaceholderscreenshot.com/screenshot.jpg"
  val placeholderSize = 15000
}
