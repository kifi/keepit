package com.keepit.model

import scala.concurrent.duration._

import org.joda.time.DateTime

import com.google.inject.{Inject, ImplementedBy, Singleton}
import com.keepit.common.cache.{JsonCacheImpl, FortyTwoCachePlugin, Key}
import com.keepit.common.db._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.db.slick._
import com.keepit.common.social.{SocialNetworks, SocialId, SocialNetworkType}
import com.keepit.common.time._

import play.api.libs.functional.syntax._
import play.api.libs.json._
import securesocial.core.SocialUser

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
    (__ \ 'createdAt).format[DateTime] and
    (__ \ 'updatedAt).format[DateTime] and
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

@ImplementedBy(classOf[SocialUserInfoRepoImpl])
trait SocialUserInfoRepo extends Repo[SocialUserInfo] {
  def getByUser(id: Id[User])(implicit session: RSession): Seq[SocialUserInfo]
  def get(id: SocialId, networkType: SocialNetworkType)(implicit session: RSession): SocialUserInfo
  def getUnprocessed()(implicit session: RSession): Seq[SocialUserInfo]
  def getNeedToBeRefreshed()(implicit session: RSession): Seq[SocialUserInfo]
  def getOpt(id: SocialId, networkType: SocialNetworkType)(implicit session: RSession): Option[SocialUserInfo]
}

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

@Singleton
class SocialUserInfoRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock,
  val userCache: SocialUserInfoUserCache,
  val networkCache: SocialUserInfoNetworkCache)
    extends DbRepo[SocialUserInfo] with SocialUserInfoRepo {

  import DBSession._
  import FortyTwoTypeMappers._
  import db.Driver.Implicit._

  override val table = new RepoTable[SocialUserInfo](db, "social_user_info") {
    def userId = column[Id[User]]("user_id", O.Nullable)
    def fullName = column[String]("full_name", O.NotNull)
    def socialId = column[SocialId]("social_id", O.NotNull)
    def networkType = column[SocialNetworkType]("network_type", O.NotNull)
    def credentials = column[SocialUser]("credentials", O.Nullable)
    def lastGraphRefresh = column[DateTime]("last_graph_refresh", O.Nullable)
    def pictureUrl = column[String]("picture_url", O.Nullable)
    def profileUrl = column[String]("profile_url", O.Nullable)
    def * = id.? ~ createdAt ~ updatedAt ~ userId.? ~ fullName ~ pictureUrl.? ~ profileUrl.? ~ state ~ socialId ~
        networkType ~ credentials.? ~ lastGraphRefresh.? <> (SocialUserInfo.apply _, SocialUserInfo.unapply _)
  }

  override def invalidateCache(socialUser: SocialUserInfo)(implicit session: RSession) = {
    socialUser.userId map {userId => userCache.remove(SocialUserInfoUserKey(userId))}
    networkCache.remove(SocialUserInfoNetworkKey(socialUser.networkType, socialUser.socialId))
    socialUser
  }

  def getByUser(userId: Id[User])(implicit session: RSession): Seq[SocialUserInfo] =
    userCache.getOrElse(SocialUserInfoUserKey(userId)) {
      (for(f <- table if f.userId === userId) yield f).list
    }

  def get(id: SocialId, networkType: SocialNetworkType)(implicit session: RSession): SocialUserInfo =
    networkCache.getOrElse(SocialUserInfoNetworkKey(networkType, id)) {
      (for(f <- table if f.socialId === id && f.networkType === networkType) yield f).first
    }

  def getUnprocessed()(implicit session: RSession): Seq[SocialUserInfo] = {
    val UNPROCESSED_STATE = SocialUserInfoStates.CREATED :: SocialUserInfoStates.FETCHED_USING_FRIEND :: Nil
    (for(f <- table if (f.state.inSet(UNPROCESSED_STATE) && f.credentials.isNotNull)) yield f).list
  }

  def getNeedToBeRefreshed()(implicit session: RSession): Seq[SocialUserInfo] =
    (for(f <- table if f.userId.isNotNull && f.credentials.isNotNull &&
      (f.lastGraphRefresh.isNull || f.lastGraphRefresh < currentDateTime.minusDays(15))) yield f).list

  def getOpt(id: SocialId, networkType: SocialNetworkType)(implicit session: RSession): Option[SocialUserInfo] =
    networkCache.getOrElseOpt(SocialUserInfoNetworkKey(networkType, id)) {
      (for(f <- table if f.socialId === id && f.networkType === networkType) yield f).firstOption
    }

}

object SocialUserInfoStates {
  val CREATED = State[SocialUserInfo]("created")
  val FETCHED_USING_FRIEND = State[SocialUserInfo]("fetched_using_friend")
  val FETCHED_USING_SELF = State[SocialUserInfo]("fetched_using_self")
  val FETCH_FAIL = State[SocialUserInfo]("fetch_fail")
  val APP_NOT_AUTHORIZED = State[SocialUserInfo]("app_not_authorized")
  val INACTIVE = State[SocialUserInfo]("inactive")
}
