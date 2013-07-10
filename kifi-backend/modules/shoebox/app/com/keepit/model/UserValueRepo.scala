package com.keepit.model

import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.db.slick._
import com.keepit.common.db.{LargeString, Id}
import com.keepit.common.db.slick.DBSession.{RWSession, RSession}
import com.keepit.common.time.Clock
import scala.Some

@ImplementedBy(classOf[UserValueRepoImpl])
trait UserValueRepo extends Repo[UserValue] {
  def getValue(userId: Id[User], key: String)(implicit session: RSession): Option[String]
  def setValue(userId: Id[User], name: String, value: String)(implicit session: RWSession): String
  // def clearValue(userId: Id[User], name: String)(implicit session: RWSession): Unit // TODO
}

@Singleton
class UserValueRepoImpl @Inject() (
  val db: DataBaseComponent,
  val valueCache: UserValueCache,
  val clock: Clock)
    extends DbRepo[UserValue] with UserValueRepo {
  import FortyTwoTypeMappers._
  import scala.slick.lifted.Query
  import db.Driver.Implicit._
  import DBSession._
  import scala.util.matching.Regex

  override val table = new RepoTable[UserValue](db, "user_value") {
    def userId = column[Id[User]]("user_id", O.NotNull)
    def value = column[LargeString]("value", O.NotNull)
    def name = column[String]("name", O.NotNull)

    def * = id.? ~ createdAt ~ updatedAt ~ userId ~ name ~ value ~ state <> (
      apply => apply match {
        case (id, createdAt, updatedAt, userId, name, value, state) =>
          UserValue(id, createdAt, updatedAt, userId, name, value.value, state)
      },
      unapply => unapply match {
        case UserValue(id, createdAt, updatedAt, userId, name, value, state) =>
          Some((id, createdAt, updatedAt, userId, name, LargeString(value), state))
        case _ => None
      })
  }

  override def invalidateCache(userValue: UserValue)(implicit session: RSession) = {
    valueCache.remove(UserValueKey(userValue.userId, userValue.name))
    userValue
  }

  def getValue(userId: Id[User], name: String)(implicit session: RSession): Option[String] = {
    valueCache.getOrElseOpt(UserValueKey(userId, name)) {
      (for(f <- table if f.state === UserValueStates.ACTIVE && f.userId === userId && f.name === name) yield f.value).firstOption.map(_.value)
    }
  }

  def setValue(userId: Id[User], name: String, value: String)(implicit session: RWSession): String = {
    (for (v <- table if v.userId === userId && v.name === name) yield v).firstOption.map { v =>
      save(v.copy(value = value))
    }.getOrElse {
      save(UserValue(userId = userId, name = name, value = value))
    }.value
  }

}
