package com.keepit.model

import com.keepit.common.db.{CX, Entity, EntityTable, ExternalId, Id, State}
import com.keepit.common.time._
import java.sql.Connection
import org.joda.time.DateTime
import ru.circumflex.orm._

case class UserExperiment (
  id: Option[Id[UserExperiment]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  userId: Id[User],
  experimentType: State[UserExperiment.ExperimentType],
  state: State[UserExperiment] = UserExperiment.States.ACTIVE
) {
  def save(implicit conn: Connection): UserExperiment = {
    val entity = UserExperimentEntity(this.copy(updatedAt = currentDateTime))
    assert(1 == entity.save())
    entity.view
  }
}

object UserExperiment {

  sealed case class ExperimentType(val value: String)

  object ExperimentTypes {
    val ADMIN = State[ExperimentType]("admin")
    val FAKE = State[ExperimentType]("fake")
    val BLOCK = State[ExperimentType]("block")

    def apply(str: String) = str.toLowerCase.trim match {
      case ADMIN.value => ADMIN
      case FAKE.value => FAKE
    }
  }

  object States {
    val ACTIVE = State[UserExperiment]("active")
    val INACTIVE = State[UserExperiment]("inactive")
  }

  def apply(experiment: State[ExperimentType], userId: Id[User]): UserExperiment = UserExperiment(experimentType = experiment, userId = userId)

  def get(id: Id[UserExperiment])(implicit conn: Connection): UserExperiment = UserExperimentEntity.get(id).get.view

  def getByUser(userId: Id[User])(implicit conn: Connection): Seq[UserExperiment] =
    (UserExperimentEntity AS "e").map { e => SELECT (e.*) FROM e WHERE ((e.userId EQ userId) AND (e.state EQ UserExperiment.States.ACTIVE))}.list.map(_.view)

  def getExperiment(userId: Id[User], experiment: State[ExperimentType])(implicit conn: Connection): Option[UserExperiment] =
    (UserExperimentEntity AS "e").map { e => SELECT (e.*) FROM e WHERE ((e.userId EQ userId) AND
        (e.state EQ UserExperiment.States.ACTIVE) AND
        (e.experimentType EQ experiment))}.unique.map(_.view)

  def getByType(experimentType: State[UserExperiment.ExperimentType])(implicit conn: Connection): Seq[UserExperiment] =
    (UserExperimentEntity AS "e").map { e => SELECT (e.*) FROM e WHERE ((e.experimentType EQ experimentType) AND (e.state EQ UserExperiment.States.ACTIVE))}.list.map(_.view)
}

private[model] class UserExperimentEntity extends Entity[UserExperiment, UserExperimentEntity] {
  val createdAt = "created_at".JODA_TIMESTAMP.NOT_NULL(currentDateTime)
  val updatedAt = "updated_at".JODA_TIMESTAMP.NOT_NULL(currentDateTime)
  val experimentType = "experiment_Type".STATE[UserExperiment.ExperimentType].NOT_NULL
  val userId = "user_id".ID[User]
  val state = "state".STATE.NOT_NULL(UserExperiment.States.ACTIVE)

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
