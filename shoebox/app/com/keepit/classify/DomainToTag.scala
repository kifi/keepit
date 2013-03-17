package com.keepit.classify

import org.joda.time.DateTime

import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.db.slick.DBSession.{RWSession, RSession}
import com.keepit.common.db.slick._
import com.keepit.common.db.{Id, Model, State, States}
import com.keepit.common.time._


case class DomainToTag(
  id: Option[Id[DomainToTag]] = None,
  domainId: Id[Domain],
  tagId: Id[DomainTag],
  state: State[DomainToTag] = DomainToTagStates.ACTIVE,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime
) extends Model[DomainToTag] {
  def withId(id: Id[DomainToTag]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withState(state: State[DomainToTag]) = this.copy(state = state)
}

@ImplementedBy(classOf[DomainToTagRepoImpl])
trait DomainToTagRepo extends Repo[DomainToTag] {
  def getByTag(tagId: Id[DomainTag], excludeState: Option[State[DomainToTag]] = Some(DomainToTagStates.INACTIVE))
      (implicit session: RSession): Seq[DomainToTag]
  def getByDomain(domainId: Id[Domain], excludeState: Option[State[DomainToTag]] = Some(DomainToTagStates.INACTIVE))
      (implicit session: RSession): Seq[DomainToTag]
  def insertAll(domainToToTags: Seq[DomainToTag])(implicit session: RWSession): Option[Int]
  def getDomainsChangedSince(dateTime: DateTime)(implicit session: RSession): Set[Id[Domain]]
  def setState(domainToTagIds: Seq[Id[DomainToTag]], state: State[DomainToTag])(implicit session: RWSession): Int
}

@Singleton
class DomainToTagRepoImpl @Inject()(val db: DataBaseComponent, val clock: Clock)
  extends DbRepo[DomainToTag] with DomainToTagRepo {

  import DBSession._
  import FortyTwoTypeMappers._
  import db.Driver.Implicit._

  override lazy val table = new RepoTable[DomainToTag](db, "domain_to_tag") {
    def domainId = column[Id[Domain]]("domain_id", O.NotNull)
    def tagId = column[Id[DomainTag]]("tag_id", O.NotNull)
    def * = id.? ~ domainId ~ tagId ~ state ~ createdAt ~ updatedAt <> (DomainToTag, DomainToTag.unapply _)
  }

  def getByDomain(domainId: Id[Domain], excludeState: Option[State[DomainToTag]] = Some(DomainToTagStates.INACTIVE))
      (implicit session: RSession): Seq[DomainToTag] =
    (for (t <- table if t.domainId === domainId && t.state =!= excludeState.getOrElse(null)) yield t).list

  def getByTag(tagId: Id[DomainTag], excludeState: Option[State[DomainToTag]] = Some(DomainToTagStates.INACTIVE))
      (implicit session: RSession): Seq[DomainToTag] =
    (for (t <- table if t.tagId === tagId && t.state =!= excludeState.getOrElse(null)) yield t).list

  def insertAll(domainToTags: Seq[DomainToTag])(implicit session: RWSession): Option[Int] =
    table.insertAll(domainToTags: _*)

  def setState(domainToTagIds: Seq[Id[DomainToTag]], state: State[DomainToTag])
      (implicit session: RWSession): Int =
    (for (t <- table if t.id inSet domainToTagIds) yield t.state).update(state)

  def getDomainsChangedSince(dateTime: DateTime)(implicit session: RSession): Set[Id[Domain]] =
    (for (t <- table if t.updatedAt >= dateTime) yield t.domainId).elements.toSet
}

object DomainToTagStates extends States[DomainToTag]
