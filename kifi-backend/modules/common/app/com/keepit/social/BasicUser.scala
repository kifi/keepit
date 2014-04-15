package com.keepit.social

import com.keepit.common.cache.{CacheStatistics, JsonCacheImpl, FortyTwoCachePlugin, Key}
import com.keepit.common.db._
import com.keepit.common.logging.AccessLog
import com.keepit.model._

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}

import play.api.libs.functional.syntax._
import play.api.libs.json._

import org.apache.lucene.store.{InputStreamDataInput, OutputStreamDataOutput}

import scala.concurrent.duration.Duration

trait BasicUserLikeEntity

object BasicUserLikeEntity {
  implicit val nonUserTypeFormat = Json.format[NonUserKind]
  implicit val basicUserLikeEntityFormat = new Format[BasicUserLikeEntity] {
    def reads(json: JsValue): JsResult[BasicUserLikeEntity] = {
      // Detect if this is a BasicUser or BasicNonUser
      (json \ "kind").asOpt[NonUserKind] match {
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
  pictureName: String) extends BasicUserLikeEntity

object BasicUser {
  implicit val userExternalIdFormat = ExternalId.format[User]

  // Be aware that BasicUserLikeEntity uses the `kind` field to detect if its a BasicUser or BasicNonUser
  implicit val basicUserFormat = (
      (__ \ 'id).format[ExternalId[User]] and
      (__ \ 'firstName).format[String] and
      (__ \ 'lastName).format[String] and
      (__ \ 'pictureName).format[String]
  )(BasicUser.apply, unlift(BasicUser.unapply))

  def fromUser(user: User): BasicUser = {
    BasicUser(
      externalId = user.externalId,
      firstName = user.firstName,
      lastName = user.lastName,
      pictureName = user.pictureName.map(_+ ".jpg").getOrElse("0.jpg") // need support for default image
    )
  }

  def toByteArray(basicUser: BasicUser): Array[Byte] = {
    val bos = new ByteArrayOutputStream()
    val oos = new OutputStreamDataOutput(bos)
    oos.writeByte(1)      // version
    oos.writeString(basicUser.externalId.toString)
    oos.writeString(basicUser.firstName)
    oos.writeString(basicUser.lastName)
    oos.writeString(basicUser.pictureName)
    oos.close()
    bos.close()
    bos.toByteArray()
  }

  def fromByteArray(bytes: Array[Byte], offset: Int, length: Int): BasicUser = {
    val in = new InputStreamDataInput(new ByteArrayInputStream(bytes, offset, length))

    val version = in.readByte().toInt
    if (version != 1 ) {
      throw new Exception(s"invalid data [version=${version}]")
    }

    BasicUser(
      externalId = ExternalId[User](in.readString),
      firstName = in.readString,
      lastName = in.readString,
      pictureName = in.readString
    )
  }
}

case class BasicUserUserIdKey(userId: Id[User]) extends Key[BasicUser] {
  override val version = 5
  val namespace = "basic_user_userid"
  def toKey(): String = userId.id.toString
}

class BasicUserUserIdCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[BasicUserUserIdKey, BasicUser](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings:_*)


case class BasicUserWithUserId(
  id: Id[User],
  firstName: String,
  lastName: String,
  pictureName: String
) extends BasicUserLikeEntity

object BasicUserWithUserId {
  implicit val userIdFormat = Id.format[User]

  implicit val basicUserWithUserIdFormat = (
      (__ \ 'id).format[Id[User]] and
      (__ \ 'firstName).format[String] and
      (__ \ 'lastName).format[String] and
      (__ \ 'pictureName).format[String]
  )(BasicUserWithUserId.apply, unlift(BasicUserWithUserId.unapply))

  def fromBasicUserAndId(user: BasicUser, id: Id[User]): BasicUserWithUserId = {
    BasicUserWithUserId(id, user.firstName, user.lastName, user.pictureName)
  }
}
