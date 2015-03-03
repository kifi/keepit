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
  def get(userId: Id[User], domainId: Id[Domain], kind: UserToDomainKind)(implicit session: RSession): Option[UserToDomain]
}

@Singleton
class UserToDomainRepoImpl @Inject() (
    val db: DataBaseComponent,
    userToDomainCache: UserToDomainCache,
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

  override def deleteCache(model: UserToDomain)(implicit session: RSession): Unit = {
    userToDomainCache.remove(UserToDomainKey(model.userId, model.domainId, model.kind))
  }

  override def invalidateCache(model: UserToDomain)(implicit session: RSession): Unit = {
    userToDomainCache.set(UserToDomainKey(model.userId, model.domainId, model.kind), Some(model))
  }

  def get(userId: Id[User], domainId: Id[Domain], kind: UserToDomainKind)(implicit session: RSession): Option[UserToDomain] = {
    userToDomainCache.getOrElse(UserToDomainKey(userId, domainId, kind)) {
      (for (t <- rows if t.userId === userId && t.domainId === domainId && t.kind === kind) yield t).firstOption
    }
  }

}
