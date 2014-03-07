package com.keepit.common.store

import com.keepit.model.{NormalizedURI}
import scala.concurrent._

case class FakeS3ScreenshotStore() extends S3ScreenshotStore {
  def config: S3ImageConfig = ???
  def getScreenshotUrl(normalizedUri: NormalizedURI): Option[String] = None
  def getScreenshotUrl(normalizedUriOpt: Option[NormalizedURI]): Option[String] = None
  def updatePicture(normalizedUri: NormalizedURI): Future[Boolean] = Future.successful(true)
  def asyncGetImageUrl(uri:NormalizedURI):Future[Option[String]] = Future.successful(None)
  def getImageUrl(normalizedUri: NormalizedURI): Option[String] = None
  def processImage(uri: NormalizedURI, imageInfo: ImageInfo): Future[Boolean] = Future.successful(true)
  def getImageInfos(normalizedUri: NormalizedURI): Future[Seq[ImageInfo]] = Future.successful(Seq.empty[ImageInfo])
}
