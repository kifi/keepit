package com.keepit.abook

import com.google.inject.{ImplementedBy, Inject}
import com.keepit.model.{ABookOriginType, User, ABookInfo}
import com.keepit.common.db.slick._
import com.keepit.common.time.Clock
import com.keepit.common.logging.Logging
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession

@ImplementedBy(classOf[ABookInfoRepoImpl])
trait ABookInfoRepo extends Repo[ABookInfo] {
  def findByUserIdAndOriginOpt(userId:Id[User], origin:ABookOriginType)(implicit session:RSession):Option[ABookInfo]
  def findByUserId(userId:Id[User])(implicit session:RSession):Seq[ABookInfo]
}

class ABookInfoRepoImpl @Inject() (val db:DataBaseComponent, val clock:Clock) extends DbRepo[ABookInfo] with ABookInfoRepo with Logging {

  import db.Driver.Implicit._
  import DBSession._
  import FortyTwoTypeMappers._

  override val table = new RepoTable[ABookInfo](db, "abook_info") {
    def userId = column[Id[User]]("user_id", O.NotNull)
    def origin = column[ABookOriginType]("origin", O.NotNull)
    def rawInfoLoc = column[String]("raw_info_loc")
    def * = id.? ~ createdAt ~ updatedAt ~ state ~ userId ~ origin ~ rawInfoLoc.? <> (ABookInfo.apply _, ABookInfo.unapply _)
  }

  def findByUserIdAndOriginOpt(userId: Id[User], origin: ABookOriginType)(implicit session:RSession):Option[ABookInfo] = {
    val q = for { c <- table if c.userId === userId && c.origin === origin } yield c
    q.firstOption
  }

  def findByUserId(userId: Id[User])(implicit session: RSession): Seq[ABookInfo] = {
    val q = for { c <- table if c.userId === userId } yield c
    q.list
  }
}