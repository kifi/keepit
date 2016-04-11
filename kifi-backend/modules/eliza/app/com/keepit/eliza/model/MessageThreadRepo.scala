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
trait MessageThreadRepo extends Repo[MessageThread] {
  def intern(model: MessageThread)(implicit session: RWSession): MessageThread
  def getByUriAndParticipants(uriId: Id[NormalizedURI], participants: MessageThreadParticipants)(implicit session: RSession): Seq[MessageThread]
  override def get(id: Id[MessageThread])(implicit session: RSession): MessageThread
  def getByIds(ids: Set[Id[MessageThread]])(implicit session: RSession): Map[Id[MessageThread], MessageThread]
  def getActiveByIds(ids: Set[Id[MessageThread]])(implicit session: RSession): Map[Id[MessageThread], MessageThread]
  def updateNormalizedUris(updates: Seq[(Id[NormalizedURI], NormalizedURI)])(implicit session: RWSession): Unit

  def getByKeepId(keepId: Id[Keep])(implicit session: RSession): Option[MessageThread]
  def getByKeepIds(keepIds: Set[Id[Keep]], excludeState: Option[State[MessageThread]] = Some(MessageThreadStates.INACTIVE))(implicit session: RSession): Map[Id[Keep], MessageThread]
  def deactivate(model: MessageThread)(implicit session: RWSession): Unit
}

@Singleton
class MessageThreadRepoImpl @Inject() (
  airbrake: AirbrakeNotifier,
  val clock: Clock,
  val db: DataBaseComponent,
  val threadKeepIdCache: MessageThreadKeepIdCache)
    extends DbRepo[MessageThread] with MessageThreadRepo with MessagingTypeMappers {

  import db.Driver.simple._

  private def messageThreadFromDbRow(
    id: Option[Id[MessageThread]],
    createdAt: DateTime,
    updatedAt: DateTime,
    state: State[MessageThread],
    uriId: Id[NormalizedURI],
    url: String,
    nUrl: String,
    startedBy: Id[User],
    participants: MessageThreadParticipants,
    participantsHash: Int, // exists only in the db, will not be put into the model
    pageTitle: Option[String],
    keepId: Id[Keep],
    numMessages: Option[Int]): MessageThread = {
    MessageThread(id, createdAt, updatedAt, state, uriId, url, nUrl, startedBy, participants, pageTitle, keepId, numMessages getOrElse 0)
  }
  private def messageThreadToDbRow(mt: MessageThread) = {
    Some((
      mt.id, mt.createdAt, mt.updatedAt, mt.state, mt.uriId, mt.url, mt.nUrl, mt.startedBy, mt.participants, mt.participantsHash, mt.pageTitle, mt.keepId, Option(mt.numMessages)
    ))
  }

  type RepoImpl = MessageThreadTable
  class MessageThreadTable(tag: Tag) extends RepoTable[MessageThread](db, tag, "message_thread") {
    def uriId = column[Id[NormalizedURI]]("uri_id", O.NotNull)
    def url = column[String]("url", O.NotNull)
    def nUrl = column[String]("nUrl", O.NotNull)
    def startedBy = column[Id[User]]("started_by", O.NotNull)
    def participants = column[MessageThreadParticipants]("participants", O.NotNull)
    def participantsHash = column[Int]("participants_hash", O.NotNull)
    def pageTitle = column[Option[String]]("page_title", O.Nullable)
    def keepId = column[Id[Keep]]("keep_id", O.NotNull)
    def numMessages = column[Option[Int]]("num_messages", O.Nullable)
    def * = (id.?, createdAt, updatedAt, state, uriId, url, nUrl, startedBy, participants, participantsHash, pageTitle, keepId, numMessages) <> ((messageThreadFromDbRow _).tupled, messageThreadToDbRow _)
  }
  def table(tag: Tag) = new MessageThreadTable(tag)

  private def activeRows = rows.filter(_.state === MessageThreadStates.ACTIVE)
  private def deadRows = rows.filter(_.state === MessageThreadStates.INACTIVE)

  override def invalidateCache(thread: MessageThread)(implicit session: RSession): Unit = {
    threadKeepIdCache.set(MessageThreadKeepIdKey(thread.keepId), thread)
  }

  override def deleteCache(thread: MessageThread)(implicit session: RSession): Unit = {
    threadKeepIdCache.remove(MessageThreadKeepIdKey(thread.keepId))
  }

  def intern(model: MessageThread)(implicit session: RWSession): MessageThread = {
    // There is a unique index on keepId, so if there is a dead model we will just snake it's spot
    // If there is a live model with that keepId (but a different model id), we're kind of screwed.
    save(model.clean().copy(
      id = deadRows.filter(_.keepId === model.keepId).map(_.id).firstOption
    ))
  }

  def getByUriAndParticipants(uriId: Id[NormalizedURI], participants: MessageThreadParticipants)(implicit session: RSession): Seq[MessageThread] = {
    activeRows.filter(row => row.participantsHash === participants.hash && row.uriId === uriId).list.filter { thread =>
      thread.participants == participants
    }
  }

  def getByIds(ids: Set[Id[MessageThread]])(implicit session: RSession): Map[Id[MessageThread], MessageThread] = {
    rows.filter(_.id.inSet(ids)).list.map(x => x.id.get -> x).toMap
  }

  def getActiveByIds(ids: Set[Id[MessageThread]])(implicit session: RSession): Map[Id[MessageThread], MessageThread] = {
    activeRows.filter(_.id.inSet(ids)).list.map(x => x.id.get -> x).toMap
  }

  private def getByKeepIdsNoCache(keepIds: Set[Id[Keep]], excludeState: Option[State[MessageThread]])(implicit session: RSession): Map[Id[Keep], MessageThread] = {
    rows.filter(r => r.keepId.inSet(keepIds) && r.state =!= excludeState.orNull).list.groupBy(_.keepId).collect { case (kid, Seq(thread)) => kid -> thread }
  }

  def getByKeepIds(keepIds: Set[Id[Keep]], excludeState: Option[State[MessageThread]] = Some(MessageThreadStates.INACTIVE))(implicit session: RSession): Map[Id[Keep], MessageThread] = {
    threadKeepIdCache.bulkGetOrElse(keepIds.map(MessageThreadKeepIdKey(_))) { missingKeys =>
      getByKeepIdsNoCache(missingKeys.map(_.keepId), excludeState).map { case (keepId, thread) => MessageThreadKeepIdKey(keepId) -> thread }
    }.map { case (MessageThreadKeepIdKey(keepId), thread) => keepId -> thread }
  }

  def getByKeepId(keepId: Id[Keep])(implicit session: RSession): Option[MessageThread] = {
    getByKeepIds(Set(keepId)).get(keepId)
  }

  def updateNormalizedUris(updates: Seq[(Id[NormalizedURI], NormalizedURI)])(implicit session: RWSession): Unit = {
    updates.foreach {
      case (oldId, newNUri) =>
        val updateIds = rows.filter(row => row.uriId === oldId).map(_.keepId).list //Note: race condition if there is an insert after this?
        rows.filter(row => row.uriId === oldId).map(row => (row.uriId, row.nUrl)).update((newNUri.id.get, newNUri.url))
        updateIds.foreach { keepId =>
          threadKeepIdCache.remove(MessageThreadKeepIdKey(keepId))
        }
    }
  }
  def deactivate(model: MessageThread)(implicit session: RWSession): Unit = {
    save(model.sanitizeForDelete)
  }
}
