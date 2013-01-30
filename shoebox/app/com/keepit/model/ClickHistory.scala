package com.keepit.model

import play.api.Play.current
import com.google.inject.{Inject, ImplementedBy, Singleton}
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.time._
import org.joda.time.DateTime
import play.api._

case class ClickHistory (
                    id: Option[Id[ClickHistory]] = None,
                    createdAt: DateTime = currentDateTime,
                    updatedAt: DateTime = currentDateTime,
                    state: State[ClickHistory] = ClickHistoryStates.ACTIVE,
                    userId: Id[User],
                    tableSize: Int,
                    filter: Array[Byte],
                    numHashFuncs: Int,
                    minHits: Int,
                    updatesCount: Int = 0
                    ) extends Model[ClickHistory] {
  def withFilter(filter: Array[Byte]) = this.copy(filter = filter)
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withId(id: Id[ClickHistory]) = this.copy(id = Some(id))
}

@ImplementedBy(classOf[ClickHistoryRepoImpl])
trait ClickHistoryRepo extends Repo[ClickHistory] {
  def getByUserId(userId: Id[User])(implicit session: RSession): Option[ClickHistory]
}

@Singleton
class ClickHistoryRepoImpl @Inject() (val db: DataBaseComponent) extends DbRepo[ClickHistory] with ClickHistoryRepo {
  import FortyTwoTypeMappers._
  import org.scalaquery.ql._
  import org.scalaquery.ql.ColumnOps._
  import org.scalaquery.ql.basic.BasicProfile
  import org.scalaquery.ql.extended.ExtendedTable
  import db.Driver.Implicit._
  import DBSession._

  override lazy val table = new RepoTable[ClickHistory](db, "click_history") {
    def userId = column[Id[User]]("user_id", O.NotNull)
    def tableSize = column[Int]("table_size", O.NotNull)
    def filter = column[Array[Byte]]("filter", O.NotNull)
    def numHashFuncs = column[Int]("num_hash_funcs", O.NotNull)
    def minHits = column[Int]("min_hits", O.NotNull)
    def updatesCount = column[Int]("updates_count", O.NotNull)

    def * = id.? ~ createdAt ~ updatedAt ~ state ~ userId ~ tableSize ~ filter ~ numHashFuncs ~ minHits ~ updatesCount <> (ClickHistory, ClickHistory.unapply _)
  }

  override def save(model: ClickHistory)(implicit session: RWSession): ClickHistory = {
    super.save(model.copy(updatesCount = model.updatesCount + 1))
  }

  def getByUserId(userId: Id[User])(implicit session: RSession): Option[ClickHistory] =
    (for(b <- table if b.userId === userId && b.state === ClickHistoryStates.ACTIVE) yield b).firstOption

}


object ClickHistoryStates {
  val ACTIVE = State[ClickHistory]("active")
  val INACTIVE = State[ClickHistory]("inactive")
}
