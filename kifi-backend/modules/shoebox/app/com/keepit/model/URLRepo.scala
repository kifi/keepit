package com.keepit.model

import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.Id
import com.keepit.common.time.Clock

@ImplementedBy(classOf[URLRepoImpl])
trait URLRepo extends Repo[URL] {
  def get(url: String)(implicit session: RSession): Option[URL]
  def getByDomain(domain: String)(implicit session: RSession): List[URL]
  def getByNormUri(normalizedUriId: Id[NormalizedURI])(implicit session: RSession): Seq[URL]
  def getRenormalizationList(lastProcessedId: Id[URL], domain: Option[String] = None, fetchSize: Int)(implicit session: RSession): Seq[URL]
  def getLastRenormalizationId(domain: Option[String] = None)(implicit session: RSession): Option[Id[URL]]
}

@Singleton
class URLRepoImpl @Inject() (val db: DataBaseComponent, val clock: Clock) extends DbRepo[URL] with URLRepo {
  import FortyTwoTypeMappers._
  import scala.slick.lifted.Query
  import db.Driver.Implicit._
  import DBSession._

  override val table = new RepoTable[URL](db, "url") {
    def url = column[String]("url", O.NotNull)
    def domain = column[String]("domain", O.Nullable)
    def normalizedUriId = column[Id[NormalizedURI]]("normalized_uri_id", O.NotNull)
    def history = column[Seq[URLHistory]]("history", O.NotNull)
    def renormalizationCheck = column[Boolean]("renormalization_check")
    def * = id.? ~ createdAt ~ updatedAt ~ url ~ domain.? ~ normalizedUriId ~ history ~ state ~ renormalizationCheck.? <> (URL, URL.unapply _)
  }

  def get(url: String)(implicit session: RSession): Option[URL] =
    (for(u <- table if u.url === url && u.state === URLStates.ACTIVE) yield u).firstOption

  def getByDomain(domain: String)(implicit session: RSession): List[URL] =
    (for(u <- table if u.domain === domain && u.state === URLStates.ACTIVE) yield u).list

  def getByNormUri(normalizedUriId: Id[NormalizedURI])(implicit session: RSession): Seq[URL] =
    (for(u <- table if u.normalizedUriId === normalizedUriId && u.state === URLStates.ACTIVE) yield u).list
    
  def getRenormalizationList(lastProcessedId: Id[URL], domain: Option[String] = None, fetchSize: Int)(implicit session: RSession): Seq[URL] =
    if (domain.isDefined) (for(u <- table if u.renormalizationCheck =!= true && u.domain === domain.get && u.id > lastProcessedId) yield u).sortBy(_.id).take(fetchSize).list
    else (for(u <- table if u.renormalizationCheck =!= true && u.id > lastProcessedId) yield u).sortBy(_.id).take(fetchSize).list
    
  def getLastRenormalizationId(domain: Option[String] = None)(implicit session: RSession): Option[Id[URL]] =
    if (domain.isDefined) (for (u <- table if u.renormalizationCheck === true && u.domain === domain.get) yield u.id).sortBy(x => x).list.lastOption
    else (for (u <- table if u.renormalizationCheck === true) yield u.id).sortBy(x => x).list.lastOption
}
