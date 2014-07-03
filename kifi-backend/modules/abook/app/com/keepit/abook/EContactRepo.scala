package com.keepit.abook

import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.abook.typeahead.EContactABookTypeahead
import com.keepit.common.db.Id
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession.{RWSession, RSession}
import com.keepit.common.logging.Logging
import com.keepit.common.mail.{BasicContact, EmailAddress}
import com.keepit.common.performance._
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.typeahead.abook.{EContactTypeaheadKey, EContactTypeaheadCache}

import play.api.Play.current
import play.api.Play

import scala.slick.jdbc.{StaticQuery => Q, StaticQuery0}
import Q.interpolation
import scala.slick.util.CloseableIterator
import scala.util.{Success, Try, Failure}

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
  def insertAll(userId:Id[User], contacts:Seq[EContact])(implicit session:RWSession):Unit
  def bulkInvalidateCache(userId:Id[User], contacts:Seq[EContact]): Unit // special handling for bulk insert/delete (i.e. insertAll)
  def getOrCreate(userId:Id[User], contact: BasicContact)(implicit session: RWSession):Try[EContact]
  def recordVerifiedEmail(email: EmailAddress, contactUserId: Id[User])(implicit session: RWSession): Int
  def hideEmailFromUser(userId: Id[User], email: EmailAddress)(implicit session: RSession): Int
  def updateOwnership(email: EmailAddress, verifiedOwner: Option[Id[User]])(implicit session: RWSession): Int

  //used only for full resync
  def getIdRangeBatch(minId: Id[EContact], maxId: Id[EContact], maxBatchSize: Int)(implicit session: RSession): Seq[EContact]
}

@Singleton
class EContactRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock,
  val econtactTypeaheadCache: EContactTypeaheadCache,
  val econtactCache: EContactCache,
  override protected val changeListener: Option[RepoModification.Listener[EContact]]
) extends DbRepo[EContact] with EContactRepo with Logging {

  import DBSession._
  import db.Driver.simple._

  type RepoImpl = EContactTable
  class EContactTable(tag: Tag) extends RepoTable[EContact](db, tag, "econtact") {
    def userId     = column[Id[User]]("user_id", O.NotNull)
    def email      = column[EmailAddress]("email", O.NotNull)
    def name       = column[String]("name", O.Nullable)
    def firstName  = column[String]("first_name", O.Nullable)
    def lastName   = column[String]("last_name", O.Nullable)
    def contactUserId = column[Id[User]]("contact_user_id", O.Nullable)
    def * = (id.?, createdAt, updatedAt, userId, email, name.?, firstName.?, lastName.?, contactUserId.?, state) <> ((EContact.apply _).tupled, EContact.unapply _)
  }

  def table(tag: Tag) = new EContactTable(tag)

  override def deleteCache(e: EContact)(implicit session: RSession): Unit = {
    e.id map { id =>
      econtactCache.remove(EContactKey(id))
    }
    econtactTypeaheadCache.remove(EContactTypeaheadKey(e.userId))
  }

  override def invalidateCache(e: EContact)(implicit session: RSession): Unit = { // eager
    econtactCache.set(EContactKey(e.id.get), e)
    log.info(s"[invalidateCache] processed $e") // todo(ray): typeahead invalidation (rare; upper layer)
  }

  def bulkInvalidateCache(userId:Id[User], contacts:Seq[EContact]): Unit = { // special handling for bulk insert/delete (i.e. insertAll)
    import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess
    contacts.foreach { e =>
      e.id match {
        case Some(id) => econtactCache.set(EContactKey(id), e)
        case None =>
          log.warn(s"[bulkInvalidateCache($userId)] $e does not have id set")
          // moving on
      }
    }
    log.info(s"[bulkInvalidateCache($userId)] #contacts=${contacts.length}; ${contacts.take(20).mkString(",")}")
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

  def insertAll(userId: Id[User], contacts: Seq[EContact])(implicit session:RWSession): Unit = timing(s"econtactRepo.insertAll($userId) #contacts=${contacts.length}") {
    var i = 0
    contacts.grouped(500).foreach { g =>
      val t = System.currentTimeMillis
      i += 1
      timing(s"econtactRepo.batchInsertAll($userId,batch($i,sz=${g.length}))") { rows.insertAll(g: _*) }
    }
  }

  private[this] def deleteByUserId(userId: Id[User])(implicit session: RWSession): Int = {
    Q.updateNA(s"delete from econtact where user_id=${userId.id}").first
  }

  val NULL = "NULL"

  private[this] def insertOnDupUpdate(userId: Id[User], c: EContact)(implicit session: RWSession): Unit = {
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

  def getOrCreate(userId:Id[User], contact: BasicContact)(implicit session: RWSession):Try[EContact] = Try {

    val c = EContact(userId = userId, email = contact.email, name = contact.name, firstName = contact.firstName, lastName = contact.lastName)
    insertOnDupUpdate(userId, c) // todo: optimize (if needed)
    val cOpt = getByUserIdAndEmail(userId, contact.email)
    cOpt match {
      case Some(e) => {
        invalidateCache(e)
        e
      }
      case None => throw new IllegalStateException(s"Failed to retrieve econtact for ${contact.email}")
    }
  }

  def updateOwnership(email: EmailAddress, verifiedOwner: Option[Id[User]])(implicit session: RWSession): Int = {
    val updated = (for { row <- rows if row.email === email && row.contactUserId =!= verifiedOwner.orNull } yield row.contactUserId).update(verifiedOwner.orNull)
    if (updated > 0) {
      val updatedContacts = for { row <- rows if row.email === email } yield row
      updatedContacts.foreach(invalidateCache)
    }
    updated
  }

  def hideEmailFromUser(userId: Id[User], email: EmailAddress)(implicit session: RSession): Int = {
    (for { row <- rows if row.email === email } yield row.state).update(EContactStates.HIDDEN)
  }

  //used only for full resync
  def getIdRangeBatch(minId: Id[EContact], maxId: Id[EContact], maxBatchSize: Int)(implicit session: RSession): Seq[EContact] = {
    (for (row <- rows if row.id > minId && row.id <= maxId) yield row).sortBy(r => r.id).take(maxBatchSize).list
  }

}
