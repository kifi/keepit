package com.keepit.model

import scala.concurrent.duration._

import org.joda.time.DateTime

import com.keepit.common.cache.{JsonCacheImpl, FortyTwoCachePlugin, Key, PrimitiveCacheImpl}
import com.keepit.common.db._
import com.keepit.common.time._

import play.api.libs.functional.syntax._
import play.api.libs.json._
import securesocial.core.SocialUser
import com.keepit.social.{SocialNetworks, SocialNetworkType, SocialId}

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
  lastGraphRefresh: Option[DateTime] = Some(currentDateTime)
) extends Model[SocialUserInfo] {
  def withId(id: Id[SocialUserInfo]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def reset() = copy(state = SocialUserInfoStates.CREATED, credentials = None)
  def withUser(user: User) = copy(userId = Some(user.id.get))//want to make sure the user has an id, fail hard if not!
  def withCredentials(credentials: SocialUser) = copy(credentials = Some(credentials))//want to make sure the user has an id, fail hard if not!
  def withState(state: State[SocialUserInfo]) = copy(state = state)
  def withLastGraphRefresh(lastGraphRefresh : Option[DateTime] = Some(currentDateTime)) = copy(lastGraphRefresh = lastGraphRefresh)
  def getPictureUrl(preferredWidth: Int = 50, preferredHeight: Int = 50): Option[String] = networkType match {
    case SocialNetworks.FACEBOOK =>
      Some(s"http://graph.facebook.com/$socialId/picture?width=$preferredWidth&height=$preferredHeight")
    case _ => pictureUrl
  }
  def getProfileUrl: Option[String] = profileUrl orElse (networkType match {
    case SocialNetworks.FACEBOOK => Some(s"http://facebook.com/$socialId")
    case _ => None
  })
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
    (__ \ 'networkType).format[String].inmap(SocialNetworkType.apply, unlift(SocialNetworkType.unapply)) and
    (__ \ 'credentials).formatNullable[SocialUser] and
    (__ \ 'lastGraphRefresh).formatNullable[DateTime]
  )(SocialUserInfo.apply, unlift(SocialUserInfo.unapply))
}

case class SocialUserInfoCountKey() extends Key[Int] {
  override val version = 0
  val namespace = "social_user_info_count"
  def toKey(): String = "all"
}

class SocialUserInfoCountCache(innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends PrimitiveCacheImpl[SocialUserInfoCountKey, Int](innermostPluginSettings, innerToOuterPluginSettings:_*)

case class SocialUserInfoUserKey(userId: Id[User]) extends Key[Seq[SocialUserInfo]] {
  val namespace = "social_user_info_by_userid"
  override val version = 3
  def toKey(): String = userId.id.toString
}

class SocialUserInfoUserCache(innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[SocialUserInfoUserKey, Seq[SocialUserInfo]](innermostPluginSettings, innerToOuterPluginSettings:_*)

case class SocialUserInfoNetworkKey(networkType: SocialNetworkType, id: SocialId) extends Key[SocialUserInfo] {
  override val version = 2
  val namespace = "social_user_info_by_network_and_id"
  def toKey(): String = networkType.name.toString + "_" + id.id
}

class SocialUserInfoNetworkCache(innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[SocialUserInfoNetworkKey, SocialUserInfo](innermostPluginSettings, innerToOuterPluginSettings:_*)

object SocialUserInfoStates {
  val CREATED = State[SocialUserInfo]("created")
  val FETCHED_USING_FRIEND = State[SocialUserInfo]("fetched_using_friend")
  val FETCHED_USING_SELF = State[SocialUserInfo]("fetched_using_self")
  val FETCH_FAIL = State[SocialUserInfo]("fetch_fail")
  val APP_NOT_AUTHORIZED = State[SocialUserInfo]("app_not_authorized")
  val INACTIVE = State[SocialUserInfo]("inactive")
}
