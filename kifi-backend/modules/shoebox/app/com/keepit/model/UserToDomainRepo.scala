package com.keepit.model

import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.db.slick._
import com.keepit.common.db.{State, Id}
import com.keepit.classify.Domain
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.time.Clock
import scala.Some
import play.api.libs.json.JsValue

@ImplementedBy(classOf[UserToDomainRepoImpl])
trait UserToDomainRepo extends Repo[UserToDomain] {
  def get(userId: Id[User], domainId: Id[Domain], kind: State[UserToDomainKind],
          excludeState: Option[State[UserToDomain]] = Some(UserToDomainStates.INACTIVE))
         (implicit session: RSession): Option[UserToDomain]

  def exists(userId: Id[User], domainId: Id[Domain], kind: State[UserToDomainKind],
             excludeState: Option[State[UserToDomain]] = Some(UserToDomainStates.INACTIVE))
            (implicit session: RSession): Boolean
}

@Singleton
class UserToDomainRepoImpl @Inject()(
                                      val db: DataBaseComponent,
                                      val clock: Clock)
  extends DbRepo[UserToDomain] with UserToDomainRepo {

  import DBSession._
  import FortyTwoTypeMappers._
  import db.Driver.Implicit._

  override val table = new RepoTable[UserToDomain](db, "user_to_domain") {
    def userId = column[Id[User]]("user_id", O.NotNull)
    def domainId = column[Id[Domain]]("domain_id", O.NotNull)
    def kind = column[State[UserToDomainKind]]("kind", O.NotNull)
    def value = column[Option[JsValue]]("value", O.Nullable)
    def * = id.? ~ userId ~ domainId ~ kind ~ value ~ state ~ createdAt ~ updatedAt <> (UserToDomain, UserToDomain.unapply _)
  }

  def get(userId: Id[User], domainId: Id[Domain], kind: State[UserToDomainKind],
          excludeState: Option[State[UserToDomain]] = Some(UserToDomainStates.INACTIVE))
         (implicit session: RSession): Option[UserToDomain] =
    (for (t <- table if t.userId === userId && t.domainId === domainId && t.kind === kind && t.state =!= excludeState.orNull) yield t).firstOption

  def exists(userId: Id[User], domainId: Id[Domain], kind: State[UserToDomainKind],
             excludeState: Option[State[UserToDomain]] = Some(UserToDomainStates.INACTIVE))
            (implicit session: RSession): Boolean =
    (for (t <- table if t.userId === userId && t.domainId === domainId && t.kind === kind && t.state =!= excludeState.orNull) yield t.id).firstOption.isDefined
}
