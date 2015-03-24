package com.keepit.common.store

import java.io.File

import com.amazonaws.services.s3.model.PutObjectResult
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.model.{ SocialUserInfo, User }

import scala.concurrent._
import scala.util.{ Success, Try }

case class FakeS3ImageStore(val config: S3ImageConfig) extends S3ImageStore {
  def getPictureUrl(w: Int, user: User) =
    Promise[String]().success(s"//cloudfront/${user.id.get}_${w}x${w}").future
  def getPictureUrl(width: Option[Int], user: User, picVersion: String): Future[String] =
    Promise[String]().success(s"//cloudfront/users/${user.id.get}/pics/${width.getOrElse(100)}/$picVersion.jpg").future

  def uploadPictureFromSocialNetwork(sui: SocialUserInfo, externalId: ExternalId[User], pictureName: String, setDefault: Boolean): Future[Seq[(String, Try[PutObjectResult])]] =
    Promise[Seq[(String, Try[PutObjectResult])]]().success(Seq()).future
  def uploadPictureFromSocialNetwork(sui: SocialUserInfo, externalId: ExternalId[User], setDefault: Boolean): Future[Seq[(String, Try[PutObjectResult])]] =
    Promise[Seq[(String, Try[PutObjectResult])]]().success(Seq()).future

  def uploadTemporaryPicture(file: File): Try[(String, String)] =
    Success("token", "http://cloudfront/token.jpg")

  def forceUpdateSocialPictures(userId: Id[User]): Unit = {}

  // Returns Some(urlOfUserPicture) or None
  def copyTempFileToUserPic(userId: Id[User], userExtId: ExternalId[User], token: String, cropAttributes: Option[ImageCropAttributes]): Option[String] = None

}
