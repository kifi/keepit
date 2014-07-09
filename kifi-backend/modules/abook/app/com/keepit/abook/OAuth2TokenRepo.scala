package com.keepit.abook

import com.keepit.common.db.slick._
import com.keepit.model.{User, OAuth2TokenIssuer, OAuth2Token}
import com.google.inject.{ImplementedBy, Inject}
import com.keepit.common.time.Clock
import com.keepit.common.logging.Logging
import com.keepit.common.db.Id
import org.joda.time.DateTime
import com.keepit.common.db.slick.DBSession.RSession

@ImplementedBy(classOf[OAuth2TokenRepoImpl])
trait OAuth2TokenRepo extends Repo[OAuth2Token] {
  def getById(id:Id[OAuth2Token])(implicit session:RSession):Option[OAuth2Token]
}


class OAuth2TokenRepoImpl @Inject() (val db:DataBaseComponent, val clock: Clock) extends DbRepo[OAuth2Token] with OAuth2TokenRepo with Logging {

    import DBSession._
  import db.Driver.simple._


  type RepoImpl = OAuth2TokenTable
  class OAuth2TokenTable(tag: Tag) extends RepoTable[OAuth2Token](db, tag, "oauth2_token") {

    def userId       = column[Id[User]]("user_id", O.NotNull)
    def issuer       = column[OAuth2TokenIssuer]("issuer", O.NotNull)
    def scope        = column[String]("scope", O.Nullable)
    def tokenType    = column[String]("token_type", O.Nullable)
    def accessToken  = column[String]("access_token", O.NotNull)
    def expiresIn    = column[Int]("expires_in", O.Nullable)
    def refreshToken = column[String]("refresh_token", O.Nullable)
    def autoRefresh  = column[Boolean]("auto_refresh", O.NotNull)
    def lastRefreshedAt = column[DateTime]("last_refreshed_at", O.Nullable)
    def idToken      = column[String]("id_token", O.Nullable)
    def rawToken     = column[String]("raw_token", O.Nullable)
    def * = (id.?, createdAt, updatedAt, state, userId, issuer, scope.?, tokenType.?, accessToken, expiresIn.?, refreshToken.?, autoRefresh, lastRefreshedAt.?, idToken.?, rawToken.?) <> ((OAuth2Token.apply _).tupled, OAuth2Token.unapply _)
  }
  def table(tag: Tag) = new OAuth2TokenTable(tag)

  def getById(id: Id[OAuth2Token])(implicit session: RSession): Option[OAuth2Token] = {
    (for(t <- rows if t.id === id) yield t).firstOption
  }

  override def deleteCache(model: OAuth2Token)(implicit session: RSession): Unit = {}
  override def invalidateCache(model: OAuth2Token)(implicit session: RSession): Unit = {}
}
