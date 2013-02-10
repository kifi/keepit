package com.keepit.classify

import org.joda.time.DateTime

import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick._
import com.keepit.common.db.{State, States, Model, Id}
import com.keepit.common.time._


case class DomainTag(
  id: Option[Id[DomainTag]] = None,
  name: DomainTagName,
  sensitive: Option[Boolean] = None,
  state: State[DomainTag] = DomainTagStates.ACTIVE,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime
) extends Model[DomainTag] {
  def withId(id: Id[DomainTag]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withSensitive(sensitive: Option[Boolean]) = this.copy(sensitive = sensitive)
  def withState(state: State[DomainTag]) = this.copy(state = state)
}

class DomainTagName private (val name: String) {
  override def equals(o: Any): Boolean = o match {
    case DomainTagName(name) => name == this.name
    case _ => false
  }
  override def hashCode = name.hashCode
  override def toString = "DomainTagName(" + name + ")"
}

object DomainTagName {
  def apply(name: String): DomainTagName = new DomainTagName(name.toLowerCase.trim)
  def unapply(dtn: DomainTagName): Option[String] = Some(dtn.name)
}

@ImplementedBy(classOf[DomainTagRepoImpl])
trait DomainTagRepo extends Repo[DomainTag] {
  def getTags(tagIds: Seq[Id[DomainTag]], excludeState: Option[State[DomainTag]] = Some(DomainTagStates.INACTIVE))
      (implicit session: RSession): Seq[DomainTag]
  def get(tag: DomainTagName, excludeState: Option[State[DomainTag]] = Some(DomainTagStates.INACTIVE))
      (implicit session: RSession): Option[DomainTag]
}

@Singleton
class DomainTagRepoImpl @Inject()(val db: DataBaseComponent) extends DbRepo[DomainTag] with DomainTagRepo {

  import DBSession._
  import FortyTwoTypeMappers._
  import db.Driver.Implicit._

  override lazy val table = new RepoTable[DomainTag](db, "domain_tag") {
    def name = column[DomainTagName]("name", O.NotNull)
    def sensitive = column[Option[Boolean]]("sensitive", O.Nullable)
    def * = id.? ~ name ~ sensitive ~ state ~ createdAt ~ updatedAt <> (DomainTag, DomainTag.unapply _)
  }

  def getTags(tagIds: Seq[Id[DomainTag]], excludeState: Option[State[DomainTag]] = Some(DomainTagStates.INACTIVE))
      (implicit session: RSession): Seq[DomainTag] =
    (for (t <- table if t.state =!= excludeState.getOrElse(null) && t.id.inSet(tagIds)) yield t).list

  def get(tag: DomainTagName, excludeState: Option[State[DomainTag]] = Some(DomainTagStates.INACTIVE))
      (implicit session: RSession): Option[DomainTag] =
    (for (t <- table if t.state =!= excludeState.getOrElse(null) && t.name === tag) yield t).firstOption
}

object DomainTagStates extends States[DomainTag]
