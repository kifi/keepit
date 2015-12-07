package com.keepit.eliza.model

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.db.slick.{ Repo, DbRepo, ExternalIdColumnFunction, ExternalIdColumnDbFunction, DataBaseComponent }
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.time._
import com.keepit.common.db.{ State, Id, ExternalId }
import com.keepit.model.{ Keep, User, NormalizedURI }
import org.joda.time.DateTime

@ImplementedBy(classOf[MessageThreadRepoImpl])
trait MessageThreadRepo extends Repo[MessageThread] with ExternalIdColumnFunction[MessageThread] {
  def getOrCreate(participants: Seq[Id[User]], nonUserParticipants: Seq[NonUserParticipant], url: String, uriId: Id[NormalizedURI], nUrl: String, pageTitleOpt: Option[String])(implicit session: RWSession): (MessageThread, Boolean)
  override def get(id: ExternalId[MessageThread])(implicit session: RSession): MessageThread
  override def get(id: Id[MessageThread])(implicit session: RSession): MessageThread
  def getActiveByIds(ids: Set[Id[MessageThread]])(implicit session: RSession): Map[Id[MessageThread], MessageThread]
  def updateNormalizedUris(updates: Seq[(Id[NormalizedURI], NormalizedURI)])(implicit session: RWSession): Unit

  def getByKeepId(keepId: Id[Keep])(implicit session: RSession): Option[MessageThread]
  def getByKeepIds(keepIds: Set[Id[Keep]])(implicit session: RSession): Map[Id[Keep], MessageThread]
}

@Singleton
class MessageThreadRepoImpl @Inject() (
  val clock: Clock,
  val db: DataBaseComponent,
  val threadExternalIdCache: MessageThreadExternalIdCache)
    extends DbRepo[MessageThread] with MessageThreadRepo with ExternalIdColumnDbFunction[MessageThread] with MessagingTypeMappers {

  import db.Driver.simple._

  private def messageThreadFromDbRow(
    id: Option[Id[MessageThread]],
    createdAt: DateTime,
    updatedAt: DateTime,
    state: State[MessageThread],
    externalId: ExternalId[MessageThread],
    uriId: Id[NormalizedURI],
    url: String,
    nUrl: String,
    participants: MessageThreadParticipants,
    participantsHash: Int, // explicitly ignored
    pageTitle: Option[String],
    keepId: Option[Id[Keep]]): MessageThread = {
    MessageThread(id, createdAt, updatedAt, state, externalId, uriId, url, nUrl, participants, pageTitle, keepId)
  }
  private def messageThreadToDbRow(mt: MessageThread) = {
    Some((
      mt.id, mt.createdAt, mt.updatedAt, mt.state, mt.externalId, mt.uriId, mt.url, mt.nUrl, mt.participants, mt.participantsHash, mt.pageTitle, mt.keepId
    ))
  }

  type RepoImpl = MessageThreadTable
  class MessageThreadTable(tag: Tag) extends RepoTable[MessageThread](db, tag, "message_thread") with ExternalIdColumn[MessageThread] {
    def uriId = column[Id[NormalizedURI]]("uri_id", O.NotNull)
    def url = column[String]("url", O.NotNull)
    def nUrl = column[String]("nUrl", O.NotNull)
    def participants = column[MessageThreadParticipants]("participants", O.NotNull)
    def participantsHash = column[Int]("participants_hash", O.NotNull)
    def pageTitle = column[Option[String]]("page_title", O.Nullable)
    def keepId = column[Option[Id[Keep]]]("keep_id", O.Nullable)
    def * = (id.?, createdAt, updatedAt, state, externalId, uriId, url, nUrl, participants, participantsHash, pageTitle, keepId) <> ((messageThreadFromDbRow _).tupled, messageThreadToDbRow _)
  }
  def table(tag: Tag) = new MessageThreadTable(tag)

  private def activeRows = rows.filter(_.state === MessageThreadStates.ACTIVE)

  override def invalidateCache(thread: MessageThread)(implicit session: RSession): Unit = {
    threadExternalIdCache.set(MessageThreadExternalIdKey(thread.externalId), thread)
  }

  override def deleteCache(thread: MessageThread)(implicit session: RSession): Unit = {
    threadExternalIdCache.remove(MessageThreadExternalIdKey(thread.externalId))
  }

  override def save(messageThread: MessageThread)(implicit session: RWSession) = super.save(messageThread.clean())

  def getOrCreate(userParticipants: Seq[Id[User]], nonUserParticipants: Seq[NonUserParticipant], url: String, uriId: Id[NormalizedURI], nUrl: String, pageTitleOpt: Option[String])(implicit session: RWSession): (MessageThread, Boolean) = {
    //Note (stephen): This has a race condition: When two threads that would normally be merged are created at the exact same time two different conversations will be the result
    val mtps = MessageThreadParticipants(userParticipants.toSet, nonUserParticipants.toSet)
    // TODO(ryan): why is this checking participantsHash == mtps.userHash? Don't we care about the non-users?
    val candidates: Seq[MessageThread] = activeRows.filter(row => row.participantsHash === mtps.userHash && row.uriId === uriId).list.filter { thread =>
      thread.participants == mtps
    }
    candidates.headOption match {
      case Some(cand) => (cand, false)
      case None =>
        val thread = MessageThread(
          uriId = uriId,
          url = url,
          nUrl = nUrl,
          pageTitle = pageTitleOpt,
          participants = mtps,
          keepId = None
        )
        (save(thread), true)
    }
  }

  override def get(id: ExternalId[MessageThread])(implicit session: RSession): MessageThread = {
    threadExternalIdCache.getOrElse(MessageThreadExternalIdKey(id))(super.get(id))
  }

  def getActiveByIds(ids: Set[Id[MessageThread]])(implicit session: RSession): Map[Id[MessageThread], MessageThread] = {
    activeRows.filter(_.id.inSet(ids)).list.map(x => x.id.get -> x).toMap
  }

  def getByKeepIds(keepIds: Set[Id[Keep]])(implicit session: RSession): Map[Id[Keep], MessageThread] = {
    activeRows.filter(_.keepId.inSet(keepIds)).list.groupBy(_.keepId).collect { case (Some(kid), Seq(thread)) => kid -> thread }
  }
  def getByKeepId(keepId: Id[Keep])(implicit session: RSession): Option[MessageThread] = {
    getByKeepIds(Set(keepId)).get(keepId)
  }

  def updateNormalizedUris(updates: Seq[(Id[NormalizedURI], NormalizedURI)])(implicit session: RWSession): Unit = {
    updates.foreach {
      case (oldId, newNUri) =>
        val updateIds = rows.filter(row => row.uriId === oldId).map(_.externalId).list //Note: race condition if there is an insert after this?
        rows.filter(row => row.uriId === oldId).map(row => (row.uriId, row.nUrl)).update((newNUri.id.get, newNUri.url))
        updateIds.foreach { extId =>
          threadExternalIdCache.remove(MessageThreadExternalIdKey(extId))
        }
    }
  }
}
