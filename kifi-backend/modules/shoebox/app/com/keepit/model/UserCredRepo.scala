package com.keepit.model

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }

import com.keepit.common.db.slick._
import com.keepit.common.db.{ State, Id }
import com.keepit.common.logging.Logging
import com.keepit.common.time.Clock
import securesocial.core.{ Registry, PasswordInfo }

@ImplementedBy(classOf[UserCredRepoImpl])
trait UserCredRepo extends Repo[UserCred] with RepoWithDelete[UserCred] {
  def findByUserIdOpt(id: Id[User], excludeState: Option[State[UserCred]] = Some(UserCredStates.INACTIVE))(implicit session: RSession): Option[UserCred]
  def internUserPassword(userId: Id[User], credentials: String)(implicit session: RWSession): UserCred
  def verifyPassword(userId: Id[User], password: String)(implicit session: RSession): Boolean
}

@Singleton
class UserCredRepoImpl @Inject() (val db: DataBaseComponent, val clock: Clock) extends DbRepo[UserCred] with DbRepoWithDelete[UserCred] with UserCredRepo with Logging {

  import db.Driver.simple._

  type RepoImpl = UserCredTable
  class UserCredTable(tag: Tag) extends RepoTable[UserCred](db, tag, "user_cred") {
    def userId = column[Id[User]]("user_id", O.NotNull)
    def credentials = column[String]("credentials") // TODO: char[]
    def * = (id.?, createdAt, updatedAt, state, userId, credentials) <> ((UserCred.apply _).tupled, UserCred.unapply _)
  }

  def table(tag: Tag) = new UserCredTable(tag)
  initTable()

  override def deleteCache(model: UserCred)(implicit session: RSession): Unit = {}
  override def invalidateCache(model: UserCred)(implicit session: RSession): Unit = {}

  def findByUserIdOpt(id: Id[User], excludeState: Option[State[UserCred]] = Some(UserCredStates.INACTIVE))(implicit session: RSession): Option[UserCred] = {
    val q = for { c <- rows if c.userId === id && c.state =!= excludeState.orNull } yield c
    q.firstOption
  }

  def internUserPassword(userId: Id[User], credentials: String)(implicit session: RWSession): UserCred = {
    findByUserIdOpt(userId, excludeState = None) match {
      case Some(cred) if cred.state == UserCredStates.ACTIVE => {
        val updatedCred = cred.withCredentials(credentials)
        if (cred != updatedCred) {
          val savedCred = save(updatedCred)
          log.info(s"[internUserPassword] Persisted updated $savedCred for user $userId")
          savedCred
        } else cred
      }
      case inactiveCredOpt =>
        val cred = UserCred(id = inactiveCredOpt.flatMap(_.id), userId = userId, credentials = credentials)
        val savedCred = save(cred)
        log.info(s"[internUserPassword] Persisted new $savedCred for user $userId")
        savedCred
    }
  }

  def verifyPassword(userId: Id[User], password: String)(implicit session: RSession): Boolean = {
    findByUserIdOpt(userId).exists { userCred =>
      val currentPasswordInfo = PasswordInfo(UserCred.passwordHasher, userCred.credentials)
      val hasher = Registry.hashers.currentHasher
      hasher.matches(currentPasswordInfo, password)
    }
  }
}
