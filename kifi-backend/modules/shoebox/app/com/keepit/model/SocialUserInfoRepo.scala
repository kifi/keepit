package com.keepit.model

import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.db.slick._
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.time._
import securesocial.core.SocialUser
import org.joda.time.DateTime
import com.keepit.social.{SocialNetworkType, SocialId}


@ImplementedBy(classOf[SocialUserInfoRepoImpl])
trait SocialUserInfoRepo extends Repo[SocialUserInfo] {
  def getByUser(id: Id[User])(implicit session: RSession): Seq[SocialUserInfo]
  def get(id: SocialId, networkType: SocialNetworkType)(implicit session: RSession): SocialUserInfo
  def getUnprocessed()(implicit session: RSession): Seq[SocialUserInfo]
  def getNeedToBeRefreshed()(implicit session: RSession): Seq[SocialUserInfo]
  def getOpt(id: SocialId, networkType: SocialNetworkType)(implicit session: RSession): Option[SocialUserInfo]
}

@Singleton
class SocialUserInfoRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock,
    val userCache: SocialUserInfoUserCache,
    val countCache: SocialUserInfoCountCache,
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

  override def count(implicit session: RSession): Int = {
    countCache.getOrElse(SocialUserInfoCountKey()) {
      super.count
    }
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
