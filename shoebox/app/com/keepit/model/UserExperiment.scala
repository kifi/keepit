package com.keepit.model

import com.google.inject.{Inject, ImplementedBy, Singleton}
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.time._
import java.sql.Connection
import org.joda.time.DateTime
import ru.circumflex.orm._

case class UserExperiment (
  id: Option[Id[UserExperiment]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  userId: Id[User],
  experimentType: State[ExperimentType],
  state: State[UserExperiment] = UserExperimentStates.ACTIVE
) extends Model[UserExperiment] {
  def withId(id: Id[UserExperiment]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def save(implicit conn: Connection): UserExperiment = {
    val entity = UserExperimentEntity(this.copy(updatedAt = currentDateTime))
    assert(1 == entity.save())
    entity.view
  }
}

sealed case class ExperimentType(val value: String)

object ExperimentTypes {
  val ADMIN = State[ExperimentType]("admin")
  val FAKE = State[ExperimentType]("fake")
  val BLOCK = State[ExperimentType]("block")

  def apply(str: String): State[ExperimentType] = str.toLowerCase.trim match {
    case ADMIN.value => ADMIN
    case BLOCK.value => BLOCK
    case FAKE.value => FAKE
  }
}

object UserExperimentStates {
  val ACTIVE = State[UserExperiment]("active")
  val INACTIVE = State[UserExperiment]("inactive")
}

@ImplementedBy(classOf[UserExperimentRepoImpl])
trait UserExperimentRepo extends Repo[UserExperiment] {
  def getByUser(userId: Id[User])(implicit session: RSession): Seq[UserExperiment]
  def getExperiment(userId: Id[User], experiment: State[ExperimentType])(implicit session: RSession): Option[UserExperiment]
  def getByType(experiment: State[ExperimentType])(implicit session: RSession): Seq[UserExperiment]
}

@Singleton
class UserExperimentRepoImpl @Inject() (val db: DataBaseComponent) extends DbRepo[UserExperiment] with UserExperimentRepo {
  import FortyTwoTypeMappers._
  import org.scalaquery.ql._
  import org.scalaquery.ql.ColumnOps._
  import org.scalaquery.ql.basic.BasicProfile
  import org.scalaquery.ql.extended.ExtendedTable
  import db.Driver.Implicit._
  import DBSession._

  override lazy val table = new RepoTable[UserExperiment](db, "follow") {
    def userId = column[Id[User]]("user_id", O.NotNull)
    def experimentType = column[State[ExperimentType]]("experiment_type", O.NotNull)
    def state = column[State[UserExperiment]]("state", O.NotNull)
    def * = id.? ~ createdAt ~ updatedAt ~ userId ~ experimentType ~ state <> (UserExperiment, UserExperiment.unapply _)
  }

  def getByUser(userId: Id[User])(implicit session: RSession): Seq[UserExperiment] =
    (for(f <- table if f.userId === userId && f.state === UserExperimentStates.ACTIVE) yield f).list

  def getExperiment(userId: Id[User], experiment: State[ExperimentType])(implicit session: RSession): Option[UserExperiment] = {
    val q = for {
      f <- table if f.userId === userId && f.experimentType === experiment && f.state === UserExperimentStates.ACTIVE
    } yield f
    q.firstOption
  }

  def getByType(experiment: State[ExperimentType])(implicit session: RSession): Seq[UserExperiment] = {
    val q = for {
      f <- table if f.experimentType === experiment && f.state === UserExperimentStates.ACTIVE
    } yield f
    q.list
  }

}

object UserExperimentCxRepo {

  def get(id: Id[UserExperiment])(implicit conn: Connection): UserExperiment = UserExperimentEntity.get(id).get.view

  def getByUser(userId: Id[User])(implicit conn: Connection): Seq[UserExperiment] =
    (UserExperimentEntity AS "e").map { e => SELECT (e.*) FROM e WHERE ((e.userId EQ userId) AND (e.state EQ UserExperimentStates.ACTIVE))}.list.map(_.view)

  def getExperiment(userId: Id[User], experiment: State[ExperimentType])(implicit conn: Connection): Option[UserExperiment] =
    (UserExperimentEntity AS "e").map { e => SELECT (e.*) FROM e WHERE ((e.userId EQ userId) AND
        (e.state EQ UserExperimentStates.ACTIVE) AND
        (e.experimentType EQ experiment))}.unique.map(_.view)

  def getByType(experimentType: State[ExperimentType])(implicit conn: Connection): Seq[UserExperiment] =
    (UserExperimentEntity AS "e").map { e => SELECT (e.*) FROM e WHERE ((e.experimentType EQ experimentType) AND (e.state EQ UserExperimentStates.ACTIVE))}.list.map(_.view)
}

private[model] class UserExperimentEntity extends Entity[UserExperiment, UserExperimentEntity] {
  val createdAt = "created_at".JODA_TIMESTAMP.NOT_NULL(currentDateTime)
  val updatedAt = "updated_at".JODA_TIMESTAMP.NOT_NULL(currentDateTime)
  val experimentType = "experiment_Type".STATE[ExperimentType].NOT_NULL
  val userId = "user_id".ID[User]
  val state = "state".STATE.NOT_NULL(UserExperimentStates.ACTIVE)

  def relation = UserExperimentEntity

  def view = UserExperiment(
    id = id.value,
    createdAt = createdAt(),
    updatedAt = updatedAt(),
    userId = userId(),
    experimentType = experimentType(),
    state = state()
  )
}

private[model] object UserExperimentEntity extends UserExperimentEntity with EntityTable[UserExperiment, UserExperimentEntity] {
  override def relationName = "user_experiment"

  def apply(view: UserExperiment): UserExperimentEntity = {
    val entity = new UserExperimentEntity
    entity.id.set(view.id)
    entity.createdAt := view.createdAt
    entity.updatedAt := view.updatedAt
    entity.userId := view.userId
    entity.experimentType := view.experimentType
    entity.state := view.state
    entity
  }
}
