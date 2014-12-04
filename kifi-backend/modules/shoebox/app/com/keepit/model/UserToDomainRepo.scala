package com.keepit.model

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.db.slick._
import com.keepit.common.db.{ State, Id }
import com.keepit.classify.Domain
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.time.Clock
import scala.Some
import play.api.libs.json.JsValue

@ImplementedBy(classOf[UserToDomainRepoImpl])
trait UserToDomainRepo extends Repo[UserToDomain] {
  def get(userId: Id[User], domainId: Id[Domain], kind: UserToDomainKind,
    excludeState: Option[State[UserToDomain]] = Some(UserToDomainStates.INACTIVE))(implicit session: RSession): Option[UserToDomain]

  def exists(userId: Id[User], domainId: Id[Domain], kind: UserToDomainKind,
    excludeState: Option[State[UserToDomain]] = Some(UserToDomainStates.INACTIVE))(implicit session: RSession): Boolean
}

@Singleton
class UserToDomainRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock) extends DbRepo[UserToDomain] with UserToDomainRepo {

  import db.Driver.simple._

  type RepoImpl = UserToDomainTable
  class UserToDomainTable(tag: Tag) extends RepoTable[UserToDomain](db, tag, "user_to_domain") {
    def userId = column[Id[User]]("user_id", O.NotNull)
    def domainId = column[Id[Domain]]("domain_id", O.NotNull)
    def kind = column[UserToDomainKind]("kind", O.NotNull)
    def value = column[Option[JsValue]]("value", O.Nullable)
    def * = (id.?, userId, domainId, kind, value, state, createdAt, updatedAt) <> ((UserToDomain.apply _).tupled, UserToDomain.unapply _)
  }

  def table(tag: Tag) = new UserToDomainTable(tag)
  initTable()

  override def deleteCache(model: UserToDomain)(implicit session: RSession): Unit = {}
  override def invalidateCache(model: UserToDomain)(implicit session: RSession): Unit = {}

  def get(userId: Id[User], domainId: Id[Domain], kind: UserToDomainKind,
    excludeState: Option[State[UserToDomain]] = Some(UserToDomainStates.INACTIVE))(implicit session: RSession): Option[UserToDomain] =
    (for (t <- rows if t.userId === userId && t.domainId === domainId && t.kind === kind && t.state =!= excludeState.orNull) yield t).firstOption

  def exists(userId: Id[User], domainId: Id[Domain], kind: UserToDomainKind,
    excludeState: Option[State[UserToDomain]] = Some(UserToDomainStates.INACTIVE))(implicit session: RSession): Boolean =
    (for (t <- rows if t.userId === userId && t.domainId === domainId && t.kind === kind && t.state =!= excludeState.orNull) yield t.id).firstOption.isDefined
}
