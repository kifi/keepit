package com.keepit.common.store

import com.google.inject.{Singleton, Provides}
import com.keepit.model.{User, SocialUserInfo, NormalizedURI}
import scala.concurrent._
import com.amazonaws.services.s3.model.PutObjectResult
import com.keepit.common.db.{Id, ExternalId}
import scala.util.{Success, Try}
import java.io.File

case class FakeS3ImageStore(val config: S3ImageConfig) extends S3ImageStore {
  def getPictureUrl(w: Int, user: User) =
    promise[String]().success(s"http://cloudfront/${user.id.get}_${w}x${w}").future
  def getPictureUrl(width: Option[Int], user: User, picVersion: String): Future[String] =
    promise[String]().success(s"http://cloudfront/${user.id.get}_${width.getOrElse(100)}x${width.getOrElse(100)}_$picVersion").future

  def uploadPictureFromSocialNetwork(sui: SocialUserInfo, externalId: ExternalId[User], pictureName: String, setDefault: Boolean): Future[Seq[(String, Try[PutObjectResult])]] =
    promise[Seq[(String,Try[PutObjectResult])]]().success(Seq()).future
  def uploadPictureFromSocialNetwork(sui: SocialUserInfo, externalId: ExternalId[User], setDefault: Boolean): Future[Seq[(String, Try[PutObjectResult])]] =
    promise[Seq[(String,Try[PutObjectResult])]]().success(Seq()).future

  def uploadTemporaryPicture(file: File): Try[(String, String)] =
    Success("token", "http://cloudfront/token.jpg")

  def forceUpdateSocialPictures(userId: Id[User]): Unit = {}

  // Returns Some(urlOfUserPicture) or None
  def copyTempFileToUserPic(userId: Id[User], userExtId: ExternalId[User], token: String, cropAttributes: Option[ImageCropAttributes]): Option[String] = None

}
