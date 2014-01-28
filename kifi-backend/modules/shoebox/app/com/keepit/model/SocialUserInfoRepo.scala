package com.keepit.model

import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.db.slick._
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.time._
import securesocial.core.SocialUser
import org.joda.time.DateTime
import com.keepit.social.{SocialNetworks, SocialNetworkType, SocialId}
import scala.reflect.ClassTag


@ImplementedBy(classOf[SocialUserInfoRepoImpl])
trait SocialUserInfoRepo extends Repo[SocialUserInfo] with RepoWithDelete[SocialUserInfo] {
  def getByUser(id: Id[User])(implicit session: RSession): Seq[SocialUserInfo]
  def getNotAuthorizedByUser(userId: Id[User])(implicit session: RSession): Seq[SocialUserInfo]
  def getSocialUserByUser(id: Id[User])(implicit session: RSession): Seq[SocialUser]
  def get(id: SocialId, networkType: SocialNetworkType)(implicit session: RSession): SocialUserInfo
  def getUnprocessed()(implicit session: RSession): Seq[SocialUserInfo]
  def getNeedToBeRefreshed()(implicit session: RSession): Seq[SocialUserInfo]
  def getOpt(id: SocialId, networkType: SocialNetworkType)(implicit session: RSession): Option[SocialUserInfo]
  def getSocialUserOpt(id: SocialId, networkType: SocialNetworkType)(implicit session: RSession): Option[SocialUser]
}

@Singleton
class SocialUserInfoRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock,
    val userCache: SocialUserInfoUserCache,
    val socialUserCache: SocialUserCache,
    val countCache: SocialUserInfoCountCache,
    val networkCache: SocialUserInfoNetworkCache,
    val socialUserNetworkCache: SocialUserNetworkCache)
  extends DbRepo[SocialUserInfo] with DbRepoWithDelete[SocialUserInfo] with SocialUserInfoRepo {

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

  private val UNPROCESSED_STATES = SocialUserInfoStates.CREATED :: SocialUserInfoStates.FETCHED_USING_FRIEND :: Nil
  private val REFRESHING_STATES = SocialUserInfoStates.FETCHED_USING_SELF :: SocialUserInfoStates.FETCH_FAIL :: Nil
  private val REFRESH_FREQUENCY = 15 // days


  override def invalidateCache(socialUser: SocialUserInfo)(implicit session: RSession) = deleteCache(socialUser)

  override def deleteCache(socialUser: SocialUserInfo)(implicit session: RSession): Unit = {
    socialUser.userId map { userId =>
      userCache.remove(SocialUserInfoUserKey(userId))
      socialUserCache.remove(SocialUserKey(userId))
    }
    networkCache.remove(SocialUserInfoNetworkKey(socialUser.networkType, socialUser.socialId))
    socialUserNetworkCache.remove(SocialUserNetworkKey(socialUser.networkType, socialUser.socialId))
    countCache.remove(SocialUserInfoCountKey())
  }

  override def count(implicit session: RSession): Int = {
    countCache.getOrElse(SocialUserInfoCountKey()) {
      super.count
    }
  }

  def getByUser(userId: Id[User])(implicit session: RSession): Seq[SocialUserInfo] =
    userCache.getOrElse(SocialUserInfoUserKey(userId)) {
      (for(f <- table if f.userId === userId) yield f).list
    }

  def getNotAuthorizedByUser(userId: Id[User])(implicit session: RSession): Seq[SocialUserInfo] =
      (for(f <- table if f.userId === userId && f.state === SocialUserInfoStates.APP_NOT_AUTHORIZED) yield f).list

  def getSocialUserByUser(userId: Id[User])(implicit session: RSession): Seq[SocialUser] =
    socialUserCache.getOrElse(SocialUserKey(userId)) {
      (for(f <- table if f.userId === userId) yield f).list.map(_.credentials).flatten.toSeq
    }

  def get(id: SocialId, networkType: SocialNetworkType)(implicit session: RSession): SocialUserInfo = try {
      networkCache.getOrElse(SocialUserInfoNetworkKey(networkType, id)) {
        (for(f <- table if f.socialId === id && f.networkType === networkType) yield f).first
      }
    } catch {
      case e: Throwable => throw new Exception(s"Can't get social user info for social id [$id] on network [$networkType]", e)
    }

  def getUnprocessed()(implicit session: RSession): Seq[SocialUserInfo] = {
    (for(f <- table if f.state.inSet(UNPROCESSED_STATES) && f.userId.isNotNull && f.credentials.isNotNull && f.networkType.inSet(SocialNetworks.REFRESHING) && f.createdAt < clock.now.minusMinutes(15)) yield f).list
  }

  def getNeedToBeRefreshed()(implicit session: RSession): Seq[SocialUserInfo] = {
    (for(f <- table if f.userId.isNotNull && f.credentials.isNotNull
      && f.networkType.inSet(SocialNetworks.REFRESHING) && f.state.inSet(REFRESHING_STATES)
      && (f.lastGraphRefresh.isNull || f.lastGraphRefresh < clock.now.minusDays(REFRESH_FREQUENCY))) yield f).list
  }

  def getOpt(id: SocialId, networkType: SocialNetworkType)(implicit session: RSession): Option[SocialUserInfo] =
    networkCache.getOrElseOpt(SocialUserInfoNetworkKey(networkType, id)) {
      (for(f <- table if f.socialId === id && f.networkType === networkType) yield f).firstOption
    }

  def getSocialUserOpt(id: SocialId, networkType: SocialNetworkType)(implicit session: RSession): Option[SocialUser] =
    socialUserNetworkCache.getOrElseOpt(SocialUserNetworkKey(networkType, id)) {
      (for(f <- table if f.socialId === id && f.networkType === networkType) yield f).firstOption.map(_.credentials).flatten
    }

}
