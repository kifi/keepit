package com.keepit.abook

import com.google.inject.{Inject, Singleton, ImplementedBy}
import scala.slick.util.CloseableIterator
import com.keepit.common.db.slick.Repo
import com.keepit.model._
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.{RWSession, RSession}
import com.keepit.common.db.slick.DataBaseComponent
import com.keepit.common.db.slick.DbRepo
import com.keepit.common.db.slick.FortyTwoTypeMappers
import com.keepit.common.db.slick.DBSession
import scala.slick.jdbc.{StaticQuery => Q}
import Q.interpolation
import com.keepit.common.time._
import com.keepit.common.logging.Logging
import com.keepit.common.performance._
import scala.slick.driver.JdbcDriver.simple._


@ImplementedBy(classOf[ContactRepoImpl])
trait ContactRepo extends Repo[Contact] {
  def getByUserIdIter(userId: Id[User], maxRows: Int = 100)(implicit session: RSession): CloseableIterator[Contact]
  def getByUserIdAndABookInfoIdIter(userId: Id[User], abookInfoId: Id[ABookInfo], maxRows: Int = 100)(implicit session:RSession): CloseableIterator[Contact]
  def deleteByUserIdAndABookInfoId(userId: Id[User], abookInfoId: Id[ABookInfo])(implicit session:RWSession): Int
  def getContactCount(userId: Id[User], abookInfoId:Id[ABookInfo])(implicit session:RSession):Int
  def getContactCount(userId: Id[User])(implicit session:RSession):Int
  def deleteAndInsertAll(userId:Id[User], abookInfoId:Id[ABookInfo], origin:ABookOriginType, contactInfos:Seq[Contact])(implicit session:RWSession):Int
  def insertAll(userId:Id[User], abookInfoId:Id[ABookInfo], origin:ABookOriginType, contactInfos:Seq[Contact])(implicit session:RWSession):Unit
}

@Singleton
class ContactRepoImpl @Inject() (val db: DataBaseComponent, val clock: Clock) extends DbRepo[Contact] with ContactRepo with Logging {
  import FortyTwoTypeMappers._
  import DBSession._


  type RepoImpl = ContactTable
  class ContactTable(tag: Tag) extends RepoTable[Contact](db, tag, "contact") {
    def userId = column[Id[User]]("user_id", O.NotNull)
    def abookId = column[Id[ABookInfo]]("abook_id", O.NotNull)
    def email  = column[String]("email", O.NotNull)
    def emailList = column[String]("email_list")
    def origin = column[ABookOriginType]("origin", O.Nullable)
    def name = column[String]("name", O.Nullable)
    def firstName = column[String]("first_name", O.Nullable)
    def lastName = column[String]("last_name", O.Nullable)
    def pictureUrl = column[String]("picture_url", O.Nullable)
    def * = (id.?, createdAt, updatedAt, userId, abookId, email, emailList.?, origin, name.?, firstName.?, lastName.?, pictureUrl.?) <> ((Contact.apply _).tupled, Contact.unapply _)
  }

  def table(tag: Tag) = new ContactTable(tag)
//  val rows = TableQuery(table)

  override def deleteCache(model: Contact)(implicit session: RSession): Unit = {}
  override def invalidateCache(model: Contact)(implicit session: RSession): Unit = {}

  def getByUserIdIter(userId: Id[User], maxRows:Int)(implicit session: RSession): CloseableIterator[Contact] =
    (for(f <- rows if f.userId === userId) yield f).iteratorTo(maxRows)

  def getByUserIdAndABookInfoIdIter(userId: Id[User], abookInfoId:Id[ABookInfo], maxRows:Int)(implicit session: RSession): CloseableIterator[Contact] =
    (for(f <- rows if f.userId === userId && f.abookId === abookInfoId) yield f).iteratorTo(maxRows)

  def deleteByUserIdAndABookInfoId(userId: Id[User], abookInfoId: Id[ABookInfo])(implicit session: RWSession): Int = {
    val ts = System.currentTimeMillis
    val rows = sqlu"delete from contact where user_id=${userId.id} and abook_id=${abookInfoId.id}".first
    log.info(s"[delete(user_id=$userId,abook_id=$abookInfoId)] $rows deleted. time-lapsed: ${System.currentTimeMillis - ts}")
    rows
  }

  def getContactCount(userId: Id[User], abookInfoId: Id[ABookInfo])(implicit session: RSession): Int = {
    Q.queryNA[Int](s"select count(*) from contact where user_id=$userId and abook_id=${abookInfoId.id}").first
  }

  def getContactCount(userId: Id[User])(implicit session: RSession): Int = {
    Q.queryNA[Int](s"select count(*) from contact where user_id=$userId").first
  }

  def insertAll(userId: Id[User], abookInfoId: Id[ABookInfo], origin:ABookOriginType, contactInfos: Seq[Contact])(implicit session:RWSession): Unit =
    timing(s"contactRepo.insertAll($userId,$abookInfoId,$origin) #contacts=${contactInfos.length}") {
      var i = 0
      contactInfos.grouped(500).foreach { g =>
        i += 1
        timing(s"contactRepo.insertBatch($userId,$abookInfoId,$origin,batch($i,sz=${g.length}))") {
          rows.insertAll(g: _*)
        }
      }
    }

  def deleteAndInsertAll(userId: Id[User], abookInfoId: Id[ABookInfo], origin: ABookOriginType, contactInfos: Seq[Contact])(implicit session: RWSession): Int =
    timing(s"contactRepo.deleteAndInsertAll($userId,$abookInfoId,$origin) #contacts=${contactInfos.length}") {
      val rows = deleteByUserIdAndABookInfoId(userId, abookInfoId)
      insertAll(userId, abookInfoId, origin, contactInfos)
      rows
    }
}


