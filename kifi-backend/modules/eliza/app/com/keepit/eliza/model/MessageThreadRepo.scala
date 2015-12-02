package com.keepit.eliza.model

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.db.slick.{ Repo, DbRepo, ExternalIdColumnFunction, ExternalIdColumnDbFunction, DataBaseComponent }
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.time._
import com.keepit.common.db.{ Id, ExternalId }
import com.keepit.model.{ Keep, User, NormalizedURI }

@ImplementedBy(classOf[MessageThreadRepoImpl])
trait MessageThreadRepo extends Repo[MessageThread] with ExternalIdColumnFunction[MessageThread] {
  def getOrCreate(participants: Seq[Id[User]], nonUserParticipants: Seq[NonUserParticipant], urlOpt: Option[String], uriIdOpt: Option[Id[NormalizedURI]], nUriOpt: Option[String], pageTitleOpt: Option[String])(implicit session: RWSession): (MessageThread, Boolean)
  override def get(id: ExternalId[MessageThread])(implicit session: RSession): MessageThread
  override def get(id: Id[MessageThread])(implicit session: RSession): MessageThread
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

  type RepoImpl = MessageThreadTable
  class MessageThreadTable(tag: Tag) extends RepoTable[MessageThread](db, tag, "message_thread") with ExternalIdColumn[MessageThread] {
    def uriId = column[Id[NormalizedURI]]("uri_id", O.Nullable)
    def url = column[String]("url", O.Nullable)
    def nUrl = column[String]("nUrl", O.Nullable)
    def pageTitle = column[String]("page_title", O.Nullable)
    def participants = column[MessageThreadParticipants]("participants", O.Nullable)
    def participantsHash = column[Int]("participants_hash", O.Nullable)
    def keepId = column[Option[Id[Keep]]]("keep_id", O.Nullable)
    def * = (id.?, createdAt, updatedAt, state, externalId, uriId.?, url.?, nUrl.?, pageTitle.?, participants.?, participantsHash.?, keepId) <> ((MessageThread.apply _).tupled, MessageThread.unapply _)
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

  def getOrCreate(userParticipants: Seq[Id[User]], nonUserParticipants: Seq[NonUserParticipant], urlOpt: Option[String], uriIdOpt: Option[Id[NormalizedURI]], nUriOpt: Option[String], pageTitleOpt: Option[String])(implicit session: RWSession): (MessageThread, Boolean) = {
    //Note (stephen): This has a race condition: When two threads that would normally be merged are created at the exact same time two different conversations will be the result
    val mtps = MessageThreadParticipants(userParticipants.toSet, nonUserParticipants.toSet)
    val candidates: Seq[MessageThread] = activeRows.filter(row => row.participantsHash === mtps.userHash && row.uriId === uriIdOpt).list.filter { thread =>
      thread.uriId.isDefined && thread.participants.isDefined && thread.participants.get == mtps
    }
    candidates.headOption match {
      case Some(cand) => (cand, false)
      case None =>
        val thread = MessageThread(
          id = None,
          uriId = uriIdOpt,
          url = urlOpt,
          nUrl = nUriOpt,
          pageTitle = pageTitleOpt,
          participants = Some(mtps),
          participantsHash = Some(mtps.userHash),
          keepId = None
        )
        (save(thread), true)
    }
  }

  override def get(id: ExternalId[MessageThread])(implicit session: RSession): MessageThread = {
    threadExternalIdCache.getOrElse(MessageThreadExternalIdKey(id))(super.get(id))
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
