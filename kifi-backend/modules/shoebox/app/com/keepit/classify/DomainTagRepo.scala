package com.keepit.classify

import com.google.inject.{ImplementedBy, Inject, Singleton}
import com.keepit.common.db.slick._
import com.keepit.common.time.Clock
import com.keepit.common.db.{State, Id}
import com.keepit.common.db.slick.DBSession.RSession
import scala.Some

@ImplementedBy(classOf[DomainTagRepoImpl])
trait DomainTagRepo extends Repo[DomainTag] {
  def getTags(tagIds: Seq[Id[DomainTag]], excludeState: Option[State[DomainTag]] = Some(DomainTagStates.INACTIVE))
      (implicit session: RSession): Seq[DomainTag]
  def get(tag: DomainTagName, excludeState: Option[State[DomainTag]] = Some(DomainTagStates.INACTIVE))
      (implicit session: RSession): Option[DomainTag]
}

@Singleton
class DomainTagRepoImpl @Inject()(val db: DataBaseComponent, val clock: Clock) extends DbRepo[DomainTag] with DomainTagRepo {

  import DBSession._
  import FortyTwoTypeMappers._
  import db.Driver.Implicit._

  override val table = new RepoTable[DomainTag](db, "domain_tag") {
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
