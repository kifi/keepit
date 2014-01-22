package com.keepit.model

import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.db.slick._
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.time.Clock

@ImplementedBy(classOf[DeepLinkRepoImpl])
trait DeepLinkRepo extends Repo[DeepLink] {
  def getByUri(urlId: Id[NormalizedURI])(implicit session: RSession): Seq[DeepLink]
  def getByUrl(urlId: Id[URL])(implicit session: RSession): Seq[DeepLink]
  def getByToken(token: DeepLinkToken)(implicit session: RSession): Option[DeepLink]
}

@Singleton
class DeepLinkRepoImpl @Inject() (val db: DataBaseComponent, val clock: Clock) extends DbRepo[DeepLink] with DeepLinkRepo {
  import FortyTwoTypeMappers._
  import scala.slick.lifted.Query
  import db.Driver.Implicit._
  import DBSession._

  override val table = new RepoTable[DeepLink](db, "deep_link") {
    def initatorUserId = column[Id[User]]("initiator_user_id")
    def recipientUserId = column[Id[User]]("recipient_user_id")
    def uriId = column[Id[NormalizedURI]]("uri_id")
    def urlId = column[Id[URL]]("url_id")
    def deepLocator = column[DeepLocator]("deep_locator", O.NotNull)
    def token = column[DeepLinkToken]("token", O.NotNull)

    def * = id.? ~ createdAt ~ updatedAt ~ initatorUserId.? ~ recipientUserId.? ~ uriId.? ~ urlId.? ~ deepLocator ~ token ~ state <> (DeepLink, DeepLink.unapply _)
  }

  def getByUri(uriId: Id[NormalizedURI])(implicit session: RSession): Seq[DeepLink] =
    (for(b <- table if b.uriId === uriId) yield b).list

  def getByUrl(urlId: Id[URL])(implicit session: RSession): Seq[DeepLink] =
    (for(b <- table if b.urlId === urlId) yield b).list

  def getByToken(token: DeepLinkToken)(implicit session: RSession): Option[DeepLink] =
    (for(b <- table if b.token === token) yield b).firstOption
}
