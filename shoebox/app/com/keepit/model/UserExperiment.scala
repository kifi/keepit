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

  case class ExperimentType(val value: String)
  
  object ExperimentTypes {
    val ADMIN = State[ExperimentType]("admin")
  }
  
  object States {
    val ACTIVE = State[UserExperiment]("active")
    val INACTIVE = State[UserExperiment]("inactive")
  }
  
  def apply(experiment: State[ExperimentType], userId: Id[User]): UserExperiment = UserExperiment(experimentType = experiment, userId = userId)
  
  def get(id: Id[UserExperiment])(implicit conn: Connection): UserExperiment = UserExperimentEntity.get(id).get.view
  
  def getByAddressOpt(address: String)(implicit conn: Connection): Option[UserExperiment] = 
    (UserExperimentEntity AS "e").map { e => SELECT (e.*) FROM e WHERE (e.address EQ address)}.unique.map(_.view)

  def getByUser(userId: Id[User])(implicit conn: Connection): Seq[UserExperiment] = 
    (UserExperimentEntity AS "e").map { e => SELECT (e.*) FROM e WHERE (e.userId EQ userId)}.list.map(_.view)
    
}

private[model] class UserExperimentEntity extends Entity[UserExperiment, UserExperimentEntity] {
  val createdAt = "created_at".JODA_TIMESTAMP.NOT_NULL(currentDateTime)
  val updatedAt = "updated_at".JODA_TIMESTAMP.NOT_NULL(currentDateTime)
  val address = "address".VARCHAR(512).NOT_NULL
  val userId = "user_id".ID[User]
  val state = "state".STATE.NOT_NULL(UserExperiment.States.UNVERIFIED)
  val verifiedAt = "verified_at".JODA_TIMESTAMP
  val lastVerificationSent = "last_verification_sent".JODA_TIMESTAMP
  
  def relation = UserExperimentEntity
  
  def view = UserExperiment(
    id = id.value,
    createdAt = createdAt(),
    updatedAt = updatedAt(),
    address = address(),
    userId = userId(),
    verifiedAt = verifiedAt.value,
    lastVerificationSent = lastVerificationSent.value
  )
}

private[model] object UserExperimentEntity extends UserExperimentEntity with EntityTable[UserExperiment, UserExperimentEntity] {
  override def relationName = "email_address"
  
  def apply(view: UserExperiment): UserExperimentEntity = {
    val entity = new UserExperimentEntity
    entity.id.set(view.id)
    entity.createdAt := view.createdAt
    entity.updatedAt := view.updatedAt
    entity.address := view.address
    entity.userId := view.userId
    entity.verifiedAt.set(view.verifiedAt)
    entity.lastVerificationSent.set(view.lastVerificationSent)
    entity
  }
}
