package com.keepit.model

import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.db.slick._
import com.keepit.common.db.{ExternalId, Id, State}
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.time.Clock
import com.keepit.common.logging.Logging

@ImplementedBy(classOf[UserRepoImpl])
trait UserRepo extends Repo[User] with ExternalIdColumnFunction[User] {
  def allExcluding(excludeStates: State[User]*)(implicit session: RSession): Seq[User]
  def getOpt(id: Id[User])(implicit session: RSession): Option[User]
}

@Singleton
class UserRepoImpl @Inject() (
                               val db: DataBaseComponent,
                               val clock: Clock,
                               val externalIdCache: UserExternalIdCache,
                               val idCache: UserIdCache)
  extends DbRepo[User] with UserRepo with ExternalIdColumnDbFunction[User] with Logging {
  import FortyTwoTypeMappers._
  import scala.slick.lifted.Query
  import db.Driver.Implicit._
  import DBSession._

  override val table = new RepoTable[User](db, "user") with ExternalIdColumn[User] {
    def firstName = column[String]("first_name", O.NotNull)
    def lastName = column[String]("last_name", O.NotNull)
    def * = id.? ~ createdAt ~ updatedAt ~ externalId ~ firstName ~ lastName ~ state <> (User, User.unapply _)
  }

  def allExcluding(excludeStates: State[User]*)(implicit session: RSession): Seq[User] =
    (for (u <- table if !(u.state inSet excludeStates)) yield u).list

  override def invalidateCache(user: User)(implicit session: RSession) = {
    user.id map {id => idCache.set(UserIdKey(id), user)}
    externalIdCache.set(UserExternalIdKey(user.externalId), user)
    user
  }

  override def get(id: Id[User])(implicit session: RSession): User = {
    idCache.getOrElse(UserIdKey(id)) {
      (for(f <- table if f.id is id) yield f).first
    }
  }

  def getOpt(id: Id[User])(implicit session: RSession): Option[User] =
    idCache.getOrElseOpt(UserIdKey(id)) { (for(f <- table if f.id is id) yield f).firstOption }

  override def getOpt(id: ExternalId[User])(implicit session: RSession): Option[User] = {
    externalIdCache.getOrElseOpt(UserExternalIdKey(id)) {
      (for(f <- externalIdColumn if f.externalId === id) yield f).firstOption
    }
  }

}
