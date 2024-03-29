package com.keepit.classify

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.slick.{ Repo, DbRepo, DataBaseComponent }
import com.keepit.common.time._
import com.keepit.common.db.{ Id, State }
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.mysql.jdbc.exceptions.MySQLIntegrityConstraintViolationException

@ImplementedBy(classOf[DomainRepoImpl])
trait DomainRepo extends Repo[Domain] {
  def get(domain: NormalizedHostname, excludeState: Option[State[Domain]] = Some(DomainStates.INACTIVE))(implicit session: RSession): Option[Domain]
  def getByIds(orgIds: Set[Id[Domain]])(implicit session: RSession): Map[Id[Domain], Domain]
  def getAllByName(domains: Seq[NormalizedHostname], excludeState: Option[State[Domain]] = Some(DomainStates.INACTIVE))(implicit session: RSession): Seq[Domain]
  def getOverrides(excludeState: Option[State[Domain]] = Some(DomainStates.INACTIVE))(implicit session: RSession): Seq[Domain]
  def updateAutoSensitivity(domainIds: Seq[Id[Domain]], value: Option[Boolean])(implicit session: RWSession): Int
  def intern(hostname: NormalizedHostname)(implicit session: RWSession): Domain
  def internAllByNames(domainNames: Set[NormalizedHostname])(implicit session: RWSession): Map[NormalizedHostname, Domain]
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
    def hostname = column[NormalizedHostname]("hostname", O.NotNull)
    def isEmailProvider = column[Boolean]("is_email_provider", O.NotNull)
    def * = (id.?, hostname, autoSensitive, manualSensitive, isEmailProvider, state, createdAt, updatedAt) <> ((Domain.apply _).tupled, Domain.unapply _)
  }

  def table(tag: Tag) = new DomainTable(tag)
  initTable()

  def get(domain: NormalizedHostname, excludeState: Option[State[Domain]] = Some(DomainStates.INACTIVE))(implicit session: RSession): Option[Domain] = {
    domainCache.getOrElseOpt(DomainKey(domain)) {
      (for (d <- rows if d.hostname === domain && d.state =!= excludeState.orNull) yield d).firstOption
    }
  }

  def intern(hostname: NormalizedHostname)(implicit session: RWSession): Domain = {
    domainCache.getOrElse(DomainKey(hostname)) {
      rows.filter(r => r.hostname === hostname).firstOption match {
        case Some(domain) if domain.isActive => domain
        case inactiveOpt => save(Domain(id = inactiveOpt.map(_.id.get), hostname = hostname))
      }
    }
  }

  def getByIds(domainIds: Set[Id[Domain]])(implicit session: RSession): Map[Id[Domain], Domain] = {
    val q = { for { row <- rows if row.id.inSet(domainIds) && row.state === DomainStates.ACTIVE } yield row }
    q.list.map { domain => domain.id.get -> domain }.toMap
  }

  def getAllByName(domains: Seq[NormalizedHostname], excludeState: Option[State[Domain]] = Some(DomainStates.INACTIVE))(implicit session: RSession): Seq[Domain] = {
    (for (d <- rows if d.hostname.inSet(domains) && d.state =!= excludeState.orNull) yield d).list
  }

  def getOverrides(excludeState: Option[State[Domain]] = Some(DomainStates.INACTIVE))(implicit session: RSession): Seq[Domain] =
    (for (d <- rows if d.state =!= excludeState.orNull && d.manualSensitive.isDefined) yield d).list

  def updateAutoSensitivity(domainIds: Seq[Id[Domain]], value: Option[Boolean])(implicit session: RWSession): Int = {
    val count = (for (d <- rows if d.id.inSet(domainIds)) yield (d.autoSensitive, d.updatedAt)).update(value -> clock.now())
    domainIds foreach { id => invalidateCache(get(id)) }
    count
  }

  def internAllByNames(domainNames: Set[NormalizedHostname])(implicit session: RWSession): Map[NormalizedHostname, Domain] = {
    val existingDomains = getAllByName(domainNames.toSeq, None).toSet

    val existingHostnames = existingDomains.map(_.hostname)

    val toBeInserted = domainNames -- existingHostnames

    val existingDomainByName = existingDomains.map { domain =>
      domain.state match {
        case DomainStates.INACTIVE => domain.hostname -> save(Domain(hostname = domain.hostname).copy(id = domain.id))
        case DomainStates.ACTIVE => domain.hostname -> domain
      }
    }.toMap
    val newDomainByName = toBeInserted.map { hostname =>
      try {
        hostname -> save(Domain(hostname = hostname))
      } catch {
        case e: MySQLIntegrityConstraintViolationException =>
          log.error(s"[internDomains]$hostname already exists. Existing=${existingHostnames.map(_.value)}. Desired=${domainNames.map(_.value)}")
          throw e
      }
    }

    existingDomainByName ++ newDomainByName
  }
}
