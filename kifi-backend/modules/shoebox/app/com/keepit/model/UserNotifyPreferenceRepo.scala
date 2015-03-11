package com.keepit.model

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db.slick._
import com.keepit.common.db.{ State, Id }
import com.keepit.common.time._
import com.keepit.common.mail.ElectronicMailCategory

@ImplementedBy(classOf[UserNotifyPreferenceRepoImpl])
trait UserNotifyPreferenceRepo extends Repo[UserNotifyPreference] {
  def getByUser(userId: Id[User], excludeState: Option[State[UserNotifyPreference]] = Some(UserNotifyPreferenceStates.INACTIVE))(implicit session: RSession): Seq[UserNotifyPreference]
  def canNotify(userId: Id[User], name: String)(implicit session: RSession): Boolean
  def canNotify(userId: Id[User], name: ElectronicMailCategory)(implicit session: RSession): Boolean
  def canNotify(userId: Id[User], name: NotifyPreference)(implicit session: RSession): Boolean
  def setNotifyPreference(userId: Id[User], name: String, canSend: Boolean)(implicit session: RWSession): Unit
  def setNotifyPreference(userId: Id[User], name: ElectronicMailCategory, canSend: Boolean)(implicit session: RWSession): Unit
  def setNotifyPreference(userId: Id[User], pref: NotifyPreference, canSend: Boolean)(implicit session: RWSession): Unit
}

@Singleton
class UserNotifyPreferenceRepoImpl @Inject() (val db: DataBaseComponent, val clock: Clock) extends DbRepo[UserNotifyPreference] with UserNotifyPreferenceRepo {

  import db.Driver.simple._

  type RepoImpl = UserNotifyPreferenceTable
  class UserNotifyPreferenceTable(tag: Tag) extends RepoTable[UserNotifyPreference](db, tag, "user_notify_preference") {
    def userId = column[Id[User]]("user_id", O.NotNull)
    def name = column[String]("name", O.NotNull)
    def canSend = column[Boolean]("can_send", O.NotNull)
    def * = (id.?, createdAt, updatedAt, userId, name, canSend, state) <> ((UserNotifyPreference.apply _).tupled, UserNotifyPreference.unapply _)
  }

  def table(tag: Tag) = new UserNotifyPreferenceTable(tag)
  initTable()

  override def deleteCache(model: UserNotifyPreference)(implicit session: RSession): Unit = {}
  override def invalidateCache(model: UserNotifyPreference)(implicit session: RSession): Unit = {}

  def getByUser(userId: Id[User], excludeState: Option[State[UserNotifyPreference]] = Some(UserNotifyPreferenceStates.INACTIVE))(implicit session: RSession): Seq[UserNotifyPreference] = {
    (for (f <- rows if f.userId === userId && f.state =!= excludeState.orNull) yield f).list
  }

  def canNotify(userId: Id[User], name: ElectronicMailCategory)(implicit session: RSession): Boolean =
    canNotify(userId, "email_" + name.category)

  def canNotify(userId: Id[User], pref: NotifyPreference)(implicit session: RSession): Boolean =
    canNotify(userId, pref.name)

  def canNotify(userId: Id[User], name: String)(implicit session: RSession): Boolean = {
    (for (f <- rows if f.userId === userId && f.name === name && f.state === UserNotifyPreferenceStates.ACTIVE) yield f.canSend).firstOption.getOrElse(true)
  }

  def setNotifyPreference(userId: Id[User], name: ElectronicMailCategory, canSend: Boolean)(implicit session: RWSession): Unit =
    setNotifyPreference(userId, "email_" + name.category, canSend)

  def setNotifyPreference(userId: Id[User], pref: NotifyPreference, canSend: Boolean)(implicit session: RWSession): Unit =
    setNotifyPreference(userId, pref.name, canSend)

  def setNotifyPreference(userId: Id[User], name: String, canSend: Boolean)(implicit session: RWSession): Unit = {
    val updated = (for (f <- rows if f.userId === userId && f.name === name) yield (f.state, f.updatedAt, f.canSend))
      .update(UserNotifyPreferenceStates.ACTIVE, clock.now, canSend)
    if (updated == 0) {
      save(UserNotifyPreference(userId = userId, name = name, canSend = canSend))
    }
  }
}
