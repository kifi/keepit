package com.keepit.abook.model

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.db.{ DbSequenceAssigner, Id }
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.logging.Logging
import com.keepit.common.mail.EmailAddress
import com.keepit.common.time._
import com.keepit.model._
import scala.slick.jdbc.StaticQuery
import scala.slick.util.CloseableIterator
import com.keepit.common.plugin.{ SequencingActor, SchedulingProperties, SequencingPlugin }
import com.keepit.common.actor.ActorInstance
import scala.concurrent.duration._
import com.keepit.common.healthcheck.AirbrakeNotifier

@ImplementedBy(classOf[EContactRepoImpl])
trait EContactRepo extends Repo[EContact] with SeqNumberFunction[EContact] {
  def bulkGetByIds(ids: Seq[Id[EContact]])(implicit session: RSession): Map[Id[EContact], EContact]
  def getByUserIdAndEmail(userId: Id[User], email: EmailAddress)(implicit session: RSession): Seq[EContact]
  def getByUserId(userId: Id[User])(implicit session: RSession): Seq[EContact]
  def getUserIdsByEmail(email: EmailAddress)(implicit session: RSession): Seq[Id[User]]
  def countEmailContacts(userId: Id[User], distinctEmailAccounts: Boolean)(implicit session: RSession): Int
  def insertAll(contacts: Seq[EContact])(implicit session: RWSession): Int
  def hideEmailFromUser(userId: Id[User], email: EmailAddress)(implicit session: RSession): Boolean
  def updateOwnership(emailAccountId: Id[EmailAccount], verifiedOwner: Option[Id[User]])(implicit session: RWSession): Int
  def getByAbookIdAndEmailId(abookId: Id[ABookInfo], emailId: Id[EmailAccount])(implicit session: RSession): Option[EContact]
  def getByAbookId(abookId: Id[ABookInfo])(implicit session: RSession): Seq[EContact]
}

@Singleton
class EContactRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock,
    econtactCache: EContactCache,
    override protected val changeListener: Option[RepoModification.Listener[EContact]]) extends DbRepo[EContact] with SeqNumberDbFunction[EContact] with EContactRepo with Logging {

  import DBSession._
  import db.Driver.simple._

  type RepoImpl = EContactTable
  class EContactTable(tag: Tag) extends RepoTable[EContact](db, tag, "econtact") with SeqNumberColumn[EContact] {
    def userId = column[Id[User]]("user_id", O.NotNull)
    def abookId = column[Id[ABookInfo]]("abook_id", O.NotNull)
    def emailAccountId = column[Id[EmailAccount]]("email_account_id", O.NotNull)
    def email = column[EmailAddress]("email", O.NotNull)
    def contactUserId = column[Id[User]]("contact_user_id", O.Nullable)
    def name = column[String]("name", O.Nullable)
    def firstName = column[String]("first_name", O.Nullable)
    def lastName = column[String]("last_name", O.Nullable)
    def * = (id.?, createdAt, updatedAt, userId, abookId, emailAccountId, email, contactUserId.?, name.?, firstName.?, lastName.?, state, seq) <> ((EContact.apply _).tupled, EContact.unapply _)
  }

  def table(tag: Tag) = new EContactTable(tag)

  override def save(contact: EContact)(implicit session: RWSession): EContact = {
    val toSave = contact.copy(seq = deferredSeqNum())
    super.save(toSave)
  }

  override def deleteCache(e: EContact)(implicit session: RSession): Unit = {
    e.id map { id =>
      econtactCache.remove(EContactKey(id))
    }
  }

  override def invalidateCache(e: EContact)(implicit session: RSession): Unit = { // eager
    econtactCache.set(EContactKey(e.id.get), e)
    log.info(s"[invalidateCache] processed $e") // todo(ray): typeahead invalidation (rare; upper layer)
  }

  private def getByIdsIter(ids: Traversable[Id[EContact]])(implicit session: RSession): CloseableIterator[EContact] = {
    (for (f <- rows if f.id.inSet(ids)) yield f).iterator
  }

  def bulkGetByIds(ids: Seq[Id[EContact]])(implicit session: RSession): Map[Id[EContact], EContact] = {
    if (ids.isEmpty) Map.empty[Id[EContact], EContact]
    else {
      val valueMap = econtactCache.bulkGetOrElse(ids.map(EContactKey(_)).toSet) { keys =>
        val missing = keys.map(_.id)
        val contacts = getByIdsIter(missing)
        val res = contacts.collect {
          case (c) if c.state == EContactStates.ACTIVE =>
            (EContactKey(c.id.get) -> c)
        }.toMap
        log.info(s"[bulkGetByIds(${ids.length};${ids.take(20).mkString(",")})] MISS: ids:(len=${ids.size})${ids.mkString(",")} res=${res.values.toSeq.take(20)}")
        res
      }
      val res = valueMap.map { case (k, v) => (k.id -> v) }
      log.info(s"[bulkGetByIds(${ids.length};${ids.take(20).mkString(",")}): ALL: res(sz=${res.size})${res.values.toSeq.take(20)}")
      res
    }
  }

  def getByUserIdAndEmail(userId: Id[User], email: EmailAddress)(implicit session: RSession): Seq[EContact] = {
    (for (f <- rows if f.userId === userId && f.email === email && f.state === EContactStates.ACTIVE) yield f).list
  }

  def getByUserIdIter(userId: Id[User], maxRows: Int)(implicit session: RSession): CloseableIterator[EContact] = {
    val limit = math.min(MySQL.MAX_ROW_LIMIT, maxRows)
    (for (f <- rows if f.userId === userId && f.state === EContactStates.ACTIVE) yield f).iteratorTo(limit)
  }

  def getByUserId(userId: Id[User])(implicit session: RSession): Seq[EContact] = {
    (for (f <- rows if f.userId === userId && f.state === EContactStates.ACTIVE) yield f).list
  }

  def getUserIdsByEmail(email: EmailAddress)(implicit session: RSession): Seq[Id[User]] =
    (for (f <- rows if f.email === email && f.state === EContactStates.ACTIVE) yield f.userId).list

  def countEmailContacts(userId: Id[User], distinctEmailAccounts: Boolean)(implicit session: RSession): Int = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    if (distinctEmailAccounts) sql"""select count(distinct email_account_id) from econtact where user_id=$userId and state='#${EContactStates.ACTIVE}'""".as[Int].first
    else sql"""select count(*) from econtact where user_id=$userId and state='#${EContactStates.ACTIVE}'""".as[Int].first
  }

  def getByAbookIdAndEmailId(abookId: Id[ABookInfo], emailId: Id[EmailAccount])(implicit session: RSession): Option[EContact] = {
    (for (row <- rows if row.abookId === abookId && row.emailAccountId === emailId) yield row).firstOption
  }

  def getByAbookId(abookId: Id[ABookInfo])(implicit session: RSession): Seq[EContact] = {
    (for (row <- rows if row.abookId === abookId) yield row).list
  }

  def insertAll(contacts: Seq[EContact])(implicit session: RWSession): Int = {
    val toBeInserted = contacts.map(_.copy(seq = deferredSeqNum()))
    rows.insertAll(toBeInserted: _*).get
  }

  def updateOwnership(emailAccountId: Id[EmailAccount], verifiedOwner: Option[Id[User]])(implicit session: RWSession): Int = {
    val updated = (for { row <- rows if row.emailAccountId === emailAccountId && row.contactUserId =!= verifiedOwner.orNull } yield row.contactUserId.?).update(verifiedOwner)
    if (updated > 0) {
      val updatedContacts = for { row <- rows if row.emailAccountId === emailAccountId } yield row
      updatedContacts.foreach(invalidateCache)
    }
    updated
  }

  def hideEmailFromUser(userId: Id[User], email: EmailAddress)(implicit session: RSession): Boolean = {
    val updated = (for { row <- rows if row.userId === userId && row.email === email } yield row.state).update(EContactStates.HIDDEN)
    if (updated > 0) {
      val updatedContacts = for { row <- rows if row.userId === userId && row.email === email } yield row
      updatedContacts.foreach(invalidateCache)
    }
    updated > 0
  }
}

trait EContactSequencingPlugin extends SequencingPlugin

class EContactSequencingPluginImpl @Inject() (
    override val actor: ActorInstance[EContactSequencingActor],
    override val scheduling: SchedulingProperties) extends EContactSequencingPlugin {

  override val interval: FiniteDuration = 20 seconds
}

@Singleton
class EContactSequenceNumberAssigner @Inject() (db: Database, repo: EContactRepo, airbrake: AirbrakeNotifier)
  extends DbSequenceAssigner[EContact](db, repo, airbrake)

class EContactSequencingActor @Inject() (
  assigner: EContactSequenceNumberAssigner,
  airbrake: AirbrakeNotifier) extends SequencingActor(assigner, airbrake)
