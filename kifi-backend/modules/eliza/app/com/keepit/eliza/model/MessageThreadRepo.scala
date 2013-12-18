package com.keepit.eliza.model

import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.db.slick.{Repo, DbRepo, ExternalIdColumnFunction, ExternalIdColumnDbFunction, DataBaseComponent}
import com.keepit.common.db.slick.FortyTwoTypeMappers._
import com.keepit.common.db.slick.{StringMapperDelegate}
import com.keepit.common.db.slick.DBSession.{RSession, RWSession}
import com.keepit.common.cache.CacheStatistics
import com.keepit.common.logging.AccessLog
import org.joda.time.DateTime
import com.keepit.common.time._
import com.keepit.common.db.{ModelWithExternalId, Id, ExternalId}
import com.keepit.model.{User, NormalizedURI}
import play.api.libs.json._
import play.api.libs.functional.syntax._
import com.keepit.common.time.{DateTimeJsonFormat}
import scala.slick.lifted.{BaseTypeMapper, TypeMapperDelegate}
import scala.slick.driver.{BasicProfile}
import scala.util.hashing.MurmurHash3
import MessagingTypeMappers._
import scala.concurrent.duration.Duration
import com.keepit.common.cache.{Key, JsonCacheImpl, FortyTwoCachePlugin}

@ImplementedBy(classOf[MessageThreadRepoImpl])
trait MessageThreadRepo extends Repo[MessageThread] with ExternalIdColumnFunction[MessageThread] {

  def getOrCreate(participants: Seq[Id[User]], nonUserParticipants: Seq[NonUserParticipant], urlOpt: Option[String], uriIdOpt: Option[Id[NormalizedURI]], nUriOpt: Option[String], pageTitleOpt: Option[String])(implicit session: RWSession) : (MessageThread, Boolean)

  override def get(id: ExternalId[MessageThread])(implicit session: RSession) : MessageThread

  override def get(id: Id[MessageThread])(implicit session: RSession) : MessageThread

  def updateNormalizedUris(updates: Seq[(Id[NormalizedURI], NormalizedURI)])(implicit session: RWSession) : Unit
}

@Singleton
class MessageThreadRepoImpl @Inject() (
    val clock: Clock,
    val db: DataBaseComponent,
    val threadExternalIdCache: MessageThreadExternalIdCache
  )
  extends DbRepo[MessageThread] with MessageThreadRepo with ExternalIdColumnDbFunction[MessageThread] {

  override val table = new RepoTable[MessageThread](db, "message_thread") with ExternalIdColumn[MessageThread] {
    def uriId = column[Id[NormalizedURI]]("uri_id", O.Nullable)
    def url = column[String]("url", O.Nullable)
    def nUrl = column[String]("nUrl", O.Nullable)
    def pageTitle = column[String]("page_title", O.Nullable)
    def participants = column[MessageThreadParticipants]("participants", O.Nullable)
    def participantsHash = column[Int]("participants_hash", O.Nullable)
    def replyable = column[Boolean]("replyable", O.NotNull)
    def * = id.? ~ createdAt ~ updatedAt ~ externalId ~ uriId.? ~ url.? ~ nUrl.? ~ pageTitle.? ~ participants.? ~ participantsHash.? ~ replyable <> (MessageThread.apply _, MessageThread.unapply _)
  }

  import db.Driver.Implicit._

  override def invalidateCache(thread: MessageThread)(implicit session: RSession): MessageThread = {
    threadExternalIdCache.set(MessageThreadExternalIdKey(thread.externalId), thread)
    thread
  }

  def getOrCreate(userParticipants: Seq[Id[User]], nonUserParticipants: Seq[NonUserParticipant], urlOpt: Option[String], uriIdOpt: Option[Id[NormalizedURI]], nUriOpt: Option[String], pageTitleOpt: Option[String])(implicit session: RWSession): (MessageThread, Boolean) = {
    //Note (stephen): This has a race condition: When two threads that would normally be merged are created at the exact same time two different conversations will be the result
    val mtps = MessageThreadParticipants(userParticipants.toSet, nonUserParticipants.toSet)
    val candidates : Seq[MessageThread]= (for (row <- table if row.participantsHash===mtps.userHash && row.uriId===uriIdOpt) yield row).list.filter { thread =>
      thread.uriId.isDefined &&
      thread.participants.isDefined &&
      thread.participants.get == mtps
    }
    if (candidates.length > 0) (candidates.head, false)
    else {
      val thread = MessageThread(
        id = None,
        uriId = uriIdOpt,
        url = urlOpt,
        nUrl = nUriOpt,
        pageTitle = pageTitleOpt,
        participants = Some(mtps),
        participantsHash = Some(mtps.userHash),
        replyable = true
      )
      (super.save(thread), true)
    }

  }

  override def get(id: ExternalId[MessageThread])(implicit session: RSession) : MessageThread = {
    threadExternalIdCache.getOrElse(MessageThreadExternalIdKey(id))(super.get(id))
  }

  override def get(id: Id[MessageThread])(implicit session: RSession) : MessageThread = {
    super.get(id)
  }

  def updateNormalizedUris(updates: Seq[(Id[NormalizedURI], NormalizedURI)])(implicit session: RWSession) : Unit = {
    updates.foreach{ case (oldId, newNUri) =>
      val updateIds = (for (row <- table if row.uriId===oldId) yield row.externalId).list //Note: race condition if there is an insert after this?
      (for (row <- table if row.uriId===oldId) yield (row.uriId ~ row.nUrl)).update((newNUri.id.get, newNUri.url))
      updateIds.foreach{ extId =>
        threadExternalIdCache.remove(MessageThreadExternalIdKey(extId))
      }
    }
  }

}
