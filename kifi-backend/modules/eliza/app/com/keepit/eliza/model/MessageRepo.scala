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
trait MessageRepo extends Repo[Message] with ExternalIdColumnFunction[Message] {

  def updateUriId(message: Message, uriId: Id[NormalizedURI])(implicit session: RWSession): Unit

  def refreshCache(thread: Id[MessageThread])(implicit session: RSession): Unit

  def get(thread: Id[MessageThread], from: Int)(implicit session: RSession): Seq[Message]

  def getAfter(threadId: Id[MessageThread], after: DateTime)(implicit session: RSession): Seq[Message]

  def getFromIdToId(fromId: Id[Message], toId: Id[Message])(implicit session: RSession): Seq[Message]

  def updateUriIds(updates: Seq[(Id[NormalizedURI], Id[NormalizedURI])])(implicit session: RWSession): Unit

  def getMaxId()(implicit session: RSession): Id[Message]

  def getMessageCounts(threadId: Id[MessageThread], afterOpt: Option[DateTime])(implicit session: RSession): (Int, Int)

  def getLatest(threadId: Id[MessageThread])(implicit session: RSession): Message

}

@Singleton
class MessageRepoImpl @Inject() (
  val clock: Clock,
  val db: DataBaseComponent,
  val messagesForThreadIdCache: MessagesForThreadIdCache)
    extends DbRepo[Message] with MessageRepo with ExternalIdColumnDbFunction[Message] with Logging with MessagingTypeMappers {
  import DBSession._
  import db.Driver.simple._

  type RepoImpl = MessageTable
  class MessageTable(tag: Tag) extends RepoTable[Message](db, tag, "message") with ExternalIdColumn[Message] {
    def from = column[Option[Id[User]]]("sender_id", O.Nullable)
    def thread = column[Id[MessageThread]]("thread_id", O.NotNull)
    def threadExtId = column[ExternalId[MessageThread]]("thread_ext_id", O.NotNull)
    def messageText = column[String]("message_text", O.NotNull)
    def source = column[Option[MessageSource]]("source", O.Nullable)
    def auxData = column[Option[JsArray]]("aux_data", O.Nullable)
    def sentOnUrl = column[Option[String]]("sent_on_url", O.Nullable)
    def sentOnUriId = column[Option[Id[NormalizedURI]]]("sent_on_uri_id", O.Nullable)
    def nonUserSender = column[Option[JsValue]]("non_user_sender", O.Nullable)

    def * = (id.?, createdAt, updatedAt, externalId, from, thread, threadExtId, messageText, source, auxData, sentOnUrl, sentOnUriId, nonUserSender) <> ((Message.fromDbTuple _).tupled, Message.toDbTuple)
  }
  def table(tag: Tag) = new MessageTable(tag)

  override def invalidateCache(message: Message)(implicit session: RSession): Unit = {
    val key = MessagesForThreadIdKey(message.thread)
    messagesForThreadIdCache.remove(key)
  }

  override def deleteCache(model: Message)(implicit session: RSession): Unit = {
    val key = MessagesForThreadIdKey(model.thread)
    messagesForThreadIdCache.remove(key)
  }

  def updateUriId(message: Message, uriId: Id[NormalizedURI])(implicit session: RWSession): Unit = {
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

  def get(threadId: Id[MessageThread], from: Int)(implicit session: RSession): Seq[Message] = { //Note: Cache does not honor pagination!! -Stephen
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

  def getAfter(threadId: Id[MessageThread], after: DateTime)(implicit session: RSession): Seq[Message] = {
    (for (row <- rows if row.thread === threadId && row.createdAt > after) yield row).sortBy(row => row.createdAt desc).list
  }

  def updateUriIds(updates: Seq[(Id[NormalizedURI], Id[NormalizedURI])])(implicit session: RWSession): Unit = { //Note: There is potentially a race condition here with updateUriId. Need to investigate.
    updates.foreach {
      case (oldId, newId) =>
        (for (row <- rows if row.sentOnUriId === oldId) yield row.sentOnUriId).update(Some(newId))
      //todo(stephen): do you invalidate cache here? do you?
    }
  }

  def getFromIdToId(fromId: Id[Message], toId: Id[Message])(implicit session: RSession): Seq[Message] = {
    (for (row <- rows if row.id >= fromId && row.id <= toId) yield row).list
  }

  def getMaxId()(implicit session: RSession): Id[Message] = {
    import StaticQuery.interpolation
    val sql = sql"select max(id) as max from message"
    sql.as[Long].firstOption.map(Id[Message]).getOrElse(Id[Message](0))
  }

  def getMessageCounts(threadId: Id[MessageThread], afterOpt: Option[DateTime])(implicit session: RSession): (Int, Int) = {
    afterOpt.map { after =>
      StaticQuery.queryNA[(Int, Int)](s"select count(*), sum(created_at > '$after') from message where thread_id = $threadId").first
    } getOrElse {
      val n = Query(rows.filter(row => row.thread === threadId).length).first
      (n, n)
    }
  }

  def getLatest(threadId: Id[MessageThread])(implicit session: RSession): Message = {
    (for (row <- rows if row.thread === threadId && row.from.isDefined) yield row).sortBy(row => row.id desc).first
  }

}
