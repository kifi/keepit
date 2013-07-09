package com.keepit.model

import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.db.slick._
import com.keepit.common.db.{Id, ExternalId}
import com.keepit.common.time.Clock
import com.keepit.common.logging.Logging
import com.keepit.common.social.{SocialNetworkType, SocialId}
import org.joda.time.DateTime

@ImplementedBy(classOf[UserSessionRepoImpl])
trait UserSessionRepo extends Repo[UserSession] with ExternalIdColumnFunction[UserSession]

@Singleton
class UserSessionRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock,
  val externalIdCache: UserSessionExternalIdCache
) extends DbRepo[UserSession] with UserSessionRepo with ExternalIdColumnDbFunction[UserSession] with Logging {

  import DBSession._
  import FortyTwoTypeMappers._
  import db.Driver.Implicit._

  override lazy val table = new RepoTable[UserSession](db, "user_session") with ExternalIdColumn[UserSession] {
    def userId = column[Option[Id[User]]]("user_id", O.Nullable)
    def socialId = column[SocialId]("social_id", O.NotNull)
    def expires = column[DateTime]("expires", O.NotNull)
    def provider = column[SocialNetworkType]("provider", O.NotNull)
    def * = id.? ~ userId ~ externalId ~ socialId ~ provider ~ expires ~ state ~ createdAt ~ updatedAt <>
      (UserSession.apply _, UserSession.unapply _)
  }

  override def invalidateCache(userSession: UserSession)(implicit session: RSession): UserSession = {
    externalIdCache.set(UserSessionExternalIdKey(userSession.externalId), userSession)
    userSession
  }

  override def getOpt(id: ExternalId[UserSession])(implicit session: RSession): Option[UserSession] = {
    externalIdCache.getOrElseOpt(UserSessionExternalIdKey(id)) {
      (for(f <- externalIdColumn if f.externalId === id) yield f).firstOption
    }
  }

  override def get(id: ExternalId[UserSession])(implicit session: RSession): UserSession = getOpt(id).get

}
