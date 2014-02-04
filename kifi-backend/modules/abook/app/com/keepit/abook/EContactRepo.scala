package com.keepit.abook

import com.google.inject.{Inject, Singleton, ImplementedBy}
import scala.slick.util.CloseableIterator
import com.keepit.common.db.slick._
import com.keepit.model._
import com.keepit.common.performance._
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.{RWSession, RSession}
import scala.slick.jdbc.{StaticQuery => Q, StaticQuery0}
import Q.interpolation
import com.keepit.common.time._
import com.keepit.common.logging.Logging
import play.api.Play.current
import play.api.Play
import scala.util.{Success, Try, Failure}

@ImplementedBy(classOf[EContactRepoImpl])
trait EContactRepo extends Repo[EContact] {
  def getById(econtactId:Id[EContact])(implicit session:RSession): Option[EContact]
  def getByUserIdAndEmail(userId: Id[User], email:String)(implicit session: RSession): Option[EContact]
  def getByUserIdIter(userId: Id[User], maxRows: Int = 100)(implicit session: RSession): CloseableIterator[EContact]
  def getByUserId(userId: Id[User])(implicit session:RSession):Seq[EContact]
  def getEContactCount(userId: Id[User])(implicit session:RSession):Int
  def deleteByUserId(userId:Id[User])(implicit session:RWSession):Int
  def deleteAndInsertAll(userId:Id[User], contactInfos:Seq[EContact])(implicit session:RWSession):Int
  def insertAll(userId:Id[User], contacts:Seq[EContact])(implicit session:RWSession):Unit
  def insertOnDupUpdate(userId:Id[User], contact:EContact)(implicit session:RWSession):Unit
  def getOrCreate(userId:Id[User], email: String, name: Option[String], firstName: Option[String], lastName: Option[String])(implicit session: RWSession):Try[EContact]
}

@Singleton
class EContactRepoImpl @Inject() (val db: DataBaseComponent, val clock: Clock) extends DbRepo[EContact] with EContactRepo with Logging {
  import FortyTwoTypeMappers._
  import db.Driver.Implicit._
  import DBSession._

  override val table = new RepoTable[EContact](db, "econtact") {
    def userId     = column[Id[User]]("user_id", O.NotNull)
    def email      = column[String]("email", O.NotNull)
    def name       = column[String]("name", O.Nullable)
    def firstName  = column[String]("first_name", O.Nullable)
    def lastName   = column[String]("last_name", O.Nullable)
    def * = id.? ~ createdAt ~ updatedAt ~ userId ~ email ~ name.? ~ firstName.? ~ lastName.? ~ state <> (EContact.apply _, EContact.unapply _)
    def forInsert = createdAt ~ updatedAt ~ userId ~ email ~ name.? ~ firstName.? ~ lastName.? ~ state <> (
      {t => EContact(None, t._1, t._2, t._3, t._4, t._5, t._6, t._7, t._8)},
      {(c:EContact) => Some((c.createdAt, c.updatedAt, c.userId, c.email, c.name, c.firstName, c.lastName, c.state))})
  }

  override def deleteCache(model: EContact)(implicit session: RSession): Unit = {}
  override def invalidateCache(model: EContact)(implicit session: RSession): Unit = {}

  def getById(econtactId:Id[EContact])(implicit session:RSession):Option[EContact] = {
    (for(f <- table if f.id === econtactId) yield f).firstOption
  }
  def getByUserIdAndEmail(userId: Id[User], email:String)(implicit session: RSession): Option[EContact] = {
    (for(f <- table if f.userId === userId && f.email === email && f.state === EContactStates.ACTIVE) yield f).firstOption
  }

  def getByUserIdIter(userId: Id[User], maxRows:Int)(implicit session: RSession): CloseableIterator[EContact] =
    (for(f <- table if f.userId === userId && f.state === EContactStates.ACTIVE) yield f).elementsTo(maxRows)

  def getByUserId(userId: Id[User])(implicit session: RSession): Seq[EContact] =
    (for(f <- table if f.userId === userId && f.state === EContactStates.ACTIVE) yield f).list

  def getEContactCount(userId: Id[User])(implicit session: RSession): Int = {
    Q.queryNA[Int](s"select count(*) from econtact where user_id=$userId and state='active'").first
  }

  def insertAll(userId: Id[User], contacts: Seq[EContact])(implicit session:RWSession): Unit = timing(s"econtactRepo.insertAll($userId) #contacts=${contacts.length}") {
    var i = 0
    contacts.grouped(500).foreach { g =>
      val t = System.currentTimeMillis
      i += 1
      timing(s"econtactRepo.batchInsertAll($userId,batch($i,sz=${g.length}))") { table.forInsert insertAll(g: _*) }
    }
  }

  def deleteByUserId(userId: Id[User])(implicit session: RWSession): Int = {
    Q.updateNA(s"delete from econtact where user_id=${userId.id}").first
  }

  def deleteAndInsertAll(userId: Id[User], contacts: Seq[EContact])(implicit session: RWSession): Int = {
    val rows = deleteByUserId(userId)
    insertAll(userId, contacts)
    rows
  }

  val NULL = "NULL"

  def insertOnDupUpdate(userId: Id[User], c: EContact)(implicit session: RWSession): Unit = {
    val cdt = currentDateTime
    if (Play.maybeApplication.isDefined && Play.isProd) {
      sqlu"insert into econtact (user_id, created_at, updated_at, email, name, first_name, last_name) values (${userId.id}, $cdt, $cdt, ${c.email}, ${c.name}, ${c.firstName}, ${c.lastName}) on duplicate key update id=id".execute
    } else { // test-only branch (H2 workarounds)
      getByUserIdAndEmail(userId, c.email) match {
        case Some(e) => 0
        case None => {
          sqlu"insert into econtact (user_id, created_at, updated_at, email, name, first_name, last_name, state) values (${userId.id}, $cdt, $cdt, ${c.email}, ${c.name}, ${c.firstName}, ${c.lastName}, 'active')".execute
        }
      }
    }
    log.info(s"[insertOnDupUpdate(${userId.id}, ${c.email})]")
  }

  def getOrCreate(userId:Id[User], email: String, name: Option[String], firstName: Option[String], lastName: Option[String])(implicit session: RWSession):Try[EContact] = Try {
    if (userId == null || email == null) throw new IllegalArgumentException("userId and email cannot be null")

    val parsedResult = EmailParser.parseAll(EmailParser.email, email)
    if (!parsedResult.successful) throw new IllegalArgumentException(s"Invalid email: $email")

    val parsedEmail = parsedResult.get
    val c = EContact(userId = userId, email = parsedEmail.toString, name = name, firstName = firstName, lastName = lastName)
    insertOnDupUpdate(userId, c) // todo: optimize (if needed)
    val cOpt = getByUserIdAndEmail(userId, parsedEmail.toString)
    cOpt match {
      case Some(econtact) => econtact
      case None => throw new IllegalStateException(s"Failed to retrieve econtact for $email")
    }
  }

}
