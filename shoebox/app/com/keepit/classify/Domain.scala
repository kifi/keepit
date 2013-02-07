package com.keepit.classify

import org.joda.time.DateTime

import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick._
import com.keepit.common.db.{State, States, Model, Id}
import com.keepit.common.time._


case class Domain(
  id: Option[Id[Domain]] = None,
  hostname: String,
  autoSensitive: Option[Boolean] = None,
  manualSensitive: Option[Boolean] = None,
  state: State[Domain] = DomainStates.ACTIVE,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime
) extends Model[Domain] {
  def withId(id: Id[Domain]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withAutoSensitive(sensitive: Option[Boolean]) = this.copy(autoSensitive = sensitive)
  def withManualSensitive(sensitive: Option[Boolean]) = this.copy(manualSensitive = sensitive)
  def withState(state: State[Domain]) = this.copy(state = state)
  val sensitive: Option[Boolean] = manualSensitive orElse autoSensitive
}

@ImplementedBy(classOf[DomainRepoImpl])
trait DomainRepo extends Repo[Domain] {
  def get(domain: String, excludeState: Option[State[Domain]] = Some(DomainStates.INACTIVE))
      (implicit session: RSession): Option[Domain]
}

@Singleton
class DomainRepoImpl @Inject()(val db: DataBaseComponent) extends DbRepo[Domain] with DomainRepo {

  import db.Driver.Implicit._

  override lazy val table = new RepoTable[Domain](db, "domain") {
    def autoSensitive = column[Option[Boolean]]("auto_sensitive", O.Nullable)
    def manualSensitive = column[Option[Boolean]]("manual_sensitive", O.Nullable)
    def hostname = column[String]("hostname", O.NotNull)
    def * = id.? ~ hostname ~ autoSensitive ~ manualSensitive ~ state ~ createdAt ~ updatedAt <>
      (Domain, Domain.unapply _)
  }

  def get(domain: String, excludeState: Option[State[Domain]] = Some(DomainStates.INACTIVE))
      (implicit session: RSession): Option[Domain] =
    (for (d <- table if d.hostname === domain && d.state =!= excludeState.getOrElse(null)) yield d).firstOption
}

object DomainStates extends States[Domain]
