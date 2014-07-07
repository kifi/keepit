package com.keepit.abook

import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.db.Id
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession.{RWSession, RSession}
import com.keepit.common.logging.Logging
import com.keepit.common.mail.{BasicContact, EmailAddress}
import com.keepit.common.performance._
import com.keepit.common.time._
import com.keepit.model._
import scala.slick.jdbc.{StaticQuery => Q}
import scala.slick.util.CloseableIterator

@ImplementedBy(classOf[EContactRepoImpl])
trait EContactRepo extends Repo[EContact] {
  def getById(econtactId:Id[EContact])(implicit session:RSession): Option[EContact]
  def getByIds(econtactIds:Seq[Id[EContact]])(implicit session:RSession):Seq[EContact]
  def bulkGetByIds(ids:Seq[Id[EContact]])(implicit session:RSession):Map[Id[EContact], EContact]
  def getByIdsIter(ids:Traversable[Id[EContact]])(implicit session:RSession): CloseableIterator[EContact]
  def getByUserIdAndEmail(userId: Id[User], email: EmailAddress)(implicit session: RSession): Option[EContact]
  def getByUserIdIter(userId: Id[User], maxRows: Int = 100)(implicit session: RSession): CloseableIterator[EContact]
  def getByUserId(userId: Id[User])(implicit session:RSession):Seq[EContact]
  def getEContactCount(userId: Id[User])(implicit session:RSession):Int
  def insertAll(userId:Id[User], abookId: Id[ABookInfo], contacts: Seq[BasicContact])(implicit session:RWSession): Unit
  def internContact(userId:Id[User], abookId: Id[ABookInfo], contact: BasicContact)(implicit session: RWSession): EContact
  def updateOwnership(email: EmailAddress, verifiedOwner: Option[Id[User]])(implicit session: RWSession): Int

  //used only for full resync
  def getIdRangeBatch(minId: Id[EContact], maxId: Id[EContact], maxBatchSize: Int)(implicit session: RSession): Seq[EContact]
}

@Singleton
class EContactRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock,
  econtactCache: EContactCache,
  override protected val changeListener: Option[RepoModification.Listener[EContact]]
) extends DbRepo[EContact] with EContactRepo with Logging {

  import DBSession._
  import db.Driver.simple._

  type RepoImpl = EContactTable
  class EContactTable(tag: Tag) extends RepoTable[EContact](db, tag, "econtact") {
    def userId     = column[Id[User]]("user_id", O.NotNull)
    def abookId     = column[Id[ABookInfo]]("abook_id", O.Nullable)
    def email      = column[EmailAddress]("email", O.NotNull)
    def name       = column[String]("name", O.Nullable)
    def firstName  = column[String]("first_name", O.Nullable)
    def lastName   = column[String]("last_name", O.Nullable)
    def contactUserId = column[Id[User]]("contact_user_id", O.Nullable)
    def * = (id.?, createdAt, updatedAt, userId, abookId.?, email, name.?, firstName.?, lastName.?, contactUserId.?, state) <> ((EContact.apply _).tupled, EContact.unapply _)
  }

  def table(tag: Tag) = new EContactTable(tag)

  override def deleteCache(e: EContact)(implicit session: RSession): Unit = {
    e.id map { id =>
      econtactCache.remove(EContactKey(id))
    }
  }

  override def invalidateCache(e: EContact)(implicit session: RSession): Unit = { // eager
    econtactCache.set(EContactKey(e.id.get), e)
    log.info(s"[invalidateCache] processed $e") // todo(ray): typeahead invalidation (rare; upper layer)
  }

  def getById(econtactId:Id[EContact])(implicit session:RSession):Option[EContact] = {
    (for(f <- rows if f.id === econtactId) yield f).firstOption
  }

  def getByIds(ids:Seq[Id[EContact]])(implicit session:RSession):Seq[EContact] = {
    (for(f <- rows if f.id.inSet(ids)) yield f).list
  }

  def getByIdsIter(ids:Traversable[Id[EContact]])(implicit session:RSession):CloseableIterator[EContact] = {
    (for(f <- rows if f.id.inSet(ids)) yield f).iterator
  }

  def bulkGetByIds(ids:Seq[Id[EContact]])(implicit session:RSession):Map[Id[EContact], EContact] = {
    if (ids.isEmpty) Map.empty[Id[EContact], EContact]
    else {
      val valueMap = econtactCache.bulkGetOrElse(ids.map(EContactKey(_)).toSet) { keys =>
        val missing = keys.map(_.id)
        val contacts = getByIdsIter(missing)
        val res = contacts.collect { case (c) if c.state == EContactStates.ACTIVE =>
          (EContactKey(c.id.get) -> c)
        }.toMap
        log.info(s"[bulkGetByIds(${ids.length};${ids.take(20).mkString(",")})] MISS: ids:(len=${ids.size})${ids.mkString(",")} res=${res.values.toSeq.take(20)}")
        res
      }
      val res = valueMap.map { case(k,v) => (k.id -> v) }
      log.info(s"[bulkGetByIds(${ids.length};${ids.take(20).mkString(",")}): ALL: res(sz=${res.size})${res.values.toSeq.take(20)}")
      res
    }
  }

  def getByUserIdAndEmail(userId: Id[User], email: EmailAddress)(implicit session: RSession): Option[EContact] = {
    (for(f <- rows if f.userId === userId && f.email === email && f.state === EContactStates.ACTIVE) yield f).firstOption
  }

  def getByUserIdIter(userId: Id[User], maxRows:Int)(implicit session: RSession): CloseableIterator[EContact] = {
    val limit = math.min(MySQL.MAX_ROW_LIMIT, maxRows)
    (for(f <- rows if f.userId === userId && f.state === EContactStates.ACTIVE) yield f).iteratorTo(limit)
  }

  def getByUserId(userId: Id[User])(implicit session: RSession): Seq[EContact] = {
    (for(f <- rows if f.userId === userId && f.state === EContactStates.ACTIVE) yield f).list
  }

  def getEContactCount(userId: Id[User])(implicit session: RSession): Int = {
    Q.queryNA[Int](s"select count(*) from econtact where user_id=$userId and state='active'").first
  }

  def insertAll(userId: Id[User], abookId: Id[ABookInfo], contacts: Seq[BasicContact])(implicit session:RWSession): Unit = timing(s"econtactRepo.insertAll($userId) #contacts=${contacts.length}") {
    val toBeInserted = contacts.map { contact => EContact(userId = userId, abookId = Some(abookId), email = contact.email, name = contact.name, firstName = contact.firstName, lastName = contact.lastName) }
    rows.insertAll(toBeInserted: _*)
  }

  def internContact(userId:Id[User], abookId: Id[ABookInfo], contact: BasicContact)(implicit session: RWSession): EContact = {
    getByUserIdAndEmail(userId, contact.email) match {
      case None => save(EContact(userId = userId, abookId = Some(abookId), email = contact.email, name = contact.name, firstName = contact.firstName, lastName = contact.lastName))
      case Some(existingContact) => existingContact.updateWith(contact) match {
        case modifiedContact if modifiedContact != existingContact => save(modifiedContact)
        case _ => existingContact
      }
    }
  }

  def updateOwnership(email: EmailAddress, verifiedOwner: Option[Id[User]])(implicit session: RWSession): Int = {
    val updated = (for { row <- rows if row.email === email && row.contactUserId =!= verifiedOwner.orNull } yield row.contactUserId.?).update(verifiedOwner)
    if (updated > 0) {
      val updatedContacts = for { row <- rows if row.email === email } yield row
      updatedContacts.foreach(invalidateCache)
    }
    updated
  }

  //used only for full resync
  def getIdRangeBatch(minId: Id[EContact], maxId: Id[EContact], maxBatchSize: Int)(implicit session: RSession): Seq[EContact] = {
    (for (row <- rows if row.id > minId && row.id <= maxId) yield row).sortBy(r => r.id).take(maxBatchSize).list
  }

}
