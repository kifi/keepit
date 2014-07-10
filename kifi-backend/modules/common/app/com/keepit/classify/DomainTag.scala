package com.keepit.classify

import org.joda.time.DateTime
import com.keepit.common.db.{ State, States, ModelWithState, Id }
import com.keepit.common.time._

case class DomainTag(
    id: Option[Id[DomainTag]] = None,
    name: DomainTagName,
    sensitive: Option[Boolean] = None,
    state: State[DomainTag] = DomainTagStates.ACTIVE,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime) extends ModelWithState[DomainTag] {
  def withId(id: Id[DomainTag]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withSensitive(sensitive: Option[Boolean]) = this.copy(sensitive = sensitive)
  def withState(state: State[DomainTag]) = this.copy(state = state)
}

class DomainTagName private (val name: String) extends AnyVal

object DomainTagName {
  def apply(name: String): DomainTagName = new DomainTagName(name.toLowerCase.trim)
  def unapply(dtn: DomainTagName): Option[String] = Some(dtn.name)

  private val blacklist = Seq(
    "dns error site can't be resolved",
    "empty site",
    "error site can't be accessed",
    "local ip",
    "site under construction or not available"
  ).map(DomainTagName(_))
  def isBlacklisted(dt: DomainTagName): Boolean = blacklist contains dt
}

object DomainTagStates extends States[DomainTag]
