package com.keepit.model

import scala.concurrent.duration._

import org.joda.time.DateTime

import com.keepit.common.cache.{ JsonCacheImpl, FortyTwoCachePlugin, Key, PrimitiveCacheImpl, CacheStatistics }
import com.keepit.common.logging.AccessLog
import com.keepit.common.db._
import com.keepit.common.time._
import com.keepit.serializer.SocialUserSerializer.userSerializer

import play.api.libs.functional.syntax._
import play.api.libs.json._
import securesocial.core.SocialUser
import com.keepit.social.{ SocialNetworks, SocialNetworkType, SocialId }

case class SocialUserInfo(
    id: Option[Id[SocialUserInfo]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    userId: Option[Id[User]] = None,
    fullName: String,
    pictureUrl: Option[String] = None,
    profileUrl: Option[String] = None,
    state: State[SocialUserInfo] = SocialUserInfoStates.CREATED,
    socialId: SocialId,
    networkType: SocialNetworkType,
    credentials: Option[SocialUser] = None,
    lastGraphRefresh: Option[DateTime] = Some(currentDateTime),
    username: Option[String] = None,
    seq: SequenceNumber[SocialUserInfo] = SequenceNumber.ZERO) extends ModelWithState[SocialUserInfo] with ModelWithSeqNumber[SocialUserInfo] {
  def withId(id: Id[SocialUserInfo]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def reset() = copy(state = SocialUserInfoStates.CREATED, credentials = None)
  def withUser(user: User) = copy(userId = Some(user.id.get)) //want to make sure the user has an id, fail hard if not!
  def withCredentials(credentials: SocialUser) = copy(credentials = Some(credentials)) //want to make sure the user has an id, fail hard if not!
  def withState(state: State[SocialUserInfo]) = copy(state = state)
  def withLastGraphRefresh(lastGraphRefresh: Option[DateTime] = Some(currentDateTime)) = copy(lastGraphRefresh = lastGraphRefresh)
  def getPictureUrl(preferredWidth: Int = 50, preferredHeight: Int = 50): Option[String] = networkType match {
    case SocialNetworks.FACEBOOK =>
      Some(s"https://graph.facebook.com/v2.0/$socialId/picture?width=$preferredWidth&height=$preferredHeight")
    case _ => pictureUrl
  }
  def getProfileUrl: Option[String] = profileUrl orElse (networkType match {
    case SocialNetworks.FACEBOOK => Some(s"https://www.facebook.com/$socialId")
    case _ => None
  })
  override def toString(): String = s"SocialUserInfo[Id=$id,User=$userId,Name=$fullName,network=$networkType,socialId=$socialId,state=$state]"
}

object SocialUserInfo {
  import com.keepit.serializer.SocialUserSerializer._
  implicit val format = (
    (__ \ 'id).formatNullable(Id.format[SocialUserInfo]) and
    (__ \ 'createdAt).format(DateTimeJsonFormat) and
    (__ \ 'updatedAt).format(DateTimeJsonFormat) and
    (__ \ 'userId).formatNullable(Id.format[User]) and
    (__ \ 'fullName).format[String] and
    (__ \ 'pictureUrl).formatNullable[String] and
    (__ \ 'profileUrl).formatNullable[String] and
    (__ \ 'state).format(State.format[SocialUserInfo]) and
    (__ \ 'socialId).format[String].inmap(SocialId.apply, unlift(SocialId.unapply)) and
    (__ \ 'networkType).format[SocialNetworkType] and
    (__ \ 'credentials).formatNullable[SocialUser] and
    (__ \ 'lastGraphRefresh).formatNullable[DateTime] and
    (__ \ 'username).formatNullable[String] and
    (__ \ 'seq).format(SequenceNumber.format[SocialUserInfo])
  )(SocialUserInfo.apply, unlift(SocialUserInfo.unapply))
}

case class SocialUserBasicInfo(
    id: Id[SocialUserInfo],
    userId: Option[Id[User]],
    fullName: String,
    pictureUrl: Option[String],
    socialId: SocialId,
    networkType: SocialNetworkType) {

  def getPictureUrl(preferredWidth: Int = 50, preferredHeight: Int = 50): Option[String] = networkType match {
    case SocialNetworks.FACEBOOK =>
      Some(s"https://graph.facebook.com/v2.0/$socialId/picture?width=$preferredWidth&height=$preferredHeight")
    case _ => pictureUrl
  }
}

object SocialUserBasicInfo {
  // This is an intentionally compact representation to support storing large social graphs
  implicit val format = Format[SocialUserBasicInfo](
    __.read[Seq[JsValue]].map {
      case Seq(JsNumber(id), JsNumber(userId), JsString(fullName), JsString(pictureUrl), JsString(socialId), JsString(networkType)) =>
        SocialUserBasicInfo(
          Id[SocialUserInfo](id.toLong),
          Some(userId).filter(_ != 0).map(id => Id[User](id.toLong)),
          fullName,
          Some(pictureUrl).filterNot(_.isEmpty),
          SocialId(socialId),
          SocialNetworkType(networkType))
    },
    new Writes[SocialUserBasicInfo] {
      def writes(o: SocialUserBasicInfo): JsValue =
        Json.arr(o.id.id, o.userId.getOrElse(Id(0)).id, o.fullName, o.pictureUrl.getOrElse[String](""), o.socialId.id, o.networkType.name)
    })

  def fromSocialUser(sui: SocialUserInfo): SocialUserBasicInfo =
    SocialUserBasicInfo(sui.id.get, sui.userId, sui.fullName, sui.pictureUrl, sui.socialId, sui.networkType)
}

case class SocialUserInfoCountKey() extends Key[Int] {
  override val version = 1
  val namespace = "social_user_info_count"
  def toKey(): String = "all"
}

class SocialUserInfoCountCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends PrimitiveCacheImpl[SocialUserInfoCountKey, Int](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

//todo(eishay): removed references to this cache, if it fixes problem with user reg, drop it all together.
case class SocialUserInfoUserKey(userId: Id[User]) extends Key[Seq[SocialUserInfo]] {
  val namespace = "social_user_info_by_userid"
  override val version = 8
  def toKey(): String = userId.id.toString
}

class SocialUserInfoUserCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[SocialUserInfoUserKey, Seq[SocialUserInfo]](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

case class SocialUserKey(userId: Id[User]) extends Key[Seq[SocialUser]] {
  val namespace = "social_user_by_userid"
  override val version = 1
  def toKey(): String = userId.id.toString
}

class SocialUserCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[SocialUserKey, Seq[SocialUser]](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

case class SocialUserInfoNetworkKey(networkType: SocialNetworkType, id: SocialId) extends Key[SocialUserInfo] {
  override val version = 5
  val namespace = "social_user_info_by_network_and_id"
  def toKey(): String = networkType.name.toString + "_" + id.id
}

class SocialUserInfoNetworkCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[SocialUserInfoNetworkKey, SocialUserInfo](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

case class SocialUserNetworkKey(networkType: SocialNetworkType, id: SocialId) extends Key[SocialUser] {
  override val version = 1
  val namespace = "social_user_by_network_and_id"
  def toKey(): String = networkType.name.toString + "_" + id.id
}

class SocialUserNetworkCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[SocialUserNetworkKey, SocialUser](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

case class SocialUserBasicInfoKey(id: Id[SocialUserInfo]) extends Key[SocialUserBasicInfo] {
  val namespace = "social_user_basic_info"
  override val version = 1
  def toKey(): String = id.id.toString
}

class SocialUserBasicInfoCache(stats: CacheStatistics, accessLog: AccessLog, inner: (FortyTwoCachePlugin, Duration), outer: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[SocialUserBasicInfoKey, SocialUserBasicInfo](stats, accessLog, inner, outer: _*)

object SocialUserInfoStates {
  val CREATED = State[SocialUserInfo]("created")
  val TOKEN_EXPIRED = State[SocialUserInfo]("token_expired")
  val FETCHED_USING_FRIEND = State[SocialUserInfo]("fetched_using_friend")
  val FETCHED_USING_SELF = State[SocialUserInfo]("fetched_using_self")
  val FETCH_FAIL = State[SocialUserInfo]("fetch_fail")
  val USER_NOT_FOUND = State[SocialUserInfo]("user_not_found")
  val APP_NOT_AUTHORIZED = State[SocialUserInfo]("app_not_authorized")
  val INACTIVE = State[SocialUserInfo]("inactive")
}
