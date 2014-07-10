package com.keepit.model

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.db.slick._
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.time.Clock

@ImplementedBy(classOf[DeepLinkRepoImpl])
trait DeepLinkRepo extends Repo[DeepLink] {
  def getByUri(urlId: Id[NormalizedURI])(implicit session: RSession): Seq[DeepLink]
  def getByUrl(urlId: Id[URL])(implicit session: RSession): Seq[DeepLink]
  def getByToken(token: DeepLinkToken)(implicit session: RSession): Option[DeepLink]
  def getByLocatorAndUser(locator: DeepLocator, recipientUserId: Id[User])(implicit session: RSession): DeepLink
}

@Singleton
class DeepLinkRepoImpl @Inject() (val db: DataBaseComponent, val clock: Clock) extends DbRepo[DeepLink] with DeepLinkRepo {

  import db.Driver.simple._

  type RepoImpl = DeepLinkTable
  class DeepLinkTable(tag: Tag) extends RepoTable[DeepLink](db, tag, "deep_link") {
    def initatorUserId = column[Id[User]]("initiator_user_id")
    def recipientUserId = column[Id[User]]("recipient_user_id")
    def uriId = column[Id[NormalizedURI]]("uri_id")
    def urlId = column[Id[URL]]("url_id")
    def deepLocator = column[DeepLocator]("deep_locator", O.NotNull)
    def token = column[DeepLinkToken]("token", O.NotNull)

    def * = (id.?, createdAt, updatedAt, initatorUserId.?, recipientUserId.?, uriId.?, urlId.?, deepLocator, token, state) <> ((DeepLink.apply _).tupled, DeepLink.unapply _)
  }

  def table(tag: Tag) = new DeepLinkTable(tag)
  initTable()

  override def invalidateCache(model: DeepLink)(implicit session: RSession): Unit = {}
  override def deleteCache(model: DeepLink)(implicit session: RSession): Unit = {}

  def getByUri(uriId: Id[NormalizedURI])(implicit session: RSession): Seq[DeepLink] =
    (for (b <- rows if b.uriId === uriId) yield b).list

  def getByUrl(urlId: Id[URL])(implicit session: RSession): Seq[DeepLink] =
    (for (b <- rows if b.urlId === urlId) yield b).list

  def getByToken(token: DeepLinkToken)(implicit session: RSession): Option[DeepLink] =
    (for (b <- rows if b.token === token) yield b).firstOption

  def getByLocatorAndUser(locator: DeepLocator, recipientUserId: Id[User])(implicit session: RSession): DeepLink = {
    (for (b <- rows if b.deepLocator === locator && b.recipientUserId === recipientUserId) yield b).first
  }
}
