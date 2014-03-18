package com.keepit.common.store

import com.keepit.model.{PageInfo, ImageInfo, NormalizedURI}
import scala.concurrent._

case class FakeS3ScreenshotStore() extends S3ScreenshotStore {
  def config: S3ImageConfig = ???
  def getScreenshotUrl(normalizedUri: NormalizedURI): Option[String] = None
  def getScreenshotUrl(normalizedUriOpt: Option[NormalizedURI]): Option[String] = None
  def updatePicture(normalizedUri: NormalizedURI): Future[Boolean] = Future.successful(true)

  def getImageInfos(normalizedUri: NormalizedURI): Future[Seq[ImageInfo]] = Future.successful(Seq.empty[ImageInfo]) = ???
  def asyncGetImageUrl(uri: NormalizedURI, pageInfoOpt: Option[PageInfo], update: Boolean): Future[Option[String]] = ???
  def updateImage(uri: NormalizedURI): Future[Boolean] = ???
}
