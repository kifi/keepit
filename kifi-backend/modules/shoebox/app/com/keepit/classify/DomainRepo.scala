package com.keepit.classify

import com.google.inject.{ImplementedBy, Inject, Singleton}
import com.keepit.common.db.slick.{Repo, DbRepo, DataBaseComponent}
import com.keepit.common.time._
import com.keepit.common.db.{Id, State}
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.model.Normalization
import com.keepit.common.db.slick.FortyTwoTypeMappers
import com.keepit.common.db.slick.DBSession

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
  def getByPrefix(prefix: String, excludeState: Option[State[Domain]] = Some(DomainStates.INACTIVE))(implicit session: RSession): Seq[Domain]
  def normSchemePage(page: Int, pageSize: Int)(implicit session: RSession): (Seq[Domain], Int)
}

@Singleton
class DomainRepoImpl @Inject()(val db: DataBaseComponent, val clock: Clock) extends DbRepo[Domain] with DomainRepo {
  import DBSession._
  import db.Driver.Implicit._
  import FortyTwoTypeMappers._
  import scala.slick.lifted.Query


  override val table = new RepoTable[Domain](db, "domain") {
    def autoSensitive = column[Option[Boolean]]("auto_sensitive", O.Nullable)
    def manualSensitive = column[Option[Boolean]]("manual_sensitive", O.Nullable)
    def hostname = column[String]("hostname", O.NotNull)
    def normalizationScheme = column[Normalization]("normalization_scheme", O.Nullable)
    def * = id.? ~ hostname ~ normalizationScheme.? ~ autoSensitive ~ manualSensitive ~ state ~ createdAt ~ updatedAt <> (Domain.apply _, Domain.unapply _)
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
    
  def getByPrefix(prefix: String, excludeState: Option[State[Domain]] = Some(DomainStates.INACTIVE))(implicit session: RSession): Seq[Domain] = {
    (for (d <- table if d.hostname.startsWith(prefix) && d.state =!= excludeState.orNull) yield d).sortBy(_.hostname).list
  }
  
  def normSchemePage(page: Int, pageSize: Int)(implicit session: RSession): (Seq[Domain], Int) = {
    val ds = (for (d <- table if d.state =!= DomainStates.INACTIVE && d.normalizationScheme.isNotNull) yield d).sortBy(_.hostname).list
    (ds.drop(page*pageSize).take(pageSize), ds.size)
  }
  
}
