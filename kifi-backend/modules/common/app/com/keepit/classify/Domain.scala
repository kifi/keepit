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
  def isActive: Boolean = state == DomainStates.ACTIVE
}

object Domain {
  private val DomainRegex = """^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z0-9]+$""".r
  private val MaxLength = 128

  def isValid(s: String): Boolean = DomainRegex.findFirstIn(s).isDefined && s.length <= MaxLength
}

object DomainStates extends States[Domain]
