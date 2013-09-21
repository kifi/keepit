package com.keepit.model


import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.db.slick.DBSession.RSession

//import com.keepit.common.db.Id._
import com.keepit.common.db.slick._
import com.keepit.common.db.{ExternalId, Id, State}
import com.keepit.common.logging.Logging
import com.keepit.common.time.Clock

//import play.api.libs.concurrent.Execution.Implicits._

@ImplementedBy(classOf[UserCredRepoImpl])
trait UserCredRepo extends Repo[UserCred] {
  def findByUserIdOpt(id:Id[User])(implicit session:RSession):Option[UserCred]
  def findByEmailOpt(email:String)(implicit session:RSession):Option[UserCred]
}

@Singleton
class UserCredRepoImpl @Inject() (val db:DataBaseComponent, val clock:Clock) extends DbRepo[UserCred] with UserCredRepo with Logging {

  import scala.slick.lifted.Query
  import db.Driver.Implicit._
  import DBSession._
  import FortyTwoTypeMappers._

  override val table = new RepoTable[UserCred](db, "user_cred") {
    def userId      = column[Id[User]]("user_id", O.NotNull)
    def loginName   = column[String]("login_name", O.NotNull)
    def provider    = column[String]("provider")
    def salt        = column[String]("salt")        // TODO: char[]
    def credentials = column[String]("credentials") // TODO: char[]
    def * = id.? ~ createdAt ~ updatedAt ~ userId ~ loginName ~ provider ~ salt ~ credentials <> (UserCred.apply _, UserCred.unapply _)
  }

  def findByUserIdOpt(id: Id[User])(implicit session: RSession): Option[UserCred] = {
    val q = for { c <- table if c.userId === id } yield c
    q.firstOption
  }

  def findByEmailOpt(email:String)(implicit session:RSession):Option[UserCred] = {
    val q = for { c <- table if c.loginName === email } yield c
    q.firstOption
  }

}
