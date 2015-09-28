package com.keepit.model

import com.keepit.common.db.{ States, ModelWithState, State, Id }
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
}

object UserPictureStates extends States[UserPicture]
