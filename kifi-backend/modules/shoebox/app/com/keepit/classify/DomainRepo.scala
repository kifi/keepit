package com.keepit.classify

import com.google.inject.{ImplementedBy, Inject, Singleton}
import com.keepit.common.db.slick.{Repo, DbRepo, DataBaseComponent}
import com.keepit.common.time.Clock
import com.keepit.common.db.{Id, State}
import com.keepit.common.db.slick.DBSession.RSession

@ImplementedBy(classOf[DomainRepoImpl])
trait DomainRepo extends Repo[Domain] {
  def get(domain: String, excludeState: Option[State[Domain]] = Some(DomainStates.INACTIVE))
      (implicit session: RSession): Option[Domain]
  def getAllByName(domains: Seq[String], excludeState: Option[State[Domain]] = Some(DomainStates.INACTIVE))
      (implicit session: RSession): Seq[Domain]
  def getAll(domains: Seq[Id[Domain]], excludeState: Option[State[Domain]] = Some(DomainStates.INACTIVE))
      (implicit session: RSession): Seq[Domain]
  def getOverrides(excludeState: Option[State[Domain]] = Some(DomainStates.INACTIVE))
      (implicit session: RSession): Seq[Domain]
  def updateAutoSensitivity(domainIds: Seq[Id[Domain]], value: Option[Boolean])(implicit session: RSession): Int
}

@Singleton
class DomainRepoImpl @Inject()(val db: DataBaseComponent, val clock: Clock) extends DbRepo[Domain] with DomainRepo {

  import db.Driver.Implicit._

  override val table = new RepoTable[Domain](db, "domain") {
    def autoSensitive = column[Option[Boolean]]("auto_sensitive", O.Nullable)
    def manualSensitive = column[Option[Boolean]]("manual_sensitive", O.Nullable)
    def hostname = column[String]("hostname", O.NotNull)
    def * = id.? ~ hostname ~ autoSensitive ~ manualSensitive ~ state ~ createdAt ~ updatedAt <>
      (Domain.apply _, Domain.unapply _)
  }

  def get(domain: String, excludeState: Option[State[Domain]] = Some(DomainStates.INACTIVE))
      (implicit session: RSession): Option[Domain] =
    (for (d <- table if d.hostname === domain && d.state =!= excludeState.orNull) yield d).firstOption

  def getAll(domains: Seq[Id[Domain]], excludeState: Option[State[Domain]] = Some(DomainStates.INACTIVE))
      (implicit session: RSession): Seq[Domain] =
    (for (d <- table if d.id.inSet(domains) && d.state =!= excludeState.orNull) yield d).list

  def getAllByName(domains: Seq[String], excludeState: Option[State[Domain]] = Some(DomainStates.INACTIVE))
      (implicit session: RSession): Seq[Domain] =
    (for (d <- table if d.hostname.inSet(domains) && d.state =!= excludeState.orNull) yield d).list

  def getOverrides(excludeState: Option[State[Domain]] = Some(DomainStates.INACTIVE))
      (implicit session: RSession): Seq[Domain] =
    (for (d <- table if d.state =!= excludeState.orNull && d.manualSensitive.isNotNull) yield d).list

  def updateAutoSensitivity(domainIds: Seq[Id[Domain]], value: Option[Boolean])(implicit session: RSession): Int =
    (for (d <- table if d.id.inSet(domainIds)) yield d.autoSensitive ~ d.updatedAt).update(value -> clock.now())
}
