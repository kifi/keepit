package com.keepit.model

import scala.concurrent.duration._

import org.joda.time.DateTime

import com.google.inject.{Inject, ImplementedBy, Singleton}
import com.keepit.common.cache.{JsonCacheImpl, FortyTwoCachePlugin, Key}
import com.keepit.common.db._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.db.slick._
import com.keepit.common.social.SocialId
import com.keepit.common.social.SocialNetworkType
import com.keepit.common.time._
import com.keepit.serializer.SequenceFormat

import play.api.libs.json._
import securesocial.core.SocialUser

case class SocialUserInfo(
  id: Option[Id[SocialUserInfo]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  userId: Option[Id[User]] = None,
  fullName: String,
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
}

@ImplementedBy(classOf[SocialUserInfoRepoImpl])
trait SocialUserInfoRepo extends Repo[SocialUserInfo] {
  def getByUser(id: Id[User])(implicit session: RSession): Seq[SocialUserInfo]
  def get(id: SocialId, networkType: SocialNetworkType)(implicit session: RSession): SocialUserInfo
  def getUnprocessed()(implicit session: RSession): Seq[SocialUserInfo]
  def getNeedToBeRefreshed()(implicit session: RSession): Seq[SocialUserInfo]
  def getOpt(id: SocialId, networkType: SocialNetworkType)(implicit session: RSession): Option[SocialUserInfo]
}

import com.keepit.serializer.SocialUserInfoSerializer.socialUserInfoSerializer // Required implicit value
case class SocialUserInfoUserKey(userId: Id[User]) extends Key[Seq[SocialUserInfo]] {
  val namespace = "social_user_info_by_userid"
  override val version = 2
  def toKey(): String = userId.id.toString
}
class SocialUserInfoUserCache @Inject() (repo: FortyTwoCachePlugin)
  extends JsonCacheImpl[SocialUserInfoUserKey, Seq[SocialUserInfo]]((repo, 30 days))(SequenceFormat[SocialUserInfo])

case class SocialUserInfoNetworkKey(networkType: SocialNetworkType, id: SocialId) extends Key[SocialUserInfo] {
  val namespace = "social_user_info_by_network_and_id"
  def toKey(): String = networkType.name.toString + "_" + id.id
}

class SocialUserInfoNetworkCache @Inject() (repo: FortyTwoCachePlugin)
  extends JsonCacheImpl[SocialUserInfoNetworkKey, SocialUserInfo]((repo, 30 days))

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
    def * = id.? ~ createdAt ~ updatedAt ~ userId.? ~ fullName ~ state ~ socialId ~ networkType ~ credentials.? ~ lastGraphRefresh.? <> (SocialUserInfo, SocialUserInfo.unapply _)
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
    (for(f <- table if f.userId.isNotNull && (f.lastGraphRefresh.isNull || f.lastGraphRefresh < currentDateTime.minusDays(15))) yield f).list


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
