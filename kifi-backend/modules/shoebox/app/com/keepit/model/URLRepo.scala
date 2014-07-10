package com.keepit.model

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.Id
import com.keepit.common.time.Clock

@ImplementedBy(classOf[URLRepoImpl])
trait URLRepo extends Repo[URL] {
  def get(url: String, uriId: Id[NormalizedURI])(implicit session: RSession): Option[URL]
  def getByDomain(domain: String)(implicit session: RSession): List[URL]
  def getByDomainRegex(regex: String)(implicit session: RSession): List[URL]
  def getByURLRegex(regex: String)(implicit session: RSession): List[URL]
  def getByNormUri(normalizedUriId: Id[NormalizedURI])(implicit session: RSession): Seq[URL]
}

@Singleton
class URLRepoImpl @Inject() (val db: DataBaseComponent, val clock: Clock) extends DbRepo[URL] with URLRepo {
  import db.Driver.simple._

  type RepoImpl = URLTable
  class URLTable(tag: Tag) extends RepoTable[URL](db, tag, "url") {
    def url = column[String]("url", O.NotNull)
    def domain = column[String]("domain", O.Nullable)
    def normalizedUriId = column[Id[NormalizedURI]]("normalized_uri_id", O.NotNull)
    def history = column[Seq[URLHistory]]("history", O.NotNull)
    def * = (id.?, createdAt, updatedAt, url, domain.?, normalizedUriId, history, state) <> ((URL.apply _).tupled, URL.unapply _)
  }

  def table(tag: Tag) = new URLTable(tag)
  initTable()

  override def deleteCache(model: URL)(implicit session: RSession): Unit = {}
  override def invalidateCache(model: URL)(implicit session: RSession): Unit = {}

  def get(url: String, uriId: Id[NormalizedURI])(implicit session: RSession): Option[URL] = {
    val list = (for (u <- rows if u.normalizedUriId === uriId && u.url === url && u.state === URLStates.ACTIVE) yield u).list
    list.find(_.url == url)
  }

  def getByDomain(domain: String)(implicit session: RSession): List[URL] =
    (for (u <- rows if u.domain === domain && u.state === URLStates.ACTIVE) yield u).list

  def getByDomainRegex(regex: String)(implicit session: RSession): List[URL] =
    (for (u <- rows if (u.domain like regex) && u.state === URLStates.ACTIVE) yield u).list

  def getByURLRegex(regex: String)(implicit session: RSession): List[URL] =
    (for (u <- rows if (u.url like regex) && u.state === URLStates.ACTIVE) yield u).list

  def getByNormUri(normalizedUriId: Id[NormalizedURI])(implicit session: RSession): Seq[URL] =
    (for (u <- rows if u.normalizedUriId === normalizedUriId && u.state === URLStates.ACTIVE) yield u).list
}
