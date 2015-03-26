package com.keepit.classify

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.slick.{ Repo, DbRepo, DataBaseComponent }
import com.keepit.common.time._
import com.keepit.common.db.{ Id, State }
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db.slick.DBSession

@ImplementedBy(classOf[DomainRepoImpl])
trait DomainRepo extends Repo[Domain] {
  def get(domain: String, excludeState: Option[State[Domain]] = Some(DomainStates.INACTIVE))(implicit session: RSession): Option[Domain]
  def getAllByName(domains: Seq[String], excludeState: Option[State[Domain]] = Some(DomainStates.INACTIVE))(implicit session: RSession): Seq[Domain]
  def getOverrides(excludeState: Option[State[Domain]] = Some(DomainStates.INACTIVE))(implicit session: RSession): Seq[Domain]
  def updateAutoSensitivity(domainIds: Seq[Id[Domain]], value: Option[Boolean])(implicit session: RWSession): Int
}

@Singleton
class DomainRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock,
    domainCache: DomainCache) extends DbRepo[Domain] with DomainRepo {
  import db.Driver.simple._

  //todo(martin) remove this default implementation so we force repos to implement it
  override def invalidateCache(domain: Domain)(implicit session: RSession): Unit = {
    domainCache.set(DomainKey(domain.hostname), domain)
  }

  override def deleteCache(domain: Domain)(implicit session: RSession): Unit = {
    domainCache.remove(DomainKey(domain.hostname))
  }

  type RepoImpl = DomainTable

  class DomainTable(tag: Tag) extends RepoTable[Domain](db, tag, "domain") {
    def autoSensitive = column[Option[Boolean]]("auto_sensitive", O.Nullable)
    def manualSensitive = column[Option[Boolean]]("manual_sensitive", O.Nullable)
    def hostname = column[String]("hostname", O.NotNull)
    def * = (id.?, hostname, autoSensitive, manualSensitive, state, createdAt, updatedAt) <> ((Domain.apply _).tupled, Domain.unapply _)
  }

  def table(tag: Tag) = new DomainTable(tag)
  initTable()

  def get(domain: String, excludeState: Option[State[Domain]] = Some(DomainStates.INACTIVE))(implicit session: RSession): Option[Domain] = {
    domainCache.getOrElseOpt(DomainKey(domain)) {
      (for (d <- rows if d.hostname === domain) yield d).firstOption
    } filter { d => excludeState.map(s => d.state != s).getOrElse(true) }
  }

  def getAllByName(domains: Seq[String], excludeState: Option[State[Domain]] = Some(DomainStates.INACTIVE))(implicit session: RSession): Seq[Domain] =
    (for (d <- rows if d.hostname.inSet(domains) && d.state =!= excludeState.orNull) yield d).list

  def getOverrides(excludeState: Option[State[Domain]] = Some(DomainStates.INACTIVE))(implicit session: RSession): Seq[Domain] =
    (for (d <- rows if d.state =!= excludeState.orNull && d.manualSensitive.isDefined) yield d).list

  def updateAutoSensitivity(domainIds: Seq[Id[Domain]], value: Option[Boolean])(implicit session: RWSession): Int = {
    val count = (for (d <- rows if d.id.inSet(domainIds)) yield (d.autoSensitive, d.updatedAt)).update(value -> clock.now())
    domainIds foreach { id => invalidateCache(get(id)) }
    count
  }
}
