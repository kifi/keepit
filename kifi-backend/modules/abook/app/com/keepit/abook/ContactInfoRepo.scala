package com.keepit.abook

import com.google.inject.{Inject, Singleton, ImplementedBy}
import scala.slick.util.CloseableIterator
import com.keepit.common.db.slick.Repo
import com.keepit.model.{ABookInfo, ABookOriginType, ContactInfo, User}
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.{RWSession, RSession}
import com.keepit.common.db.slick.DataBaseComponent
import com.keepit.common.time.Clock
import com.keepit.common.db.slick.DbRepo
import com.keepit.common.db.slick.FortyTwoTypeMappers
import com.keepit.common.db.slick.DBSession

@ImplementedBy(classOf[ContactInfoRepoImpl])
trait ContactInfoRepo extends Repo[ContactInfo] {
  def getByUserIdIter(userId: Id[User], maxRows: Int = 100)(implicit session: RSession): CloseableIterator[ContactInfo]
  def getByUserIdAndABookInfoIdIter(userId: Id[User], abookInfoId: Id[ABookInfo], maxRows: Int = 100)(implicit session:RSession): CloseableIterator[ContactInfo]
  def deleteByUserIdAndABookInfo(userId: Id[User], abookInfoId: Id[ABookInfo])(implicit session:RWSession): Int
}

@Singleton
class ContactInfoRepoImpl @Inject() (val db: DataBaseComponent, val clock: Clock) extends DbRepo[ContactInfo] with ContactInfoRepo {
  import FortyTwoTypeMappers._
  import db.Driver.Implicit._
  import DBSession._

  override val table = new RepoTable[ContactInfo](db, "contact_info") {
    def userId = column[Id[User]]("user_id", O.NotNull)
    def abookId = column[Id[ABookInfo]]("abook_id", O.NotNull)
    def email = column[String]("email", O.NotNull)
    def origin = column[ABookOriginType]("origin", O.Nullable)
    def name = column[String]("name", O.Nullable)
    def firstName = column[String]("first_name", O.Nullable)
    def lastName = column[String]("last_name", O.Nullable)
    def pictureUrl = column[String]("picture_url", O.Nullable)
    def parentId = column[Id[ContactInfo]]("parent_id", O.Nullable)
    def * = id.? ~ createdAt ~ updatedAt ~ userId ~ abookId ~ email ~ origin ~ name.? ~ firstName.? ~ lastName.? ~ pictureUrl.? ~ parentId.? <> (ContactInfo.apply _, ContactInfo.unapply _)
  }

  def getByUserIdIter(userId: Id[User], maxRows:Int)(implicit session: RSession): CloseableIterator[ContactInfo] =
    (for(f <- table if f.userId === userId) yield f).elementsTo(maxRows)
  def getByUserIdAndABookInfoIdIter(userId: Id[User], abookInfoId:Id[ABookInfo], maxRows:Int)(implicit session: RSession): CloseableIterator[ContactInfo] =
    (for(f <- table if f.userId === userId && f.abookId === abookInfoId) yield f).elementsTo(maxRows)
  def deleteByUserIdAndABookInfo(userId: Id[User], abookInfoId: Id[ABookInfo])(implicit session: RWSession): Int =
    (for(f <- table if f.userId === userId && f.abookId === abookInfoId) yield f).delete // TODO: REVISIT
}


