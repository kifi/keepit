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
  import FortyTwoTypeMappers._
  import db.Driver.Implicit._

  override val table = new RepoTable[TopicName](db, tableName){
    def topicName = column[String]("topic_name", O.NotNull)
    def * = id.? ~ createdAt ~ updatedAt ~ topicName <> (TopicName.apply _, TopicName.unapply _)
  }

  def getAllNames()(implicit session: RSession): Seq[String] = {
    (for(r <- table) yield r.topicName).list
  }

  def getName(id: Id[TopicName])(implicit session: RSession): Option[String] = {
    (for(r <- table if r.id === id) yield r.topicName).firstOption
  }
  def updateName(id: Id[TopicName], name: String)(implicit session: RWSession): Option[TopicName] = {
    (for(r <- table if r.id === id) yield r).firstOption match {
      case Some(topic) => Some(super.save(topic.copy(topicName = name)))
      case None => None
    }
  }
  def deleteAll()(implicit session: RWSession): Int = {
    (for(r <- table) yield r).delete
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
