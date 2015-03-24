package com.keepit.eliza.model

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.db.slick.{ Repo, DbRepo, DataBaseComponent }
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import org.joda.time.DateTime
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.common.db.{ State, Id }
import com.keepit.model.{ User, NormalizedURI }
import com.keepit.common.mail.EmailAddress
import com.keepit.common.crypto.RatherInsecureDESCrypt
import com.keepit.social.{ NonUserKind, NonUserKinds }
import com.keepit.common.db.slick.StaticQueryFixed.interpolation

@ImplementedBy(classOf[NonUserThreadRepoImpl])
trait NonUserThreadRepo extends Repo[NonUserThread] {

  def getThreadsByEmail(emailAddress: EmailAddress)(implicit session: RSession): Seq[Id[MessageThread]]

  def getNonUserThreadsForEmailing(lastNotifiedBefore: DateTime, threadUpdatedByOtherAfter: DateTime)(implicit session: RSession): Seq[NonUserThread]

  def getByMessageThreadId(messageThreadId: Id[MessageThread])(implicit session: RSession): Seq[NonUserThread]

  def updateUriIds(updates: Seq[(Id[NormalizedURI], Id[NormalizedURI])])(implicit session: RWSession): Unit

  def setMuteState(nonUserThreadId: Id[NonUserThread], muted: Boolean)(implicit session: RWSession): Boolean

  def setMuteState(muteToken: String, muted: Boolean)(implicit session: RWSession): Boolean

  def setLastNotifiedAndIncCount(nut: Id[NonUserThread])(implicit session: RWSession): Unit

  def getByAccessToken(token: ThreadAccessToken)(implicit session: RSession): Option[NonUserThread]

  def getRecentRecipientsByUser(userId: Id[User], since: DateTime)(implicit session: RSession): Map[EmailAddress, Int]

}

/**
 * If we ever add cache to this repo and need to invalidate it then pay attention to the update statments!
 */
@Singleton
class NonUserThreadRepoImpl @Inject() (
  val clock: Clock,
  val db: DataBaseComponent)
    extends DbRepo[NonUserThread] with NonUserThreadRepo with MessagingTypeMappers with Logging {

  import db.Driver.simple._

  implicit val nonUserKind = MappedColumnType.base[NonUserKind, String](_.name, NonUserKind.apply)

  type RepoImpl = NonUserThreadTable
  class NonUserThreadTable(tag: Tag) extends RepoTable[NonUserThread](db, tag, "non_user_thread") {
    def createdBy = column[Id[User]]("created_by", O.NotNull)
    def kind = column[NonUserKind]("kind", O.NotNull)
    def emailAddress = column[Option[EmailAddress]]("email_address", O.Nullable)
    def threadId = column[Id[MessageThread]]("thread_id", O.NotNull)
    def uriId = column[Option[Id[NormalizedURI]]]("uri_id", O.Nullable)
    def notifiedCount = column[Int]("notified_count", O.NotNull)
    def lastNotifiedAt = column[Option[DateTime]]("last_notified_at", O.Nullable)
    def threadUpdatedByOtherAt = column[Option[DateTime]]("thread_updated_at", O.Nullable)
    def muted = column[Boolean]("muted", O.NotNull)
    def accessToken = column[ThreadAccessToken]("access_token", O.NotNull)

    def * = (id.?, createdAt, updatedAt, createdBy, kind, emailAddress, threadId, uriId, notifiedCount, lastNotifiedAt, threadUpdatedByOtherAt, muted, state, accessToken) <> (rowToObj2 _, objToRow _)

    private def rowToObj2(t: (Option[Id[NonUserThread]], DateTime, DateTime, Id[User], NonUserKind, Option[EmailAddress], Id[MessageThread], Option[Id[NormalizedURI]], Int, Option[DateTime], Option[DateTime], Boolean, State[NonUserThread], ThreadAccessToken)): NonUserThread = {
      val participant = t._5 match {
        case NonUserKinds.email =>
          NonUserEmailParticipant(t._6.get)
      }
      NonUserThread(id = t._1, createdAt = t._2, updatedAt = t._3, createdBy = t._4, participant = participant, threadId = t._7, uriId = t._8, notifiedCount = t._9, lastNotifiedAt = t._10, threadUpdatedByOtherAt = t._11, muted = t._12, state = t._13, accessToken = t._14)
    }

    private def objToRow(n: NonUserThread) = {
      val (kind, emailAddress) = n.participant match {
        case ep: NonUserEmailParticipant =>
          (ep.kind, Option(ep.address))
      }
      Option((n.id, n.createdAt, n.updatedAt, n.createdBy, kind, emailAddress, n.threadId, n.uriId, n.notifiedCount, n.lastNotifiedAt, n.threadUpdatedByOtherAt, n.muted, n.state, n.accessToken))
    }
  }
  def table(tag: Tag) = new NonUserThreadTable(tag)

  override def deleteCache(model: NonUserThread)(implicit session: RSession): Unit = {}
  override def invalidateCache(model: NonUserThread)(implicit session: RSession): Unit = {}

  def getThreadsByEmail(emailAddress: EmailAddress)(implicit session: RSession): Seq[Id[MessageThread]] =
    (for (row <- rows if row.emailAddress === emailAddress) yield row.threadId).list

  def getNonUserThreadsForEmailing(lastNotifiedBefore: DateTime, threadUpdatedByOtherAfter: DateTime)(implicit session: RSession): Seq[NonUserThread] =
    (for (row <- rows if row.lastNotifiedAt.isEmpty || (row.lastNotifiedAt < lastNotifiedBefore && row.threadUpdatedByOtherAt > threadUpdatedByOtherAfter && row.lastNotifiedAt < row.threadUpdatedByOtherAt && !row.muted)) yield row).list

  def getByMessageThreadId(messageThreadId: Id[MessageThread])(implicit session: RSession): Seq[NonUserThread] =
    (for (row <- rows if row.threadId === messageThreadId) yield row).list

  def updateUriIds(updates: Seq[(Id[NormalizedURI], Id[NormalizedURI])])(implicit session: RWSession): Unit =
    updates.foreach {
      case (oldId, newId) =>
        (for (row <- rows if row.uriId === oldId) yield row.uriId).update(Some(newId))
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

  def setLastNotifiedAndIncCount(nut: Id[NonUserThread])(implicit session: RWSession): Unit = {
    val time = clock.now()
    sqlu"""UPDATE non_user_thread
      SET notified_count = notified_count+1, last_notified_at = $time, updated_at = $time
      WHERE id = $nut
    """.execute
  }

  def getByAccessToken(token: ThreadAccessToken)(implicit session: RSession): Option[NonUserThread] = {
    (for (row <- rows if row.accessToken === token) yield row).firstOption
  }

  def getRecentRecipientsByUser(userId: Id[User], since: DateTime)(implicit session: RSession): Map[EmailAddress, Int] = {
    val relevantThreads = (for (row <- rows if row.createdBy === userId && row.createdAt > since) yield row)
    val recentRecipients = relevantThreads.groupBy(_.emailAddress).map { case (recipient, threads) => (recipient, threads.length) }
    recentRecipients.run.toMap.collect {
      case (k, v) if k.isDefined =>
        k.get -> v
    }
  }

}
