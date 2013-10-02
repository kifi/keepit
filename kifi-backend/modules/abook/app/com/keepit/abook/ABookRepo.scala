package com.keepit.abook

import com.google.inject.{ImplementedBy, Inject}
import com.keepit.model.{ABookOriginType, User, ABook}
import com.keepit.common.db.slick._
import com.keepit.common.time.Clock
import com.keepit.common.logging.Logging
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession

@ImplementedBy(classOf[ABookRepoImpl])
trait ABookRepo extends Repo[ABook] {
  def findByUserIdAndOriginOpt(userId:Id[User], origin:ABookOriginType)(implicit session:RSession):Option[ABook]
}

class ABookRepoImpl @Inject() (val db:DataBaseComponent, val clock:Clock) extends DbRepo[ABook] with ABookRepo with Logging {

  import db.Driver.Implicit._
  import DBSession._
  import FortyTwoTypeMappers._

  override val table = new RepoTable[ABook](db, "abook") {
    def userId = column[Id[User]]("user_id", O.NotNull)
    def origin = column[ABookOriginType]("origin", O.NotNull)
    def rawInfoLoc = column[String]("raw_info_loc")
    def * = id.? ~ createdAt ~ updatedAt ~ state ~ userId ~ origin ~ rawInfoLoc.? <> (ABook.apply _, ABook.unapply _)
  }

  def findByUserIdAndOriginOpt(userId: Id[User], origin: ABookOriginType)(implicit session:RSession):Option[ABook] = {
    val q = for { c <- table if c.userId === userId && c.origin === origin } yield c
    q.firstOption
  }
}