package com.keepit.eliza.model

import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.db.slick.{Repo, DbRepo, DataBaseComponent}
import com.keepit.common.db.slick.DBSession.{RWSession, RSession}
import org.joda.time.DateTime
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.common.db.{State, Id}
import com.keepit.model.{EContact, NormalizedURI}
import com.keepit.common.mail.EmailAddressHolder
import com.keepit.common.crypto.SimpleDESCrypt
import com.keepit.social.{NonUserKind, NonUserKinds}

@ImplementedBy(classOf[NonUserThreadRepoImpl])
trait NonUserThreadRepo extends Repo[NonUserThread] {

  def getThreadsByEmail(emailAddress: EmailAddressHolder)(implicit session: RSession): Seq[Id[MessageThread]]

  def getThreadsByEContactId(econtactId: Id[EContact])(implicit session: RSession): Seq[Id[MessageThread]]

  def getNonUserThreadsForEmailing(before: DateTime)(implicit session: RSession): Seq[NonUserThread]

  def getByMessageThreadId(messageThreadId: Id[MessageThread])(implicit session: RSession): Seq[NonUserThread]

  def updateUriIds(updates: Seq[(Id[NormalizedURI], Id[NormalizedURI])])(implicit session: RWSession): Unit

  def setMuteState(nonUserThreadId: Id[NonUserThread], muted: Boolean)(implicit session: RWSession): Boolean

  def setMuteState(muteToken: String, muted: Boolean)(implicit session: RWSession): Boolean

}

/**
 * If we ever add cache to this repo and need to invalidate it then pay attention to the update statments!
 */
@Singleton
class NonUserThreadRepoImpl @Inject() (
  val clock: Clock,
  val db: DataBaseComponent
  )
  extends DbRepo[NonUserThread] with NonUserThreadRepo with Logging {

  import db.Driver.simple._

  implicit val nonUserKind = MappedColumnType.base[NonUserKind, String](_.name, NonUserKind.apply)

  type RepoImpl = NonUserThreadTable
  class NonUserThreadTable(tag: Tag) extends RepoTable[NonUserThread](db, tag, "non_user_thread") with ExternalIdColumn[NonUserThread] {
    def kind = column[NonUserKind]("kind", O.NotNull)
    def emailAddress = column[EmailAddressHolder]("email_address", O.Nullable)
    def econtactId = column[Id[EContact]]("econtact_id", O.Nullable)
    def threadId = column[Id[MessageThread]]("thread_id", O.NotNull)
    def uriId = column[Id[NormalizedURI]]("uri_id", O.Nullable)
    def notifiedCount = column[Int]("notified_count", O.NotNull)
    def lastNotifiedAt = column[DateTime]("last_notified_at", O.Nullable)
    def threadUpdatedAt = column[DateTime]("thread_updated_at", O.Nullable)
    def muted = column[Boolean]("muted", O.NotNull)

    def * = (id.?, createdAt, updatedAt, kind, emailAddress.?, econtactId.?, threadId, uriId.?, notifiedCount, lastNotifiedAt.?, threadUpdatedAt.?, muted, state) <> (rowToObj _, objToRow _)

    private def rowToObj(id: Option[Id[NonUserThread]], createdAt: DateTime, updatedAt: DateTime, kind: NonUserKind, emailAddress: Option[EmailAddressHolder], econtactId: Option[Id[EContact]], threadId: Id[MessageThread], uriId: Option[Id[NormalizedURI]], notifiedCount: Int, lastNotifiedAt: Option[DateTime], threadUpdatedAt: Option[DateTime], muted: Boolean, state: State[NonUserThread]): NonUserThread = {
      val participant = kind match {
        case NonUserKinds.email =>
          NonUserEmailParticipant(emailAddress.get, econtactId)
      }
      NonUserThread(id, createdAt, updatedAt, participant, threadId, uriId, notifiedCount, lastNotifiedAt, threadUpdatedAt, muted, state)
    }

    private def objToRow(n: NonUserThread) = {
      val (kind, emailAddress, econtactId) = n.participant match {
        case ep: NonUserEmailParticipant =>
          (ep.kind, Option(ep.address), ep.econtactId)
      }
      Option((n.id, n.createdAt, n.updatedAt, kind, emailAddress, econtactId, n.threadId, n.uriId, n.notifiedCount, n.lastNotifiedAt, n.threadUpdatedAt, n.muted, n.state))
    }
  }
  def table(tag: Tag) = new NonUserThreadTable(tag)


  override def deleteCache(model: NonUserThread)(implicit session: RSession): Unit = {}
  override def invalidateCache(model: NonUserThread)(implicit session: RSession): Unit = {}

  def getThreadsByEmail(emailAddress: EmailAddressHolder)(implicit session: RSession): Seq[Id[MessageThread]] =
    (for (row <- rows if row.emailAddress === emailAddress) yield row.threadId).list

  def getThreadsByEContactId(econtactId: Id[EContact])(implicit session: RSession): Seq[Id[MessageThread]] =
    (for (row <- rows if row.econtactId === econtactId) yield row.threadId).list

  def getNonUserThreadsForEmailing(before: DateTime)(implicit session: RSession): Seq[NonUserThread] =
    (for (row <- rows if (row.lastNotifiedAt.isNull || row.lastNotifiedAt < row.threadUpdatedAt) && row.lastNotifiedAt < before) yield row).list

  def getByMessageThreadId(messageThreadId: Id[MessageThread])(implicit session: RSession): Seq[NonUserThread] =
    (for (row <- rows if row.threadId === messageThreadId) yield row).list

  def updateUriIds(updates: Seq[(Id[NormalizedURI], Id[NormalizedURI])])(implicit session: RWSession): Unit =
    updates.foreach{ case (oldId, newId) =>
      (for (row <- rows if row.uriId === oldId) yield row.uriId).update(newId)
    }

  def setMuteState(muteToken: String, muted: Boolean)(implicit session: RWSession): Boolean =
    muteTokenToNonUserThreadId(muteToken).map { id =>
      setMuteState(id, muted)
    }.getOrElse(false)

  def setMuteState(nonUserThreadId: Id[NonUserThread], muted: Boolean)(implicit session: RWSession): Boolean =
    (for (row <- rows if row.id === nonUserThreadId) yield row.muted).update(muted) > 0

  val crypt = new SimpleDESCrypt()
  val muteKey = crypt.stringToKey("non user thread id to muteKey key, word.")
  def nonUserThreadIdToMuteToken(id: Id[NonUserThread]) = {
    val encText = { // DES is a block cypher, so we'd prefer all blocks to be different for similar IDs
      val _id = id.id.toString
      _id.reverse.take(4) + " " + (" " * ((12 - _id.length) / 2) + _id).padTo(11, " ").mkString("")
    }
    crypt.crypt(muteKey, encText)
  }
  def muteTokenToNonUserThreadId(muteToken: String): Option[Id[NonUserThread]] = {
    val decText = crypt.decrypt(muteKey, muteToken)
    decText.map { i =>
      Id[NonUserThread](i.trim.split(" ").tail.dropWhile(_.length == 0).head.toLong)
    }.toOption
  }

}
