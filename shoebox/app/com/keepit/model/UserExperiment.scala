package com.keepit.model

import com.google.inject.{Inject, ImplementedBy, Singleton}
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.time._
import java.sql.Connection
import org.joda.time.DateTime

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

object UserExperimentStates extends States[UserExperiment]

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

  override lazy val table = new RepoTable[UserExperiment](db, "user_experiment") {
    def userId = column[Id[User]]("user_id", O.NotNull)
    def experimentType = column[State[ExperimentType]]("experiment_type", O.NotNull)
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
