package com.keepit.model

import com.keepit.common.db._
import com.keepit.common.store.{ S3UserPictureConfig, ImagePath }
import com.keepit.social.SocialNetworkType
import org.joda.time.DateTime
import com.keepit.common.time._
import org.apache.commons.lang3.RandomStringUtils
import play.api.libs.json.JsObject

case class UserPictureSource(name: String)
object UserPictureSource {
  val USER_UPLOAD = UserPictureSource("user_upload")
  def apply(networkType: SocialNetworkType): UserPictureSource = {
    import com.keepit.social.SocialNetworks._
    networkType match {
      case EMAIL | FORTYTWO | FORTYTWO_NF => UserPictureSource("gravatar")
      case socialNetwork => UserPictureSource(socialNetwork.name)
    }
  }
}

case class UserPicture(
    id: Option[Id[UserPicture]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    userId: Id[User],
    name: String, // the filename of the picture stored in S3, no extension (all are .jpg)
    origin: UserPictureSource,
    state: State[UserPicture] = UserPictureStates.ACTIVE,
    attributes: Option[JsObject]) extends ModelWithState[UserPicture] {
  def withId(id: Id[UserPicture]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withState(state: State[UserPicture]) = copy(state = state)
}

object UserPicture {
  def generateNewFilename: String = RandomStringUtils.randomAlphanumeric(5)

  def toS3Key(size: String, userId: ExternalId[User], picName: String): String = {
    val pic = if (picName.endsWith(".jpg")) picName else s"$picName.jpg"
    s"users/${userId.id}/pics/$size/$pic"
  }
  def toImagePath(w: Option[Int], userId: ExternalId[User], picName: String): ImagePath = {
    val size = S3UserPictureConfig.ImageSizes.find(size => w.exists(size >= _)).map(_.toString).getOrElse(S3UserPictureConfig.OriginalImageSize)
    ImagePath(toS3Key(size, userId, picName))
  }
}

object UserPictureStates extends States[UserPicture]
