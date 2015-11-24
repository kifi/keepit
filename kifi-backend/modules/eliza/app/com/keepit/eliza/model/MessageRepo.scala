package com.keepit.eliza.model

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import org.joda.time.DateTime
import com.keepit.common.time._
import com.keepit.common.db.{ Id, ExternalId }
import com.keepit.model.{ User, NormalizedURI }
import com.keepit.common.logging.Logging
import com.keepit.common.cache.CacheSizeLimitExceededException
import play.api.libs.json.{ JsArray, JsValue }
import scala.slick.jdbc.StaticQuery

@ImplementedBy(classOf[MessageRepoImpl])
trait MessageRepo extends Repo[ElizaMessage] with ExternalIdColumnFunction[ElizaMessage] {
  def updateUriId(message: ElizaMessage, uriId: Id[NormalizedURI])(implicit session: RWSession): Unit
  def refreshCache(thread: Id[MessageThread])(implicit session: RSession): Unit
  def get(thread: Id[MessageThread], from: Int)(implicit session: RSession): Seq[ElizaMessage]
  def getAfter(threadId: Id[MessageThread], after: DateTime)(implicit session: RSession): Seq[ElizaMessage]
  def getFromIdToId(fromId: Id[ElizaMessage], toId: Id[ElizaMessage])(implicit session: RSession): Seq[ElizaMessage]
  def updateUriIds(updates: Seq[(Id[NormalizedURI], Id[NormalizedURI])])(implicit session: RWSession): Unit
  def getMaxId()(implicit session: RSession): Id[ElizaMessage]
  def getMessageCounts(threadId: Id[MessageThread], afterOpt: Option[DateTime])(implicit session: RSession): (Int, Int)
  def getAllMessageCounts(threadIds: Set[Id[MessageThread]])(implicit session: RSession): Map[Id[MessageThread], Int]
  def getLatest(threadId: Id[MessageThread])(implicit session: RSession): ElizaMessage

  // PSA: please just use this method going forward, it has the cleanest API
  def getByThread(threadId: Id[MessageThread], fromId: Option[Id[ElizaMessage]], limit: Int)(implicit session: RSession): Seq[ElizaMessage]
}

@Singleton
class MessageRepoImpl @Inject() (
  val clock: Clock,
  val db: DataBaseComponent,
  val messagesForThreadIdCache: MessagesForThreadIdCache)
    extends DbRepo[ElizaMessage] with MessageRepo with ExternalIdColumnDbFunction[ElizaMessage] with Logging with MessagingTypeMappers {
  import DBSession._
  import db.Driver.simple._

  type RepoImpl = MessageTable
  class MessageTable(tag: Tag) extends RepoTable[ElizaMessage](db, tag, "message") with ExternalIdColumn[ElizaMessage] {
    def from = column[Option[Id[User]]]("sender_id", O.Nullable)
    def thread = column[Id[MessageThread]]("thread_id", O.NotNull)
    def threadExtId = column[ExternalId[MessageThread]]("thread_ext_id", O.NotNull)
    def messageText = column[String]("message_text", O.NotNull)
    def source = column[Option[MessageSource]]("source", O.Nullable)
    def auxData = column[Option[JsArray]]("aux_data", O.Nullable)
    def sentOnUrl = column[Option[String]]("sent_on_url", O.Nullable)
    def sentOnUriId = column[Option[Id[NormalizedURI]]]("sent_on_uri_id", O.Nullable)
    def nonUserSender = column[Option[JsValue]]("non_user_sender", O.Nullable)

    def * = (id.?, createdAt, updatedAt, externalId, from, thread, threadExtId, messageText, source, auxData, sentOnUrl, sentOnUriId, nonUserSender) <> ((ElizaMessage.fromDbTuple _).tupled, ElizaMessage.toDbTuple)
  }
  def table(tag: Tag) = new MessageTable(tag)

  // TODO(ryan): for some reason we can't delete messages? We really need to add a `state` column
  private def activeRows = rows

  override def invalidateCache(message: ElizaMessage)(implicit session: RSession): Unit = {
    val key = MessagesForThreadIdKey(message.thread)
    messagesForThreadIdCache.remove(key)
  }

  override def deleteCache(model: ElizaMessage)(implicit session: RSession): Unit = {
    val key = MessagesForThreadIdKey(model.thread)
    messagesForThreadIdCache.remove(key)
  }

  def updateUriId(message: ElizaMessage, uriId: Id[NormalizedURI])(implicit session: RWSession): Unit = {
    (for (row <- rows if row.id === message.id) yield row.sentOnUriId).update(Some(uriId))
    invalidateCache(message)
  }

  def refreshCache(threadId: Id[MessageThread])(implicit session: RSession): Unit = {
    val key = MessagesForThreadIdKey(threadId)
    val messages = (for (row <- rows if row.thread === threadId) yield row).sortBy(row => row.createdAt desc).list
    try {
      messagesForThreadIdCache.set(key, new MessagesForThread(threadId, messages))
    } catch {
      case c: CacheSizeLimitExceededException => // already reported in FortyTwoCache
    }
  }

  def get(threadId: Id[MessageThread], from: Int)(implicit session: RSession): Seq[ElizaMessage] = { //Note: Cache does not honor pagination!! -Stephen
    val key = MessagesForThreadIdKey(threadId)
    messagesForThreadIdCache.get(key) match {
      case Some(v) => {
        v.messages
      }
      case None => {
        val query = (for (row <- rows if row.thread === threadId) yield row).drop(from)
        val got = query.sortBy(row => row.createdAt desc).list
        val mft = new MessagesForThread(threadId, got)
        try {
          messagesForThreadIdCache.set(key, mft)
        } catch {
          case c: CacheSizeLimitExceededException => // already reported in FortyTwoCache
        }
        got
      }
    }
  }

  def getAfter(threadId: Id[MessageThread], after: DateTime)(implicit session: RSession): Seq[ElizaMessage] = {
    (for (row <- rows if row.thread === threadId && row.createdAt > after) yield row).sortBy(row => row.createdAt desc).list
  }

  def updateUriIds(updates: Seq[(Id[NormalizedURI], Id[NormalizedURI])])(implicit session: RWSession): Unit = { //Note: There is potentially a race condition here with updateUriId. Need to investigate.
    updates.foreach {
      case (oldId, newId) =>
        (for (row <- rows if row.sentOnUriId === oldId) yield row.sentOnUriId).update(Some(newId))
      //todo(stephen): do you invalidate cache here? do you?
    }
  }

  def getFromIdToId(fromId: Id[ElizaMessage], toId: Id[ElizaMessage])(implicit session: RSession): Seq[ElizaMessage] = {
    (for (row <- rows if row.id >= fromId && row.id <= toId) yield row).list
  }

  def getMaxId()(implicit session: RSession): Id[ElizaMessage] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    val sql = sql"select max(id) as max from message"
    sql.as[Long].firstOption.map(Id[ElizaMessage]).getOrElse(Id[ElizaMessage](0))
  }

  def getMessageCounts(threadId: Id[MessageThread], afterOpt: Option[DateTime])(implicit session: RSession): (Int, Int) = {
    afterOpt.map { after =>
      StaticQuery.queryNA[(Int, Int)](s"select count(*), sum(created_at > '$after') from message where thread_id = $threadId").first
    } getOrElse {
      val n = Query(rows.filter(row => row.thread === threadId).length).first
      (n, n)
    }
  }

  def getAllMessageCounts(threadIds: Set[Id[MessageThread]])(implicit session: RSession): Map[Id[MessageThread], Int] = {
    rows.filter(row => row.thread.inSet(threadIds)).groupBy(_.thread).map { case (thread, messages) => (thread, messages.length) }.list.toMap
  }

  def getLatest(threadId: Id[MessageThread])(implicit session: RSession): ElizaMessage = {
    (for (row <- rows if row.thread === threadId && row.from.isDefined) yield row).sortBy(row => row.id desc).first
  }

  def getByThread(threadId: Id[MessageThread], fromId: Option[Id[ElizaMessage]], limit: Int)(implicit session: RSession): Seq[ElizaMessage] = {
    val threadMessages = activeRows.filter(row => row.thread === threadId)
    val filteredMessages = fromId match {
      case None => threadMessages
      case Some(fid) =>
        val fromTime = rows.filter(_.id === fid).map(_.createdAt).first
        threadMessages.filter(r => r.createdAt > fromTime || (r.createdAt === fromTime && r.id > fid))
    }
    filteredMessages.sortBy(r => (r.createdAt desc, r.id desc)).take(limit).list
  }

}
