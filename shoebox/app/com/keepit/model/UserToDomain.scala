package com.keepit.model

import org.joda.time.DateTime

import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.db.slick.DBSession.{RWSession, RSession}
import com.keepit.common.db.slick._
import com.keepit.common.db.{Id, Model, State, States}
import com.keepit.common.time._
import com.keepit.classify.Domain

case class UserToDomain(
  id: Option[Id[UserToDomain]] = None,
  userId: Id[User],
  domainId: Id[Domain],
  kind: State[UserToDomainKind],
  state: State[UserToDomain] = UserToDomainStates.ACTIVE,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime
) extends Model[UserToDomain] {
  def withId(id: Id[UserToDomain]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withState(state: State[UserToDomain]) = this.copy(state = state)
  def isActive = state == UserToDomainStates.ACTIVE
}

sealed case class UserToDomainKind(val value: String)

object UserToDomainKinds {
  val NEVER_SHOW = State[UserToDomainKind]("never_show")

  def apply(str: String): State[UserToDomainKind] = str.toLowerCase.trim match {
    case NEVER_SHOW.value => NEVER_SHOW
  }
}

@ImplementedBy(classOf[UserToDomainRepoImpl])
trait UserToDomainRepo extends Repo[UserToDomain] {
  def get(userId: Id[User], domainId: Id[Domain], kind: State[UserToDomainKind],
          excludeState: Option[State[UserToDomain]] = Some(UserToDomainStates.INACTIVE))
      (implicit session: RSession): Option[UserToDomain]
}

@Singleton
class UserToDomainRepoImpl @Inject()(
  val db: DataBaseComponent,
  val clock: Clock)
    extends DbRepo[UserToDomain] with UserToDomainRepo {

  import DBSession._
  import FortyTwoTypeMappers._
  import db.Driver.Implicit._

  override lazy val table = new RepoTable[UserToDomain](db, "user_to_domain") {
    def userId = column[Id[User]]("user_id", O.NotNull)
    def domainId = column[Id[Domain]]("domain_id", O.NotNull)
    def kind = column[State[UserToDomainKind]]("kind", O.NotNull)
    def * = id.? ~ userId ~ domainId ~ kind ~ state ~ createdAt ~ updatedAt <> (UserToDomain, UserToDomain.unapply _)
  }

  def get(userId: Id[User], domainId: Id[Domain], kind: State[UserToDomainKind],
          excludeState: Option[State[UserToDomain]] = Some(UserToDomainStates.INACTIVE))
      (implicit session: RSession): Option[UserToDomain] =
    (for (t <- table if t.userId === userId && t.domainId === domainId && t.kind === kind && t.state =!= excludeState.orNull) yield t).firstOption
}

object UserToDomainStates extends States[UserToDomain]
