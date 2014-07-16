package com.keepit.social

import com.keepit.common.cache._
import com.keepit.common.db._
import com.keepit.common.logging.AccessLog
import com.keepit.model._

import java.io.{ ByteArrayInputStream, ByteArrayOutputStream }

import play.api.libs.functional.syntax._
import play.api.libs.json._

import org.apache.lucene.store.{ InputStreamDataInput, OutputStreamDataOutput }

import scala.concurrent.duration.Duration
import com.keepit.serializer.NoCopyLocalSerializer
import com.keepit.serializer.NoCopyLocalSerializer
import scala.Some

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
    username: Option[Username]) extends BasicUserLikeEntity {

  override def asBasicUser = Some(this)
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
    (__ \ 'username).formatNullable[Username]
  )(BasicUser.apply, unlift(BasicUser.unapply))

  def fromUser(user: User): BasicUser = {
    BasicUser(
      externalId = user.externalId,
      firstName = user.firstName,
      lastName = user.lastName,
      pictureName = user.pictureName.map(_ + ".jpg").getOrElse("0.jpg"), // need support for default image
      username = user.username
    )
  }

  def toByteArray(basicUser: BasicUser): Array[Byte] = {
    val bos = new ByteArrayOutputStream()
    val oos = new OutputStreamDataOutput(bos)
    oos.writeByte(2) // version
    oos.writeString(basicUser.externalId.toString)
    oos.writeString(basicUser.firstName)
    oos.writeString(basicUser.lastName)
    oos.writeString(basicUser.pictureName)
    oos.writeString(basicUser.username.map(_.value).getOrElse(""))
    oos.close()
    bos.close()
    bos.toByteArray()
  }

  def fromByteArray(bytes: Array[Byte], offset: Int, length: Int): BasicUser = {
    val in = new InputStreamDataInput(new ByteArrayInputStream(bytes, offset, length))

    val version = in.readByte().toInt

    version match {
      case 1 => // pre-username
        BasicUser(
          externalId = ExternalId[User](in.readString),
          firstName = in.readString,
          lastName = in.readString,
          pictureName = in.readString,
          username = None
        )
      case 2 => // with username
        BasicUser(
          externalId = ExternalId[User](in.readString),
          firstName = in.readString,
          lastName = in.readString,
          pictureName = in.readString,
          username = {
            val u = in.readString
            if (u.length == 0) None else Some(Username(u))
          }
        )
      case _ =>
        throw new Exception(s"invalid data [version=${version}]")
    }
  }
}

case class BasicUserUserIdKey(userId: Id[User]) extends Key[BasicUser] {
  override val version = 6
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
