package com.keepit.social

import scala.concurrent.duration.Duration
import com.keepit.common.cache.{JsonCacheImpl, FortyTwoCachePlugin, Key}
import com.keepit.common.cache.CacheStatistics
import com.keepit.common.logging.AccessLog
import com.keepit.common.db._
import com.keepit.model._
import com.keepit.common.cache.CacheStatistics
import com.keepit.common.logging.AccessLog
import play.api.libs.functional.syntax._
import play.api.libs.json._
import java.io.ByteArrayOutputStream
import org.apache.lucene.store.OutputStreamDataOutput
import org.apache.lucene.store.InputStreamDataInput
import java.io.ByteArrayInputStream

case class BasicUser(
  externalId: ExternalId[User],
  firstName: String,
  lastName: String,
  pictureName: String)

object BasicUser {
  implicit val userExternalIdFormat = ExternalId.format[User]
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
    val bos = new ByteArrayOutputStream();
    val oos = new OutputStreamDataOutput(bos);
    oos.writeByte(1)      // version
    oos.writeString(basicUser.externalId.toString)
    oos.writeString(basicUser.firstName)
    oos.writeString(basicUser.lastName)
    oos.writeString(basicUser.pictureName)
    oos.close();
    bos.close();
    bos.toByteArray();
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
