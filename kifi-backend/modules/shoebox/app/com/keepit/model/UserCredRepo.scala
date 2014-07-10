package com.keepit.model

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.db.slick.DBSession.RSession

import com.keepit.common.db.slick._
import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.common.time.Clock

@ImplementedBy(classOf[UserCredRepoImpl])
trait UserCredRepo extends Repo[UserCred] with RepoWithDelete[UserCred] {
  def findByUserIdOpt(id: Id[User])(implicit session: RSession): Option[UserCred]
  def findByEmailOpt(email: String)(implicit session: RSession): Option[UserCred]
}

@Singleton
class UserCredRepoImpl @Inject() (val db: DataBaseComponent, val clock: Clock) extends DbRepo[UserCred] with DbRepoWithDelete[UserCred] with UserCredRepo with Logging {

  import db.Driver.simple._

  type RepoImpl = UserCredTable
  class UserCredTable(tag: Tag) extends RepoTable[UserCred](db, tag, "user_cred") {
    def userId = column[Id[User]]("user_id", O.NotNull)
    def loginName = column[String]("login_name", O.NotNull)
    def provider = column[String]("provider")
    def salt = column[String]("salt") // TODO: char[]
    def credentials = column[String]("credentials") // TODO: char[]
    def * = (id.?, createdAt, updatedAt, state, userId, loginName, provider, salt, credentials) <> ((UserCred.apply _).tupled, UserCred.unapply _)
  }

  def table(tag: Tag) = new UserCredTable(tag)
  initTable()

  override def deleteCache(model: UserCred)(implicit session: RSession): Unit = {}
  override def invalidateCache(model: UserCred)(implicit session: RSession): Unit = {}

  def findByUserIdOpt(id: Id[User])(implicit session: RSession): Option[UserCred] = {
    val q = for { c <- rows if c.userId === id && c.state === UserCredStates.ACTIVE } yield c
    q.firstOption
  }

  def findByEmailOpt(email: String)(implicit session: RSession): Option[UserCred] = {
    val q = for { c <- rows if c.loginName === email && c.state === UserCredStates.ACTIVE } yield c
    q.firstOption
  }

}
