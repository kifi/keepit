package com.keepit.model

import com.google.inject.{Inject, ImplementedBy, Singleton}
import com.keepit.common.db.Id
import com.keepit.common.db.Model
import com.keepit.common.time._
import org.joda.time.DateTime
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._

case class TopicName (
  id: Option[Id[TopicName]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  topicName: String
) extends Model[TopicName] {
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withId(id: Id[TopicName]) = this.copy(id = Some(id))
}

trait TopicNameRepo extends Repo[TopicName]{
  def getAllNames()(implicit session: RSession): Seq[String]
  def getName(id: Id[TopicName])(implicit session: RSession): Option[String]
  def updateName(id: Id[TopicName], name: String)(implicit session: RWSession): Option[TopicName]
  def deleteAll()(implicit session: RWSession): Int
}

abstract class TopicNameRepoBase(
  val tableName: String,
  val db: DataBaseComponent,
  val clock: Clock
) extends DbRepo[TopicName] with TopicNameRepo {
    import db.Driver.simple._

  type RepoImpl = TopicNameTable
  class TopicNameTable(tag: Tag) extends RepoTable[TopicName](db, tag, "topic_name") {
    def topicName = column[String]("topic_name", O.NotNull)
    def * = (id.?, createdAt, updatedAt, topicName) <> ((TopicName.apply _).tupled, TopicName.unapply _)
  }

  def table(tag: Tag) = new TopicNameTable(tag)
  initTable()

  override def deleteCache(model: TopicName)(implicit session: RSession): Unit = {}
  override def invalidateCache(model: TopicName)(implicit session: RSession): Unit = {}

  def getAllNames()(implicit session: RSession): Seq[String] = {
    (for(r <- rows) yield r.topicName).list
  }

  def getName(id: Id[TopicName])(implicit session: RSession): Option[String] = {
    (for(r <- rows if r.id === id) yield r.topicName).firstOption
  }

  def updateName(id: Id[TopicName], name: String)(implicit session: RWSession): Option[TopicName] = {
    (for(r <- rows if r.id === id) yield r).firstOption match {
      case Some(topic) => Some(super.save(topic.copy(topicName = name)))
      case None => None
    }
  }
  def deleteAll()(implicit session: RWSession): Int = {
    (for(r <- rows) yield r).delete
  }
}

@Singleton
class TopicNameRepoA @Inject()(
  db: DataBaseComponent,
  clock: Clock
) extends TopicNameRepoBase("topic_name_a", db, clock)

@Singleton
class TopicNameRepoB @Inject()(
  db: DataBaseComponent,
  clock: Clock
) extends TopicNameRepoBase("topic_name_b", db, clock)
