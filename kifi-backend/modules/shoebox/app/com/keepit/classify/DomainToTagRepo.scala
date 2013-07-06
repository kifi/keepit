package com.keepit.classify

import com.google.inject.{ImplementedBy, Inject, Singleton}
import com.keepit.common.db.slick._
import com.keepit.common.time.Clock
import com.keepit.common.db.{State, Id}
import com.keepit.common.db.slick.DBSession.{RWSession, RSession}
import org.joda.time.DateTime
import com.keepit.classify.DomainTag
import com.keepit.classify.DomainToTag
import scala.Some

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

  override val table = new RepoTable[DomainToTag](db, "domain_to_tag") {
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
