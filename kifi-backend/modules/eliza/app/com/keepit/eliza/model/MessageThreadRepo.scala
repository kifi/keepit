package com.keepit.eliza.model

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.db.slick.{ Repo, DbRepo, ExternalIdColumnFunction, ExternalIdColumnDbFunction, DataBaseComponent }
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.time._
import com.keepit.common.db.{ State, Id, ExternalId }
import com.keepit.model.{ Keep, User, NormalizedURI }
import org.joda.time.DateTime

@ImplementedBy(classOf[MessageThreadRepoImpl])
trait MessageThreadRepo extends Repo[MessageThread] with ExternalIdColumnFunction[MessageThread] {
  def getOrCreate(starter: Id[User], participants: Seq[Id[User]], nonUserParticipants: Seq[NonUserParticipant], url: String, uriId: Id[NormalizedURI], nUrl: String, pageTitleOpt: Option[String])(implicit session: RWSession): (MessageThread, Boolean)
  override def get(id: ExternalId[MessageThread])(implicit session: RSession): MessageThread
  override def get(id: Id[MessageThread])(implicit session: RSession): MessageThread
  def getActiveByIds(ids: Set[Id[MessageThread]])(implicit session: RSession): Map[Id[MessageThread], MessageThread]
  def updateNormalizedUris(updates: Seq[(Id[NormalizedURI], NormalizedURI)])(implicit session: RWSession): Unit

  def getByKeepId(keepId: Id[Keep])(implicit session: RSession): Option[MessageThread]
  def getByKeepIds(keepIds: Set[Id[Keep]])(implicit session: RSession): Map[Id[Keep], MessageThread]
  def getThreadsWithoutKeepId(limit: Int)(implicit session: RSession): Seq[MessageThread]
}

@Singleton
class MessageThreadRepoImpl @Inject() (
  airbrake: AirbrakeNotifier,
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
    startedBy: Id[User],
    participants: MessageThreadParticipants,
    participantsHash: Int, // exists only in the db, will not be put into the model
    pageTitle: Option[String],
    keepId: Option[Id[Keep]]): MessageThread = {
    MessageThread(id, createdAt, updatedAt, state, externalId, uriId, url, nUrl, startedBy, participants, pageTitle, keepId)
  }
  private def messageThreadToDbRow(mt: MessageThread) = {
    Some((
      mt.id, mt.createdAt, mt.updatedAt, mt.state, mt.externalId, mt.uriId, mt.url, mt.nUrl, mt.startedBy, mt.participants, mt.participantsHash, mt.pageTitle, mt.keepId
    ))
  }

  type RepoImpl = MessageThreadTable
  class MessageThreadTable(tag: Tag) extends RepoTable[MessageThread](db, tag, "message_thread") with ExternalIdColumn[MessageThread] {
    def uriId = column[Id[NormalizedURI]]("uri_id", O.NotNull)
    def url = column[String]("url", O.NotNull)
    def nUrl = column[String]("nUrl", O.NotNull)
    def startedBy = column[Id[User]]("started_by", O.NotNull)
    def participants = column[MessageThreadParticipants]("participants", O.NotNull)
    def participantsHash = column[Int]("participants_hash", O.NotNull)
    def pageTitle = column[Option[String]]("page_title", O.Nullable)
    def keepId = column[Option[Id[Keep]]]("keep_id", O.Nullable)
    def * = (id.?, createdAt, updatedAt, state, externalId, uriId, url, nUrl, startedBy, participants, participantsHash, pageTitle, keepId) <> ((messageThreadFromDbRow _).tupled, messageThreadToDbRow _)
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

  def getOrCreate(starter: Id[User], userParticipants: Seq[Id[User]], nonUserParticipants: Seq[NonUserParticipant], url: String, uriId: Id[NormalizedURI], nUrl: String, pageTitleOpt: Option[String])(implicit session: RWSession): (MessageThread, Boolean) = {
    airbrake.verify(userParticipants.contains(starter), s"Called getOrCreate with starter $starter and users $userParticipants")
    val mtps = MessageThreadParticipants(userParticipants.toSet, nonUserParticipants.toSet)

    // This will find any thread with the same participants set (on the correct page). It does not check who owns the thread
    // Passing in a starter is only to indicate who will own the thread if a NEW one must be created
    val candidates: Seq[MessageThread] = activeRows.filter(row => row.participantsHash === mtps.hash && row.uriId === uriId).list.filter { thread =>
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
          startedBy = starter,
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
  def getThreadsWithoutKeepId(limit: Int)(implicit session: RSession): Seq[MessageThread] = {
    activeRows.filter(row => row.keepId.isEmpty && row.startedBy === Id[User](84792)).take(limit).list
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
