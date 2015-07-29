package com.keepit.classify

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.slick.{ Repo, DbRepo, DataBaseComponent }
import com.keepit.common.time._
import com.keepit.common.db.{ Id, State }
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }

@ImplementedBy(classOf[DomainRepoImpl])
trait DomainRepo extends Repo[Domain] {
  def get(domain: String, excludeState: Option[State[Domain]] = Some(DomainStates.INACTIVE))(implicit session: RSession): Option[Domain]
  def getNormalized(hostname: NormalizedHostname, excludeState: Option[State[Domain]] = Some(DomainStates.INACTIVE))(implicit session: RSession): Option[Domain]
  def getByIds(orgIds: Set[Id[Domain]])(implicit session: RSession): Map[Id[Domain], Domain]
  def getAllByName(domains: Seq[String], excludeState: Option[State[Domain]] = Some(DomainStates.INACTIVE))(implicit session: RSession): Seq[Domain]
  def getOverrides(excludeState: Option[State[Domain]] = Some(DomainStates.INACTIVE))(implicit session: RSession): Seq[Domain]
  def updateAutoSensitivity(domainIds: Seq[Id[Domain]], value: Option[Boolean])(implicit session: RWSession): Int
  def internAllByNames(domainNames: Set[String])(implicit session: RWSession): Map[String, Domain]
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

  def get(domain: String, excludeState: Option[State[Domain]] = Some(DomainStates.INACTIVE))(implicit session: RSession): Option[Domain] = {
    val normalizedHostname = NormalizedHostname.fromHostname(domain)
    domainCache.getOrElseOpt(DomainKey(normalizedHostname)) {
      (for (d <- rows if d.hostname === normalizedHostname && d.state =!= excludeState.orNull) yield d).firstOption
    }
  }

  def getNormalized(hostname: NormalizedHostname, excludeState: Option[State[Domain]] = Some(DomainStates.INACTIVE))(implicit session: RSession): Option[Domain] = {
    domainCache.getOrElseOpt(DomainKey(hostname)) {
      (for (d <- rows if d.hostname === hostname && d.state =!= excludeState.orNull) yield d).firstOption
    }
  }

  def getByIds(domainIds: Set[Id[Domain]])(implicit session: RSession): Map[Id[Domain], Domain] = {
    val q = { for { row <- rows if row.id.inSet(domainIds) && row.state === DomainStates.ACTIVE } yield row }
    q.list.map { domain => domain.id.get -> domain }.toMap
  }

  def getAllByName(domains: Seq[String], excludeState: Option[State[Domain]] = Some(DomainStates.INACTIVE))(implicit session: RSession): Seq[Domain] = {
    val normalizedHostnames = domains.map(NormalizedHostname.apply)
    (for (d <- rows if d.hostname.inSet(normalizedHostnames) && d.state =!= excludeState.orNull) yield d).list
  }

  def getOverrides(excludeState: Option[State[Domain]] = Some(DomainStates.INACTIVE))(implicit session: RSession): Seq[Domain] =
    (for (d <- rows if d.state =!= excludeState.orNull && d.manualSensitive.isDefined) yield d).list

  def updateAutoSensitivity(domainIds: Seq[Id[Domain]], value: Option[Boolean])(implicit session: RWSession): Int = {
    val count = (for (d <- rows if d.id.inSet(domainIds)) yield (d.autoSensitive, d.updatedAt)).update(value -> clock.now())
    domainIds foreach { id => invalidateCache(get(id)) }
    count
  }

  def internAllByNames(domainNames: Set[String])(implicit session: RWSession): Map[String, Domain] = {
    val existingDomains = getAllByName(domainNames.toSeq, None).toSet

    val normalizedHostnames = domainNames.map(NormalizedHostname.apply)
    val existingHostnames = existingDomains.map(_.hostname)

    val toBeInserted = normalizedHostnames -- existingHostnames

    val existingDomainByName = existingDomains.map { domain =>
      domain.state match {
        case DomainStates.INACTIVE => domain.hostname.value -> save(Domain(hostname = domain.hostname).copy(id = domain.id))
        case DomainStates.ACTIVE => domain.hostname.value -> domain
      }
    }.toMap

    val newDomainByName = toBeInserted.map { hostname =>
      hostname.value -> save(Domain(hostname = hostname))
    }.toMap

    existingDomainByName ++ newDomainByName
  }
}
