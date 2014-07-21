package com.keepit.model

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
  def invalidateByUser(userId: Id[User])(implicit s: RWSession): Int
}

@Singleton
class UserSessionRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock,
    val externalIdCache: UserSessionExternalIdCache) extends DbRepo[UserSession] with UserSessionRepo with ExternalIdColumnDbFunction[UserSession] with Logging {

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
    externalIdCache.set(UserSessionExternalIdKey(userSession.externalId), userSession)
  }

  override def deleteCache(userSession: UserSession)(implicit session: RSession): Unit = {
    externalIdCache.remove(UserSessionExternalIdKey(userSession.externalId))
  }

  override def getOpt(id: ExternalId[UserSession])(implicit session: RSession): Option[UserSession] = {
    externalIdCache.getOrElseOpt(UserSessionExternalIdKey(id)) {
      (for (f <- rows if f.externalId === id) yield f).firstOption
    }
  }

  def invalidateByUser(userId: Id[User])(implicit s: RWSession): Int = {
    (for (s <- rows if s.userId === userId) yield s.externalId).list.foreach { id =>
      externalIdCache.remove(UserSessionExternalIdKey(id))
    }
    (for (s <- rows if s.userId === userId) yield (s.state, s.updatedAt))
      .update(UserSessionStates.INACTIVE -> clock.now())
  }

}
