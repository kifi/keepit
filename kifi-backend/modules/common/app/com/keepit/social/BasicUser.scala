package com.keepit.social

import com.keepit.common.cache._
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.db.Id.mapOfIdToObjectFormat
import com.keepit.common.logging.AccessLog
import com.keepit.common.store.S3UserPictureConfig
import com.keepit.model._
import play.api.libs.functional.syntax._
import play.api.libs.json._
import scala.concurrent.duration.Duration

trait BasicUserLikeEntity {
  def asBasicUser: Option[BasicUser] = None
  def asBasicNonUser: Option[BasicNonUser] = None
}

object BasicUserLikeEntity {
  implicit val nonUserTypeFormat = Json.format[NonUserKind]
  implicit val basicUserLikeEntityFormat = new Format[BasicUserLikeEntity] {
    def reads(json: JsValue): JsResult[BasicUserLikeEntity] = {
      // Detect if this is a BasicUser or BasicNonUser
      (json \ "kind").asOpt[String] match {
        case Some(kind) => BasicNonUser.basicNonUserFormat.reads(json)
        case None => BasicUser.basicUserFormat.reads(json)
      }
    }
    def writes(entity: BasicUserLikeEntity): JsValue = {
      entity match {
        case b: BasicUser => BasicUser.basicUserFormat.writes(b)
        case b: BasicNonUser => BasicNonUser.basicNonUserFormat.writes(b)
      }
    }
  }
}

case class BasicUser(
    externalId: ExternalId[User],
    firstName: String,
    lastName: String,
    pictureName: String,
    username: Username,
    active: Boolean) extends BasicUserLikeEntity {

  override def asBasicUser = Some(this)
  def fullName = s"$firstName $lastName"
}

object BasicUser {
  implicit val userExternalIdFormat = ExternalId.format[User]
  implicit val usernameFormat = Username.jsonAnnotationFormat

  // Be aware that BasicUserLikeEntity uses the `kind` field to detect if its a BasicUser or BasicNonUser
  implicit val basicUserFormat = (
    (__ \ 'id).format[ExternalId[User]] and
    (__ \ 'firstName).format[String] and
    (__ \ 'lastName).format[String] and
    (__ \ 'pictureName).format[String] and
    (__ \ 'username).format[Username] and
    (__ \ 'active).format[Boolean]
  )(BasicUser.apply, unlift(BasicUser.unapply))

  implicit val mapUserIdToInt = mapOfIdToObjectFormat[User, Int]
  implicit val mapUserIdToBasicUser = mapOfIdToObjectFormat[User, BasicUser]
  implicit val mapUserIdToUserIdSet = mapOfIdToObjectFormat[User, Set[Id[User]]]

  def fromUser(user: User): BasicUser = {
    BasicUser(
      externalId = user.externalId,
      firstName = user.firstName,
      lastName = user.lastName,
      pictureName = user.pictureName.getOrElse(S3UserPictureConfig.defaultName) + ".jpg",
      username = user.username,
      active = user.state == UserStates.ACTIVE
    )
  }
}

case class BasicUserUserIdKey(userId: Id[User]) extends Key[BasicUser] {
  override val version = 10
  val namespace = "basic_user_userid"
  def toKey(): String = userId.id.toString
}

class BasicUserUserIdCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends ImmutableJsonCacheImpl[BasicUserUserIdKey, BasicUser](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

// todo: Move to shared project between shoebox and search. This is a very specialized class that doesn't need to be in common
case class TypeaheadUserHit(
  userId: Id[User],
  externalId: ExternalId[User],
  firstName: String,
  lastName: String,
  pictureName: String)

object TypeaheadUserHit {
  implicit val userIdFormat = Id.format[User]
  implicit val userExternalIdFormat = ExternalId.format[User]

  implicit val basicUserWithUserIdFormat = (
    (__ \ 'userId).format[Id[User]] and
    (__ \ 'externalId).format[ExternalId[User]] and
    (__ \ 'firstName).format[String] and
    (__ \ 'lastName).format[String] and
    (__ \ 'pictureName).format[String]
  )(TypeaheadUserHit.apply, unlift(TypeaheadUserHit.unapply))

  def fromBasicUserAndId(user: BasicUser, id: Id[User]): TypeaheadUserHit = {
    TypeaheadUserHit(id, user.externalId, user.firstName, user.lastName, user.pictureName)
  }
}
