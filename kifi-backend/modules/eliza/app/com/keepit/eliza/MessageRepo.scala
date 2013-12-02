package com.keepit.eliza

import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.db.slick.{Repo, DbRepo, ExternalIdColumnFunction, ExternalIdColumnDbFunction, DataBaseComponent}
import com.keepit.common.db.slick.DBSession.{RSession, RWSession}
import com.keepit.common.db.slick.FortyTwoTypeMappers._
import com.keepit.common.cache.CacheStatistics
import com.keepit.common.logging.AccessLog
import org.joda.time.DateTime
import com.keepit.common.time._
import com.keepit.common.db.{ModelWithExternalId, Id, ExternalId}
import com.keepit.model.{User, NormalizedURI}
import MessagingTypeMappers._
import com.keepit.common.logging.Logging
import com.keepit.common.cache.{CacheSizeLimitExceededException, JsonCacheImpl, FortyTwoCachePlugin, Key}
import scala.concurrent.duration.Duration
import play.api.libs.json._
import play.api.libs.json.util._
import play.api.libs.functional.syntax._
import scala.slick.lifted.Query
import scala.slick.jdbc.{StaticQuery => Q}

case class Message(
    id: Option[Id[Message]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    externalId: ExternalId[Message] = ExternalId(),
    from: Option[Id[User]],
    thread: Id[MessageThread],
    threadExtId: ExternalId[MessageThread],
    messageText: String,
    auxData: Option[JsArray] = None,
    sentOnUrl: Option[String],
    sentOnUriId: Option[Id[NormalizedURI]]
  )
  extends ModelWithExternalId[Message] {

  def withId(id: Id[Message]): Message = this.copy(id = Some(id))
  def withUpdateTime(updateTime: DateTime) = this.copy(updatedAt=updateTime)
}

object Message {
  implicit val format = (
      (__ \ 'id).formatNullable(Id.format[Message]) and
      (__ \ 'createdAt).format[DateTime] and
      (__ \ 'updatedAt).format[DateTime] and
      (__ \ 'externalId).format(ExternalId.format[Message]) and
      (__ \ 'from).formatNullable(Id.format[User]) and
      (__ \ 'thread).format(Id.format[MessageThread]) and
      (__ \ 'threadExtId).format(ExternalId.format[MessageThread]) and
      (__ \ 'messageText).format[String] and
      (__ \ 'auxData).formatNullable[JsArray] and
      (__ \ 'sentOnUrl).formatNullable[String] and
      (__ \ 'sentOnUriId).formatNullable(Id.format[NormalizedURI])
    )(Message.apply, unlift(Message.unapply))
}

case class MessagesForThread(val thread:Id[MessageThread], val messages:Seq[Message])
{
  override def equals(other:Any):Boolean = other match {
    case mft: MessagesForThread => (thread.id == mft.thread.id && messages.size == mft.messages.size)
    case _ => false
  }
  override def hashCode = thread.id.hashCode
  override def toString = "[MessagesForThread(%s): %s]".format(thread, messages)
}

object MessagesForThread {

  implicit val messagesForThreadReads = (
    (__ \ 'thread_id).read(Id.format[MessageThread]) and
    (__ \ 'messages).read[Seq[Message]]
  )(MessagesForThread.apply _)

  implicit val messagesForThreadWrites = (
    (__ \ 'thread_id).write(Id.format[MessageThread]) and
    (__ \ 'messages).write(Writes.traversableWrites[Message])
  )(unlift(MessagesForThread.unapply))

}

case class MessagesForThreadIdKey(threadId:Id[MessageThread]) extends Key[MessagesForThread] {
  override val version = 2
  val namespace = "messages_for_thread_id"
  def toKey():String = threadId.id.toString
}

class MessagesForThreadIdCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[MessagesForThreadIdKey, MessagesForThread](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings:_*)

@ImplementedBy(classOf[MessageRepoImpl])
trait MessageRepo extends Repo[Message] with ExternalIdColumnFunction[Message] {

  def updateUriId(message: Message, uriId: Id[NormalizedURI])(implicit session: RWSession) : Unit

  def refreshCache(thread: Id[MessageThread])(implicit session: RSession): Unit

  def get(thread: Id[MessageThread], from: Int, to: Option[Int])(implicit session: RSession)  : Seq[Message]

  def getAfter(threadId: Id[MessageThread], after: DateTime)(implicit session: RSession): Seq[Message]

  def getFromIdToId(fromId: Id[Message], toId: Id[Message])(implicit session: RSession): Seq[Message]

  def updateUriIds(updates: Seq[(Id[NormalizedURI], Id[NormalizedURI])])(implicit session: RWSession) : Unit

  def getMaxId()(implicit session: RSession): Id[Message]

  def getMessageCounts(threadId: Id[MessageThread], afterOpt: Option[DateTime])(implicit session: RSession): (Int, Int)

}

@Singleton
class MessageRepoImpl @Inject() (
    val clock: Clock,
    val db: DataBaseComponent,
    val messagesForThreadIdCache: MessagesForThreadIdCache
  )
  extends DbRepo[Message] with MessageRepo with ExternalIdColumnDbFunction[Message] with Logging {

  import db.Driver.Implicit._

  override val table = new RepoTable[Message](db, "message") with ExternalIdColumn[Message] {
    def from = column[Id[User]]("sender_id", O.Nullable)
    def thread = column[Id[MessageThread]]("thread_id", O.NotNull)
    def threadExtId = column[ExternalId[MessageThread]]("thread_ext_id", O.NotNull)
    def messageText = column[String]("message_text", O.NotNull)
    def auxData = column[JsArray]("aux_data", O.Nullable)
    def sentOnUrl = column[String]("sent_on_url", O.Nullable)
    def sentOnUriId = column[Id[NormalizedURI]]("sent_on_uri_id", O.Nullable)
    def * = id.? ~ createdAt ~ updatedAt ~ externalId ~ from.? ~ thread ~ threadExtId ~ messageText ~ auxData.? ~ sentOnUrl.? ~ sentOnUriId.? <> (Message.apply _, Message.unapply _)
  }

  override def invalidateCache(message:Message)(implicit session:RSession):Message = {
    val key = MessagesForThreadIdKey(message.thread)
    messagesForThreadIdCache.remove(key)
    message
  }

  def updateUriId(message: Message, uriId: Id[NormalizedURI])(implicit session: RWSession) : Unit = {
    (for (row <- table if row.id===message.id) yield row.sentOnUriId).update(uriId)
  }

  def refreshCache(threadId: Id[MessageThread])(implicit session: RSession): Unit = {
    val key = MessagesForThreadIdKey(threadId)
    val messages = (for (row <- table if row.thread === threadId) yield row).sortBy(row => row.createdAt desc).list
    try {
      messagesForThreadIdCache.set(key, new MessagesForThread(threadId, messages))
    } catch {
      case c:CacheSizeLimitExceededException => // already reported in FortyTwoCache
    }
  }

  def get(threadId: Id[MessageThread], from: Int, to: Option[Int])(implicit session: RSession) : Seq[Message] = { //Note: Cache does not honor pagination!! -Stephen
    val key = MessagesForThreadIdKey(threadId)
    messagesForThreadIdCache.get(key) match {
      case Some(v) => {
        log.info(s"[get_thread] cache-hit: id=$threadId v=$v")
        v.messages
      }
      case None => {
        val query = (for (row <- table if row.thread === threadId) yield row).drop(from)
        val got = to match {
          case Some(upper) => query.take(upper-from).sortBy(row => row.createdAt desc).list
          case None => query.sortBy(row => row.createdAt desc).list
        }
        log.info(s"[get_thread] got thread messages for thread_id $threadId:\n$got")
        val mft = new MessagesForThread(threadId, got)
        try {
          log.info(s"[get_thread] cache-miss: set key=$key to messages=$mft")
          messagesForThreadIdCache.set(key, mft)
        } catch {
          case c:CacheSizeLimitExceededException => // already reported in FortyTwoCache
        }
        got
      }
    }
  }

  def getAfter(threadId: Id[MessageThread], after: DateTime)(implicit session: RSession): Seq[Message] = {
    (for (row <- table if row.thread===threadId && row.createdAt>after) yield row).sortBy(row => row.createdAt desc).list
  }

  def updateUriIds(updates: Seq[(Id[NormalizedURI], Id[NormalizedURI])])(implicit session: RWSession) : Unit = { //Note: There is potentially a race condition here with updateUriId. Need to investigate.
    updates.foreach{ case (oldId, newId) =>
      (for (row <- table if row.sentOnUriId===oldId) yield row.sentOnUriId).update(newId)
    }
  }

  def getFromIdToId(fromId: Id[Message], toId: Id[Message])(implicit session: RSession): Seq[Message] = {
    (for (row <- table if row.id>=fromId && row.id<=toId) yield row).list
  }

  def getMaxId()(implicit session: RSession): Id[Message] = {
    Query(table.map(_.id).max).first.getOrElse(Id[Message](0))
  }

  def getMessageCounts(threadId: Id[MessageThread], afterOpt: Option[DateTime])(implicit session: RSession): (Int, Int) = {
    afterOpt.map{ after =>
      Q.queryNA[(Int, Int)](s"select count(*), sum(created_at > '$after') from message where thread_id = $threadId").first
    } getOrElse {
      val n = Query(table.filter(row => row.thread === threadId).length).first
      (n, n)
    }
  }

}
