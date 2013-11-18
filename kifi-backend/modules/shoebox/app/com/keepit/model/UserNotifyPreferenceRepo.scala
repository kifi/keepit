package com.keepit.model

import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.db.slick.DBSession.{RWSession, RSession}
import com.keepit.common.db.slick._
import com.keepit.common.db.{State, Id}
import com.keepit.common.time._
import com.keepit.common.mail.ElectronicMailCategory

@ImplementedBy(classOf[UserNotifyPreferenceRepoImpl])
trait UserNotifyPreferenceRepo extends Repo[UserNotifyPreference] {
  def getByUser(userId: Id[User], excludeState: Option[State[UserNotifyPreference]] = Some(UserNotifyPreferenceStates.INACTIVE))(implicit session: RSession): Seq[UserNotifyPreference]
  def canNotify(userId: Id[User], name: String)(implicit session: RSession): Boolean
  def canNotify(userId: Id[User], name: ElectronicMailCategory)(implicit session: RSession): Boolean
  def setNotifyPreference(userId: Id[User], name: String, canSend: Boolean)(implicit session: RWSession): Unit
}

@Singleton
class UserNotifyPreferenceRepoImpl @Inject() (val db: DataBaseComponent, val clock: Clock) extends DbRepo[UserNotifyPreference] with UserNotifyPreferenceRepo {
  import FortyTwoTypeMappers._
  import db.Driver.Implicit._
  import DBSession._

  override val table = new RepoTable[UserNotifyPreference](db, "user_notify_preference") {
    def userId = column[Id[User]]("user_id", O.NotNull)
    def name = column[String]("name", O.NotNull)
    def canSend = column[Boolean]("can_send", O.NotNull)
    def * = id.? ~ createdAt ~ updatedAt ~ userId ~ name ~ canSend ~ state <> (UserNotifyPreference, UserNotifyPreference.unapply _)
  }

  def getByUser(userId: Id[User], excludeState: Option[State[UserNotifyPreference]] = Some(UserNotifyPreferenceStates.INACTIVE))(implicit session: RSession): Seq[UserNotifyPreference] = {
    (for(f <- table if f.userId === userId && f.state =!= excludeState.orNull) yield f).list
  }

  def canNotify(userId: Id[User], name: ElectronicMailCategory)(implicit session: RSession): Boolean =
    canNotify(userId, "email_" + name.category)

  def canNotify(userId: Id[User], name: String)(implicit session: RSession): Boolean = {
    (for(f <- table if f.userId === userId && f.name === name && f.state === UserNotifyPreferenceStates.ACTIVE) yield f.canSend).firstOption.getOrElse(true)
  }

  def setNotifyPreference(userId: Id[User], name: String, canSend: Boolean)(implicit session: RWSession): Unit = {
    val updated = (for(f <- table if f.userId === userId && f.name === name) yield f.state ~ f.updatedAt ~ f.canSend)
      .update(UserNotifyPreferenceStates.ACTIVE, clock.now, canSend)
    if (updated == 0) {
      save(UserNotifyPreference(userId = userId, name = name, canSend = canSend))
    }
  }
}
