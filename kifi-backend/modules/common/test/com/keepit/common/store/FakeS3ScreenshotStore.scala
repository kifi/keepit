package com.keepit.common.store

import com.keepit.model.NormalizedURI
import scala.concurrent.Future
import scala.concurrent.Promise
import com.amazonaws.services.s3.model.PutObjectResult

class FakeS3ScreenshotStore extends S3ScreenshotStore {
  def config: S3ImageConfig = ???
  def getScreenshotUrl(normalizedUri: NormalizedURI): Option[String] = None
  def getScreenshotUrl(normalizedUriOpt: Option[NormalizedURI]): Option[String] = None
  def updatePicture(normalizedUri: NormalizedURI): Future[Option[Seq[Option[PutObjectResult]]]] = Promise.successful(None).future
}