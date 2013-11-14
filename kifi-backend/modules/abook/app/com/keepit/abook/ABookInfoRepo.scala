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
  def getById(id:Id[ABookInfo])(implicit session:RSession):Option[ABookInfo]
  def findByUserIdOriginAndOwnerId(userId:Id[User], origin:ABookOriginType, ownerId:Option[String])(implicit session:RSession):Option[ABookInfo]
  def findByUserIdAndOrigin(userId:Id[User], origin:ABookOriginType)(implicit session:RSession):Seq[ABookInfo]
  def findByUserId(userId:Id[User])(implicit session:RSession):Seq[ABookInfo]
}

class ABookInfoRepoImpl @Inject() (val db:DataBaseComponent, val clock:Clock) extends DbRepo[ABookInfo] with ABookInfoRepo with Logging {

  import db.Driver.Implicit._
  import DBSession._
  import FortyTwoTypeMappers._

  override val table = new RepoTable[ABookInfo](db, "abook_info") {
    def userId = column[Id[User]]("user_id", O.NotNull)
    def origin = column[ABookOriginType]("origin", O.NotNull)
    def ownerId = column[String]("owner_id")
    def ownerEmail = column[String]("owner_email")
    def rawInfoLoc = column[String]("raw_info_loc")
    def * = id.? ~ createdAt ~ updatedAt ~ state ~ userId ~ origin ~ ownerId.? ~ ownerEmail.? ~ rawInfoLoc.? <> (ABookInfo.apply _, ABookInfo.unapply _)
  }

  def getById(id: Id[ABookInfo])(implicit session: RSession): Option[ABookInfo] = {
    (for { c <- table if c.id === id } yield c).firstOption
  }

  def findByUserIdOriginAndOwnerId(userId: Id[User], origin: ABookOriginType, ownerId:Option[String])(implicit session:RSession): Option[ABookInfo] = {
    val q = for { c <- table if c.userId === userId && c.origin === origin && c.ownerId === ownerId} yield c // assumption: NULL === None
    q.firstOption
  }

  def findByUserIdAndOrigin(userId: Id[User], origin: ABookOriginType)(implicit session:RSession): Seq[ABookInfo] = {
    val q = for { c <- table if c.userId === userId && c.origin === origin } yield c
    q.list
  }

  def findByUserId(userId: Id[User])(implicit session: RSession): Seq[ABookInfo] = {
    val q = for { c <- table if c.userId === userId } yield c
    q.list
  }
}