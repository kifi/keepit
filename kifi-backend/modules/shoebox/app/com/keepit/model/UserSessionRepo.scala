package com.keepit.model

import com.keepit.model.cache.{ UserSessionViewExternalIdKey, UserSessionViewExternalIdCache }
import com.keepit.model.view.UserSessionView
import org.joda.time.DateTime

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick._
import com.keepit.common.db.{ Id, ExternalId }
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.social.{ SocialNetworkType, SocialId }

@ImplementedBy(classOf[UserSessionRepoImpl])
trait UserSessionRepo extends Repo[UserSession] with ExternalIdColumnFunction[UserSession] {
  def getViewOpt(id: ExternalId[UserSession])(implicit session: RSession): Option[UserSessionView]
  def invalidateByUser(userId: Id[User])(implicit s: RWSession): Int
}

@Singleton
class UserSessionRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock,
    val externalIdCache: UserSessionViewExternalIdCache) extends DbRepo[UserSession] with UserSessionRepo with ExternalIdColumnDbFunction[UserSession] with Logging {

  import db.Driver.simple._

  type RepoImpl = UserSessionTable
  class UserSessionTable(tag: Tag) extends RepoTable[UserSession](db, tag, "user_session") with ExternalIdColumn[UserSession] {
    def userId = column[Option[Id[User]]]("user_id", O.Nullable)
    def socialId = column[SocialId]("social_id", O.NotNull)
    def expires = column[DateTime]("expires", O.NotNull)
    def provider = column[SocialNetworkType]("provider", O.NotNull)
    def * = (id.?, userId, externalId, socialId, provider, expires, state, createdAt, updatedAt) <>
      ((UserSession.apply _).tupled, UserSession.unapply _)
  }

  def table(tag: Tag) = new UserSessionTable(tag)
  initTable

  override def invalidateCache(userSession: UserSession)(implicit session: RSession): Unit = {
    externalIdCache.set(UserSessionViewExternalIdKey(userSession.externalId), userSession.toUserSessionView)
  }

  override def deleteCache(userSession: UserSession)(implicit session: RSession): Unit = {
    externalIdCache.remove(UserSessionViewExternalIdKey(userSession.externalId))
  }

  override def getViewOpt(id: ExternalId[UserSession])(implicit session: RSession): Option[UserSessionView] = {
    externalIdCache.getOrElseOpt(UserSessionViewExternalIdKey(id)) {
      (for (f <- rows if f.externalId === id) yield f).firstOption.map(_.toUserSessionView)
    }
  }

  def invalidateByUser(userId: Id[User])(implicit s: RWSession): Int = {
    (for (s <- rows if s.userId === userId) yield s.externalId).list.foreach { id =>
      externalIdCache.remove(UserSessionViewExternalIdKey(id))
    }
    (for (s <- rows if s.userId === userId) yield (s.state, s.updatedAt))
      .update(UserSessionStates.INACTIVE -> clock.now())
  }

}
