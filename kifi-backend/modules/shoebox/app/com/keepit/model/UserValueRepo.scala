package com.keepit.model

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.db.slick._
import com.keepit.common.db.{ LargeString, State, Id }
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.time.{ Clock, DEFAULT_DATE_TIME_ZONE }
import org.joda.time.DateTime
import com.keepit.model.UserValues.UserValueHandler

@ImplementedBy(classOf[UserValueRepoImpl])
trait UserValueRepo extends Repo[UserValue] {
  def getValue[T](userId: Id[User], handler: UserValueHandler[T])(implicit session: RSession): T
  def getValueStringOpt(userId: Id[User], key: String)(implicit session: RSession): Option[String]
  def getValues(userId: Id[User], keys: String*)(implicit session: RSession): Map[String, Option[String]]
  def getUserValue(userId: Id[User], key: String)(implicit session: RSession): Option[UserValue]
  def setValue[T](userId: Id[User], name: String, value: T)(implicit session: RWSession): T
  def clearValue(userId: Id[User], name: String)(implicit session: RWSession): Boolean
}

@Singleton
class UserValueRepoImpl @Inject() (
  val db: DataBaseComponent,
  val valueCache: UserValueCache,
  val clock: Clock)
    extends DbRepo[UserValue] with UserValueRepo {
  import db.Driver.simple._
  import DBSession._

  type RepoImpl = UserValueTable
  case class UserValueTable(tag: Tag) extends RepoTable[UserValue](db, tag, "user_value") {
    def userId = column[Id[User]]("user_id", O.NotNull)
    def value = column[LargeString]("value", O.NotNull)
    def name = column[String]("name", O.NotNull)

    def * = (id.?, createdAt, updatedAt, userId, name, value, state) <> (rowToObj, objToRow)
  }

  private val rowToObj: ((Option[Id[UserValue]], DateTime, DateTime, Id[User], String, LargeString, State[UserValue])) => UserValue = {
    case (id, createdAt, updatedAt, userId, name, LargeString(value), state) => UserValue(id, createdAt, updatedAt, userId, name, value, state)
  }

  private val objToRow: UserValue => Option[(Option[Id[UserValue]], DateTime, DateTime, Id[User], String, LargeString, State[UserValue])] = {
    case UserValue(id, createdAt, updatedAt, userId, name, value, state) => Some((id, createdAt, updatedAt, userId, name, LargeString(value), state))
    case _ => None
  }

  def table(tag: Tag) = new UserValueTable(tag)

  override def invalidateCache(userValue: UserValue)(implicit session: RSession): Unit = {
    valueCache.remove(UserValueKey(userValue.userId, userValue.name))
  }

  override def deleteCache(userValue: UserValue)(implicit session: RSession): Unit = {
    valueCache.remove(UserValueKey(userValue.userId, userValue.name))
  }

  private def getValueUnsafeNoCache[T](userId: Id[User], key: String)(implicit session: RSession): Option[String] = {
    (for (f <- rows if f.state === UserValueStates.ACTIVE && f.userId === userId && f.name === key) yield f.value).firstOption.map(_.value)
  }

  def getValueStringOpt(userId: Id[User], key: String)(implicit session: RSession): Option[String] = {
    valueCache.getOrElseOpt(UserValueKey(userId, key)) {
      getValueUnsafeNoCache(userId, key)
    }
  }

  def getValue[T](userId: Id[User], handler: UserValueHandler[T])(implicit session: RSession): T = {
    val value = getValueStringOpt(userId, handler.name)
    handler.parse(value)
  }

  def getValues(userId: Id[User], names: String*)(implicit session: RSession): Map[String, Option[String]] =
    valueCache.bulkGetOrElseOpt(names map { name => UserValueKey(userId, name) } toSet) { missingNames =>
      val missingValues = missingNames map { missingName =>
        missingName -> getValueUnsafeNoCache(userId, missingName.key)
      }
      missingValues.toMap
    } map {
      case (k, v) =>
        k.key -> v
    }

  def getUserValue(userId: Id[User], name: String)(implicit session: RSession): Option[UserValue] =
    (for (f <- rows if f.state === UserValueStates.ACTIVE && f.userId === userId && f.name === name) yield f).firstOption

  def setValue[T](userId: Id[User], name: String, value: T)(implicit session: RWSession): T = {
    val stringValue = value.toString
    val updated = (for (v <- rows if v.userId === userId && v.name === name) yield (v.value, v.state, v.updatedAt)).update((stringValue, UserValueStates.ACTIVE, clock.now())) > 0
    if (updated) {
      valueCache.remove(UserValueKey(userId, name))
      value
    } else {
      save(UserValue(userId = userId, name = name, value = stringValue))
      value
    }
  }

  def clearValue(userId: Id[User], name: String)(implicit session: RWSession): Boolean = {
    val changed = (for (v <- rows if v.userId === userId && v.name === name && v.state =!= UserValueStates.INACTIVE) yield (v.state, v.updatedAt))
      .update((UserValueStates.INACTIVE, clock.now())) > 0
    if (changed) {
      valueCache.remove(UserValueKey(userId, name))
    }
    changed
  }

}
