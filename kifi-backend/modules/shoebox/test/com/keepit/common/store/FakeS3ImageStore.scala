package com.keepit.common.store

import java.io.File

import com.amazonaws.services.s3.model.PutObjectResult
import com.keepit.common.db.slick.Database
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.model.{ UserPictureSource, User, UserRepo }

import scala.concurrent._
import scala.util.{ Success, Try }

case class FakeS3ImageStore(val config: S3ImageConfig, db: Database, userRepo: UserRepo) extends S3ImageStore {
  def getPictureUrl(w: Int, userId: Id[User]) = {
    val user = db.readOnlyMaster { implicit session => userRepo.get(userId) }
    getPictureUrl(Some(w), user, user.pictureName getOrElse "0")
  }
  def getPictureUrl(width: Option[Int], user: User, picVersion: String): Future[String] =
    Promise[String]().success(s"//cloudfront/users/${user.id.get}/pics/${width.getOrElse(100)}/$picVersion.jpg").future

  def uploadRemotePicture(userId: Id[User], externalId: ExternalId[User], pictureSource: UserPictureSource, pictureName: Option[String], setDefault: Boolean)(getPictureUrl: Option[ImageSize] => Option[String]): Future[Seq[(String, Try[PutObjectResult])]] = {
    Future.successful(Seq())
  }

  def uploadTemporaryPicture(file: File): Try[(String, String)] =
    Success("token", "http://cloudfront/token.jpg")

  def forceUpdateSocialPictures(userId: Id[User]): Unit = {}

  // Returns Some(urlOfUserPicture) or None
  def copyTempFileToUserPic(userId: Id[User], userExtId: ExternalId[User], token: String, cropAttributes: Option[ImageCropAttributes]): Option[String] = None

}
