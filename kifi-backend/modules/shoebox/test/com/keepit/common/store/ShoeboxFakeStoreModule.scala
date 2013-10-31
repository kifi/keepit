package com.keepit.common.store

import com.google.inject.{Singleton, Provides}
import com.keepit.common.analytics.reports.{InMemoryReportStoreImpl, ReportStore}
import com.keepit.model.{User, SocialUserInfo, NormalizedURI}
import scala.concurrent._
import com.amazonaws.services.s3.model.PutObjectResult
import com.keepit.common.db.{Id, ExternalId}
import scala.util.{Success, Try}
import java.io.File

case class ShoeboxFakeStoreModule() extends FakeStoreModule {

  @Provides @Singleton
  def reportStore(): ReportStore = new InMemoryReportStoreImpl()

  @Provides @Singleton
  def s3ScreenshotStore = FakeS3ScreenshotStore()

  @Provides @Singleton
  def s3ImageStore(s3ImageConfig: S3ImageConfig): S3ImageStore = FakeS3ImageStore(s3ImageConfig)

}

case class FakeS3ScreenshotStore() extends S3ScreenshotStore {
  def config: S3ImageConfig = ???
  def getScreenshotUrl(normalizedUri: NormalizedURI): Option[String] = None
  def getScreenshotUrl(normalizedUriOpt: Option[NormalizedURI]): Option[String] = None
  def updatePicture(normalizedUri: NormalizedURI): Future[Option[Seq[Option[PutObjectResult]]]] = Future.successful(None)
}

case class FakeS3ImageStore(val config: S3ImageConfig) extends S3ImageStore {
  def getPictureUrl(w: Int, user: User) =
    promise[String]().success(s"http://cloudfront/${user.id.get}_${w}x${w}").future
  def getPictureUrl(width: Option[Int], user: User, picVersion: String): Future[String] =
    promise[String]().success(s"http://cloudfront/${user.id.get}_${width.getOrElse(100)}x${width.getOrElse(100)}_$picVersion").future

  def uploadPictureFromSocialNetwork(sui: SocialUserInfo, externalId: ExternalId[User], pictureName: String): Future[Seq[(String, Try[PutObjectResult])]] =
    promise[Seq[(String,Try[PutObjectResult])]]().success(Seq()).future
  def uploadPictureFromSocialNetwork(sui: SocialUserInfo, externalId: ExternalId[User]): Future[Seq[(String, Try[PutObjectResult])]] =
    promise[Seq[(String,Try[PutObjectResult])]]().success(Seq()).future

  def uploadTemporaryPicture(file: File): Try[(String, String)] =
    Success("token", "http://cloudfront/token.jpg")

  // Returns Some(urlOfUserPicture) or None
  def copyTempFileToUserPic(userId: Id[User], userExtId: ExternalId[User], token: String, cropAttributes: Option[ImageCropAttributes]): Option[String] = None

}
