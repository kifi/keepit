package com.keepit.model

import com.google.inject.{Inject, ImplementedBy, Singleton}
import com.keepit.common.db.{Id, Model, SequenceNumber}
import com.keepit.common.time._
import org.joda.time.DateTime
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._

/**
 * This table is intended to have only one row: it's used to track two sequence numbers.
 * TopicUpdater uses these numbers to keep topic tables updated.
 */
case class TopicSeqNumInfo(
  id: Option[Id[TopicSeqNumInfo]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  uriSeq: SequenceNumber[NormalizedURI] = SequenceNumber.ZERO,
  bookmarkSeq: SequenceNumber[Bookmark] = SequenceNumber.ZERO
) extends Model[TopicSeqNumInfo] {
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withId(id: Id[TopicSeqNumInfo]) = this.copy(id = Some(id))
}

trait TopicSeqNumInfoRepo extends Repo[TopicSeqNumInfo]{
  def getSeqNums(implicit session: RSession): Option[(SequenceNumber[NormalizedURI], SequenceNumber[Bookmark])]
  def updateUriSeq(uriSeqNum: SequenceNumber[NormalizedURI])(implicit session: RWSession): TopicSeqNumInfo
  def updateBookmarkSeq(bookmarkSeqNum: SequenceNumber[Bookmark])(implicit session: RWSession): TopicSeqNumInfo
}

abstract class TopicSeqNumInfoRepoBase(
  val tableName: String,
  val db: DataBaseComponent,
  val clock: Clock
) extends DbRepo[TopicSeqNumInfo] with TopicSeqNumInfoRepo {

  import db.Driver.simple._

  type RepoImpl = TopicSeqNumInfoTable
  class TopicSeqNumInfoTable(tag: Tag) extends RepoTable[TopicSeqNumInfo](db, tag, tableName) {
    def uriSeq = column[SequenceNumber[NormalizedURI]]("uri_seq", O.NotNull)
    def bookmarkSeq = column[SequenceNumber[Bookmark]]("bookmark_seq", O.NotNull)
    def * = (id.?, createdAt, updatedAt, uriSeq, bookmarkSeq) <> ((TopicSeqNumInfo.apply _).tupled, TopicSeqNumInfo.unapply _)
  }

  def table(tag: Tag) = new TopicSeqNumInfoTable(tag)
  initTable()

  override def deleteCache(model: TopicSeqNumInfo)(implicit session: RSession): Unit = {}
  override def invalidateCache(model: TopicSeqNumInfo)(implicit session: RSession): Unit = {}

  def getSeqNums(implicit session: RSession): Option[(SequenceNumber[NormalizedURI], SequenceNumber[Bookmark])] = {
    (for( r <- rows ) yield (r.uriSeq, r.bookmarkSeq)).firstOption
  }

  def updateUriSeq(uriSeqNum: SequenceNumber[NormalizedURI])(implicit session: RWSession): TopicSeqNumInfo = {
    (for (r <- rows) yield r).firstOption match {
      case Some(seqInfo) => save(seqInfo.copy(uriSeq = uriSeqNum))
      case None => save(TopicSeqNumInfo(uriSeq = uriSeqNum))
    }
  }

  def updateBookmarkSeq(bookmarkSeqNum: SequenceNumber[Bookmark])(implicit session: RWSession): TopicSeqNumInfo = {
    (for (r <- rows) yield r).firstOption match {
      case Some(seqInfo) => save(seqInfo.copy(bookmarkSeq = bookmarkSeqNum))
      case None => save(TopicSeqNumInfo(bookmarkSeq = bookmarkSeqNum))
    }
  }

}

@Singleton
class TopicSeqNumInfoRepoA @Inject()(
  db: DataBaseComponent,
  clock: Clock
) extends TopicSeqNumInfoRepoBase("topic_seq_num_info", db, clock)

@Singleton
class TopicSeqNumInfoRepoB @Inject()(
  db: DataBaseComponent,
  clock: Clock
) extends TopicSeqNumInfoRepoBase("topic_seq_num_info_b", db, clock)

