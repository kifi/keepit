package com.keepit.common.store

import com.google.inject.{Singleton, Provides}
import com.keepit.model.{User, SocialUserInfo, NormalizedURI}
import scala.concurrent._
import com.amazonaws.services.s3.model.PutObjectResult
import com.keepit.common.db.{Id, ExternalId}
import scala.util.{Success, Try}
import java.io.File

case class FakeS3ScreenshotStore() extends S3ScreenshotStore {
  def config: S3ImageConfig = ???
  def getScreenshotUrl(normalizedUri: NormalizedURI): Option[String] = None
  def getScreenshotUrl(normalizedUriOpt: Option[NormalizedURI]): Option[String] = None
  def updatePicture(normalizedUri: NormalizedURI): Future[Option[Seq[Option[PutObjectResult]]]] = Future.successful(None)
}
