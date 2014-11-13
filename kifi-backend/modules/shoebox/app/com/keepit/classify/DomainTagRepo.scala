package com.keepit.classify

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.slick._
import com.keepit.common.time.Clock
import com.keepit.common.db.{ State, Id }
import com.keepit.common.db.slick.DBSession.RSession
import scala.Some

@ImplementedBy(classOf[DomainTagRepoImpl])
trait DomainTagRepo extends Repo[DomainTag] {
  def getTags(tagIds: Seq[Id[DomainTag]], excludeState: Option[State[DomainTag]] = Some(DomainTagStates.INACTIVE))(implicit session: RSession): Seq[DomainTag]
  def get(tag: DomainTagName, excludeState: Option[State[DomainTag]] = Some(DomainTagStates.INACTIVE))(implicit session: RSession): Option[DomainTag]
}

@Singleton
class DomainTagRepoImpl @Inject() (val db: DataBaseComponent, val clock: Clock) extends DbRepo[DomainTag] with DomainTagRepo {
  import db.Driver.simple._

  type RepoImpl = DomainTagTable

  class DomainTagTable(tag: Tag) extends RepoTable[DomainTag](db, tag, "domain_tag") {
    def name = column[DomainTagName]("name", O.NotNull)
    def sensitive = column[Option[Boolean]]("sensitive", O.Nullable)
    def * = (id.?, name, sensitive, state, createdAt, updatedAt) <> (DomainTag.tupled, DomainTag.unapply _)
  }

  def table(tag: Tag) = new DomainTagTable(tag)
  initTable()

  override def deleteCache(model: DomainTag)(implicit session: RSession): Unit = {}
  override def invalidateCache(model: DomainTag)(implicit session: RSession): Unit = {}

  def getTags(tagIds: Seq[Id[DomainTag]], excludeState: Option[State[DomainTag]] = Some(DomainTagStates.INACTIVE))(implicit session: RSession): Seq[DomainTag] = {
    if (tagIds.isEmpty) {
      Seq.empty
    } else {
      (for (t <- rows if t.state =!= excludeState.getOrElse(null) && t.id.inSet(tagIds)) yield t).list
    }
  }

  def get(tag: DomainTagName, excludeState: Option[State[DomainTag]] = Some(DomainTagStates.INACTIVE))(implicit session: RSession): Option[DomainTag] =
    (for (t <- rows if t.state =!= excludeState.getOrElse(null) && t.name === tag) yield t).firstOption
}
