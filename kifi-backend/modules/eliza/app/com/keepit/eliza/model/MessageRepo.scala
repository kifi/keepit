package com.keepit.eliza.model

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.actor.ActorInstance
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.plugin.{ SequencingActor, SchedulingProperties, SequencingPlugin }
import com.keepit.discussion.MessageSource
import org.joda.time.DateTime
import com.keepit.common.time._
import com.keepit.common.db.{ SequenceNumber, DbSequenceAssigner, Id }
import com.keepit.model.{ Keep, SortDirection, User, NormalizedURI }
import com.keepit.common.logging.Logging
import com.keepit.common.cache.CacheSizeLimitExceededException
import play.api.libs.json.{ Json, JsValue }
import scala.slick.jdbc.StaticQuery

case class MessageCount(total: Int, unread: Int)

@ImplementedBy(classOf[MessageRepoImpl])
trait MessageRepo extends Repo[ElizaMessage] with SeqNumberFunction[ElizaMessage] {
  def updateUriId(message: ElizaMessage, uriId: Id[NormalizedURI])(implicit session: RWSession): Unit
  def refreshCache(keepId: Id[Keep])(implicit session: RSession): Unit
  def get(keepId: Id[Keep], from: Int)(implicit session: RSession): Seq[ElizaMessage]
  def getAll(ids: Seq[Id[ElizaMessage]])(implicit session: RSession): Map[Id[ElizaMessage], ElizaMessage]
  def getAfter(keepId: Id[Keep], after: DateTime)(implicit session: RSession): Seq[ElizaMessage]
  def getFromIdToId(fromId: Id[ElizaMessage], toId: Id[ElizaMessage])(implicit session: RSession): Seq[ElizaMessage]
  def updateUriIds(updates: Seq[(Id[NormalizedURI], Id[NormalizedURI])])(implicit session: RWSession): Unit
  def getMaxId()(implicit session: RSession): Id[ElizaMessage]
  def getMessageCounts(keepId: Id[Keep], afterOpt: Option[DateTime])(implicit session: RSession): MessageCount
  def getLatest(keepId: Id[Keep])(implicit session: RSession): Option[ElizaMessage]
  def deactivate(message: ElizaMessage)(implicit session: RWSession): Unit
  def deactivate(messageId: Id[ElizaMessage])(implicit session: RWSession): Unit

  // PSA: please just use this method going forward, it has the cleanest API
  def countByKeep(keepId: Id[Keep], fromId: Option[Id[ElizaMessage]], dir: SortDirection = SortDirection.DESCENDING)(implicit session: RSession): MessageCount
  def countByKeeps(keepIds: Set[Id[Keep]])(implicit session: RSession): Map[Id[Keep], Int]
  def getNumCommentsByKeep(keepIds: Set[Id[Keep]])(implicit session: RSession): Map[Id[Keep], Int]
  def getByKeep(keepId: Id[Keep], fromId: Option[Id[ElizaMessage]], dir: SortDirection = SortDirection.DESCENDING, limit: Int)(implicit session: RSession): Seq[ElizaMessage]
  def getByKeepBefore(keepId: Id[Keep], fromOpt: Option[DateTime], dir: SortDirection = SortDirection.DESCENDING, limit: Int)(implicit session: RSession): Seq[ElizaMessage]
  def getAllByKeep(keepId: Id[Keep])(implicit session: RSession): Seq[ElizaMessage]

  def getCommentIndex(message: ElizaMessage)(implicit session: RSession): Option[Int]
  def getForKeepsBySequenceNumber(keepIds: Set[Id[Keep]], seq: SequenceNumber[ElizaMessage])(implicit session: RSession): Seq[ElizaMessage]
  def getRecentByKeeps(keepIds: Set[Id[Keep]], limitPerKeep: Int)(implicit session: RSession): Map[Id[Keep], Seq[Id[ElizaMessage]]]

  //migration
  def pageSystemMessages(fromId: Id[ElizaMessage], pageSize: Int)(implicit session: RSession): Seq[ElizaMessage]
}

@Singleton
class MessageRepoImpl @Inject() (
  val clock: Clock,
  val db: DataBaseComponent,
  val messagesByKeepIdCache: MessagesByKeepIdCache)
    extends DbRepo[ElizaMessage] with MessageRepo with SeqNumberDbFunction[ElizaMessage] with Logging with MessagingTypeMappers {
  import DBSession._
  import db.Driver.simple._

  implicit val systemMessageDataTypeMapper = MappedColumnType.base[SystemMessageData, String](
    { obj => Json.stringify(Json.toJson(obj)(SystemMessageData.internalFormat)) },
    { str => Json.parse(str).as[SystemMessageData](SystemMessageData.internalFormat) }
  )

  type RepoImpl = MessageTable
  class MessageTable(tag: Tag) extends RepoTable[ElizaMessage](db, tag, "message") with SeqNumberColumn[ElizaMessage] {
    def keepId = column[Id[Keep]]("keep_id", O.NotNull)
    def commentIndexOnKeep = column[Option[Int]]("comment_index_on_keep", O.Nullable)
    def from = column[Option[Id[User]]]("sender_id", O.Nullable)
    def messageText = column[String]("message_text", O.NotNull)
    def source = column[Option[MessageSource]]("source", O.Nullable)
    def auxData = column[Option[SystemMessageData]]("aux_data", O.Nullable)
    def sentOnUrl = column[Option[String]]("sent_on_url", O.Nullable)
    def sentOnUriId = column[Option[Id[NormalizedURI]]]("sent_on_uri_id", O.Nullable)
    def nonUserSender = column[Option[JsValue]]("non_user_sender", O.Nullable)

    def fromHuman: Column[Boolean] = from.isDefined || nonUserSender.isDefined

    def * = (id.?, createdAt, updatedAt, state, seq, keepId, commentIndexOnKeep, from, messageText, source, auxData, sentOnUrl, sentOnUriId, nonUserSender) <> ((ElizaMessage.fromDbRow _).tupled, ElizaMessage.toDbRow)
  }
  def table(tag: Tag) = new MessageTable(tag)

  private def activeRows = rows.filter(_.state === ElizaMessageStates.ACTIVE)

  override def save(message: ElizaMessage)(implicit session: RWSession): ElizaMessage = {
    super.save(message.copy(seq = sequence.incrementAndGet()))
  }

  override def invalidateCache(message: ElizaMessage)(implicit session: RSession): Unit = deleteCache(message)

  override def deleteCache(message: ElizaMessage)(implicit session: RSession): Unit = {
    messagesByKeepIdCache.remove(MessagesKeepIdKey(message.keepId))
  }

  def updateUriId(message: ElizaMessage, uriId: Id[NormalizedURI])(implicit session: RWSession): Unit = {
    rows.filter(row => row.id === message.id).map(_.sentOnUriId).update(Some(uriId))
    invalidateCache(message)
  }

  def refreshCache(keepId: Id[Keep])(implicit session: RSession): Unit = {
    val messages = activeRows.filter(row => row.keepId === keepId).sortBy(row => row.createdAt desc).list
    try {
      messagesByKeepIdCache.set(MessagesKeepIdKey(keepId), messages)
    } catch {
      case c: CacheSizeLimitExceededException => // already reported in FortyTwoCache
    }
  }

  def get(keepId: Id[Keep], from: Int)(implicit session: RSession): Seq[ElizaMessage] = { //Note: Cache does not honor pagination!! -Stephen
    val key = MessagesKeepIdKey(keepId)
    messagesByKeepIdCache.get(key) match {
      case Some(messages) => messages
      case None =>
        val query = activeRows.filter(row => row.keepId === keepId).drop(from)
        val messages = query.sortBy(row => row.createdAt desc).list
        try {
          messagesByKeepIdCache.set(key, messages)
        } catch {
          case c: CacheSizeLimitExceededException => // already reported in FortyTwoCache
        }
        messages
    }
  }

  def getAll(ids: Seq[Id[ElizaMessage]])(implicit session: RSession): Map[Id[ElizaMessage], ElizaMessage] = rows.filter(_.id.inSet(ids)).map(r => r.id -> r).list.toMap

  def getAfter(keepId: Id[Keep], after: DateTime)(implicit session: RSession): Seq[ElizaMessage] = {
    activeRows.filter(row => row.keepId === keepId && row.createdAt > after).sortBy(row => row.createdAt desc).list
  }

  def updateUriIds(updates: Seq[(Id[NormalizedURI], Id[NormalizedURI])])(implicit session: RWSession): Unit = { //Note: There is potentially a race condition here with updateUriId. Need to investigate.
    updates.foreach {
      case (oldId, newId) =>
        rows.filter(row => row.sentOnUriId === oldId).map(_.sentOnUriId).update(Some(newId))
    }
  }

  def getFromIdToId(fromId: Id[ElizaMessage], toId: Id[ElizaMessage])(implicit session: RSession): Seq[ElizaMessage] = {
    activeRows.filter(row => row.id >= fromId && row.id <= toId).list
  }

  def getMaxId()(implicit session: RSession): Id[ElizaMessage] = {
    activeRows.map(_.id).max.run.getOrElse(Id(0))
  }

  def getMessageCounts(keepId: Id[Keep], afterOpt: Option[DateTime])(implicit session: RSession): MessageCount = {
    val (total, unread) = afterOpt.map { after =>
      StaticQuery.queryNA[(Int, Int)](s"select count(*), sum(created_at > '$after') from message where keep_id = $keepId").first
    } getOrElse {
      val n = activeRows.filter(row => row.keepId === keepId).length.run
      (n, n)
    }
    MessageCount(total, unread)
  }

  def getNumCommentsByKeep(keepIds: Set[Id[Keep]])(implicit session: RSession): Map[Id[Keep], Int] = {
    activeRows.filter(row => row.keepId.inSet(keepIds) && row.fromHuman).groupBy(_.keepId).map { case (keepId, messages) => (keepId, messages.length) }.list.toMap
  }

  def getLatest(keepId: Id[Keep])(implicit session: RSession): Option[ElizaMessage] = {
    activeRows.filter(row => row.keepId === keepId).sortBy(row => (row.createdAt desc, row.id desc)).firstOption
  }

  private def getByThreadHelper(keepId: Id[Keep], fromId: Option[Id[ElizaMessage]], dir: SortDirection)(implicit session: RSession) = {
    val threadMessages = activeRows.filter(row => row.keepId === keepId)
    fromId match {
      case None => threadMessages
      case Some(fid) =>
        // The message for fid may have been deleted, so search for it in all rows
        val fromTime = rows.filter(_.id === fid).map(_.createdAt).first
        dir match {
          case SortDirection.DESCENDING => threadMessages.filter(r => r.createdAt < fromTime || (r.createdAt === fromTime && r.id < fid))
          case SortDirection.ASCENDING => threadMessages.filter(r => r.createdAt > fromTime || (r.createdAt === fromTime && r.id > fid))
        }
    }
  }
  def countByKeep(keepId: Id[Keep], fromId: Option[Id[ElizaMessage]], dir: SortDirection = SortDirection.DESCENDING)(implicit session: RSession): MessageCount = {
    val (total, unread) = (getByThreadHelper(keepId, None, dir).length, getByThreadHelper(keepId, fromId, dir).length).run
    MessageCount(total, unread)
  }

  def countByKeeps(keepIds: Set[Id[Keep]])(implicit session: RSession): Map[Id[Keep], Int] = {
    activeRows.filter(_.keepId.inSet(keepIds)).groupBy(_.keepId).map {
      case (kId, msgs) => kId -> msgs.length
    }.list.toMap
  }
  def getByKeep(keepId: Id[Keep], fromId: Option[Id[ElizaMessage]], dir: SortDirection = SortDirection.DESCENDING, limit: Int)(implicit session: RSession): Seq[ElizaMessage] = {
    if (limit <= 0) Seq.empty
    else {
      val threads = getByThreadHelper(keepId, fromId, dir)
      val sortedThreads = dir match {
        case SortDirection.ASCENDING => threads.sortBy(r => (r.createdAt asc, r.id asc))
        case SortDirection.DESCENDING => threads.sortBy(r => (r.createdAt desc, r.id desc))
      }
      sortedThreads.take(limit).list
    }
  }
  def getByKeepBefore(keepId: Id[Keep], fromOpt: Option[DateTime], dir: SortDirection = SortDirection.DESCENDING, limit: Int)(implicit session: RSession): Seq[ElizaMessage] = {
    if (limit <= 0) Seq.empty
    else {
      val threadMessages = activeRows.filter(_.keepId === keepId)
      val filteredMessages = fromOpt match {
        case None => threadMessages
        case Some(from) if dir == SortDirection.DESCENDING => threadMessages.filter(_.createdAt < from)
        case Some(from) if dir == SortDirection.ASCENDING => threadMessages.filter(_.createdAt > from)
      }
      val sortedMessages = dir match {
        case SortDirection.DESCENDING => filteredMessages.sortBy(r => (r.createdAt desc, r.id desc))
        case SortDirection.ASCENDING => filteredMessages.sortBy(r => (r.createdAt asc, r.id asc))
      }
      sortedMessages.take(limit).list
    }
  }

  def getAllByKeep(keepId: Id[Keep])(implicit session: RSession): Seq[ElizaMessage] = {
    activeRows.filter(_.keepId === keepId).list
  }

  def getCommentIndex(message: ElizaMessage)(implicit session: RSession): Option[Int] = {
    if (!message.isActive || message.from.isSystem) None
    else Some(activeRows.filter(
      m => m.keepId === message.keepId && m.fromHuman &&
        (m.createdAt < message.createdAt || m.createdAt === message.createdAt && m.id < message.id.get)
    ).length.run)
  }

  def getForKeepsBySequenceNumber(keepIds: Set[Id[Keep]], seq: SequenceNumber[ElizaMessage])(implicit session: RSession): Seq[ElizaMessage] = {
    rows.filter(msg => msg.keepId.inSet(keepIds) && msg.seq > seq).sortBy(_.seq).list
  }

  def pageSystemMessages(fromId: Id[ElizaMessage], pageSize: Int)(implicit session: RSession): Seq[ElizaMessage] = {
    activeRows.filter(msg => msg.auxData.isDefined && msg.id > fromId).sortBy(_.id asc).take(pageSize).list
  }

  /**
   * Grabs the `limitPerKeep` most recent messages for each keep in `keepIds`
   * This is a non-trivial thing to do in a SQL database, so this query abuses user-variables.
   * The sad part is that this is both complicated AND sensitive to the exactly db engine being used
   * The happy part is that it's actually quite efficient compared to `keepIds.size` independent queries
   * Use it if you desire efficiency at the cost of happiness
   */
  def getRecentByKeeps(keepIds: Set[Id[Keep]], limitPerKeep: Int)(implicit session: RSession): Map[Id[Keep], Seq[Id[ElizaMessage]]] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    if (keepIds.isEmpty) Map.empty
    else {
      val keepIdSetStr = keepIds.mkString("(", ",", ")")
      val updateRankStatement = db match {
        case _: com.keepit.common.db.slick.H2 => "@idx := CASEWHEN(@curKeep = keep_id, @idx + 1, 1) AS rank"
        case _: com.keepit.common.db.slick.MySQL => "@idx := IF(@curKeep = keep_id, @idx + 1, 1) AS rank"
        case _ => throw new RuntimeException("unexpected db type, not one of {H2, MySQL}")
      }

      StaticQuery.updateNA { s""" SET @idx = 0 """ }.execute
      StaticQuery.updateNA { s""" SET @curKeep = 0 """ }.execute
      StaticQuery.queryNA[(Id[Keep], Id[ElizaMessage])] {
        s"""
        SELECT keep_id, id FROM (
          SELECT keep_id, id,
              $updateRankStatement,
              @curKeep := keep_id AS dummy
          FROM (SELECT * FROM message WHERE keep_id IN $keepIdSetStr AND state = 'active' ORDER BY keep_id, created_at DESC, id DESC) x
        ) sub WHERE sub.rank <= $limitPerKeep
        """
      }.list.groupBy(_._1).mapValues(_.map(_._2))
    }
  }

  def deactivate(message: ElizaMessage)(implicit session: RWSession): Unit = save(message.sanitizeForDelete)
  def deactivate(messageId: Id[ElizaMessage])(implicit session: RWSession): Unit = save(get(messageId).sanitizeForDelete)
}

trait MessageSequencingPlugin extends SequencingPlugin

class MessageSequencingPluginImpl @Inject() (override val actor: ActorInstance[MessageSeqActor], override val scheduling: SchedulingProperties)
  extends MessageSequencingPlugin

@Singleton
class MessageSeqAssigner @Inject() (db: Database, repo: MessageRepo, airbrake: AirbrakeNotifier)
  extends DbSequenceAssigner[ElizaMessage](db, repo, airbrake)

class MessageSeqActor @Inject() (assigner: MessageSeqAssigner, airbrake: AirbrakeNotifier)
  extends SequencingActor(assigner, airbrake)

