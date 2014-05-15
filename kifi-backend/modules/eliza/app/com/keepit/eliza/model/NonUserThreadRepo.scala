package com.keepit.eliza.model

import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.db.slick.{Repo, DbRepo, DataBaseComponent}
import com.keepit.common.db.slick.DBSession.{RWSession, RSession}
import org.joda.time.DateTime
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.common.db.{State, Id}
import com.keepit.model.{User, EContact, NormalizedURI}
import com.keepit.common.mail.EmailAddressHolder
import com.keepit.common.crypto.RatherInsecureDESCrypt
import com.keepit.social.{NonUserKind, NonUserKinds}
import scala.slick.jdbc.StaticQuery.interpolation

@ImplementedBy(classOf[NonUserThreadRepoImpl])
trait NonUserThreadRepo extends Repo[NonUserThread] {

  def getThreadsByEmail(emailAddress: EmailAddressHolder)(implicit session: RSession): Seq[Id[MessageThread]]

  def getThreadsByEContactId(econtactId: Id[EContact])(implicit session: RSession): Seq[Id[MessageThread]]

  def getNonUserThreadsForEmailing(before: DateTime)(implicit session: RSession): Seq[NonUserThread]

  def getByMessageThreadId(messageThreadId: Id[MessageThread])(implicit session: RSession): Seq[NonUserThread]

  def updateUriIds(updates: Seq[(Id[NormalizedURI], Id[NormalizedURI])])(implicit session: RWSession): Unit

  def setMuteState(nonUserThreadId: Id[NonUserThread], muted: Boolean)(implicit session: RWSession): Boolean

  def setMuteState(muteToken: String, muted: Boolean)(implicit session: RWSession): Boolean

  def setLastNotifiedAndIncCount(nut: Id[NonUserThread], dt: DateTime)(implicit session: RWSession): Unit

  def getByAccessToken(token: ThreadAccessToken)(implicit session: RSession): Option[NonUserThread]

}

/**
 * If we ever add cache to this repo and need to invalidate it then pay attention to the update statments!
 */
@Singleton
class NonUserThreadRepoImpl @Inject() (
  val clock: Clock,
  val db: DataBaseComponent
  )
  extends DbRepo[NonUserThread] with NonUserThreadRepo with MessagingTypeMappers with Logging {

  import db.Driver.simple._

  implicit val nonUserKind = MappedColumnType.base[NonUserKind, String](_.name, NonUserKind.apply)

  type RepoImpl = NonUserThreadTable
  class NonUserThreadTable(tag: Tag) extends RepoTable[NonUserThread](db, tag, "non_user_thread") {
    def createdBy = column[Id[User]]("created_by", O.NotNull)
    def kind = column[NonUserKind]("kind", O.NotNull)
    def emailAddress = column[EmailAddressHolder]("email_address", O.Nullable)
    def econtactId = column[Id[EContact]]("econtact_id", O.Nullable)
    def threadId = column[Id[MessageThread]]("thread_id", O.NotNull)
    def uriId = column[Id[NormalizedURI]]("uri_id", O.Nullable)
    def notifiedCount = column[Int]("notified_count", O.NotNull)
    def lastNotifiedAt = column[DateTime]("last_notified_at", O.Nullable)
    def threadUpdatedAt = column[DateTime]("thread_updated_at", O.Nullable)
    def muted = column[Boolean]("muted", O.NotNull)
    def accessToken = column[ThreadAccessToken]("access_token", O.NotNull)

    def * = (id.?, createdAt, updatedAt, createdBy, kind, emailAddress.?, econtactId.?, threadId, uriId.?, notifiedCount, lastNotifiedAt.?, threadUpdatedAt.?, muted, state, accessToken) <> (rowToObj2 _, objToRow _)

    private def rowToObj2(t: (Option[Id[NonUserThread]], DateTime, DateTime, Id[User], NonUserKind, Option[EmailAddressHolder], Option[Id[EContact]], Id[MessageThread], Option[Id[NormalizedURI]], Int, Option[DateTime], Option[DateTime], Boolean, State[NonUserThread], ThreadAccessToken)): NonUserThread = {
      val participant = t._5 match {
        case NonUserKinds.email =>
          NonUserEmailParticipant(t._6.get, t._7)
      }
      NonUserThread(id = t._1, createdAt = t._2, updatedAt = t._3, createdBy = t._4, participant = participant, threadId = t._8, uriId = t._9, notifiedCount = t._10, lastNotifiedAt = t._11, threadUpdatedAt = t._12, muted = t._13, state = t._14, accessToken = t._15)
    }

    private def objToRow(n: NonUserThread) = {
      val (kind, emailAddress, econtactId) = n.participant match {
        case ep: NonUserEmailParticipant =>
          (ep.kind, Option(ep.address), ep.econtactId)
      }
      Option((n.id, n.createdAt, n.updatedAt, n.createdBy, kind, emailAddress, econtactId, n.threadId, n.uriId, n.notifiedCount, n.lastNotifiedAt, n.threadUpdatedAt, n.muted, n.state, n.accessToken))
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

  val crypt = new RatherInsecureDESCrypt()
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

  def setLastNotifiedAndIncCount(nut: Id[NonUserThread], dt: DateTime)(implicit session: RWSession): Unit = {
    sqlu"""UPDATE non_user_thread
      SET notified_count = notified_count+1
      WHERE id = $nut
    """.execute()
    (for (row <- rows if row.id===nut) yield (row.lastNotifiedAt, row.updatedAt)).update(dt, currentDateTime)
  }

  def getByAccessToken(token: ThreadAccessToken)(implicit session: RSession): Option[NonUserThread] = {
    (for (row <- rows if row.accessToken===token) yield row).firstOption
  }

}
