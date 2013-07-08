package com.keepit.model

import com.google.inject.{Inject, ImplementedBy, Singleton}
import com.keepit.common.db.Id
import com.keepit.common.db.Model
import com.keepit.common.time._
import org.joda.time.DateTime
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.db.SequenceNumber
import com.keepit.common.db.slick.FortyTwoTypeMappers.SequenceNumberTypeMapper

/**
 * This table is intended to have only one row: it's used to track two sequence numbers.
 * TopicUpdater uses these numbers to keep topic tables updated.
 */
case class TopicSeqNumInfo(
  id: Option[Id[TopicSeqNumInfo]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  uriSeq: SequenceNumber = SequenceNumber.ZERO,
  bookmarkSeq: SequenceNumber = SequenceNumber.ZERO
) extends Model[TopicSeqNumInfo] {
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withId(id: Id[TopicSeqNumInfo]) = this.copy(id = Some(id))
}

trait TopicSeqNumInfoRepo extends Repo[TopicSeqNumInfo]{
  def getSeqNums(implicit session: RSession): Option[(SequenceNumber, SequenceNumber)]
  def updateUriSeq(uriSeqNum: SequenceNumber)(implicit session: RWSession): TopicSeqNumInfo
  def updateBookmarkSeq(bookmarkSeqNum: SequenceNumber)(implicit session: RWSession): TopicSeqNumInfo
}

abstract class TopicSeqNumInfoRepoBase(
  val tableName: String,
  val db: DataBaseComponent,
  val clock: Clock
) extends DbRepo[TopicSeqNumInfo] with TopicSeqNumInfoRepo {
  import FortyTwoTypeMappers._
  import db.Driver.Implicit._

  override val table = new RepoTable[TopicSeqNumInfo](db, tableName){
    def uriSeq = column[SequenceNumber]("uri_seq", O.NotNull)
    def bookmarkSeq = column[SequenceNumber]("bookmark_seq", O.NotNull)
    def * = id.? ~ createdAt ~ updatedAt ~ uriSeq ~ bookmarkSeq <> (TopicSeqNumInfo.apply _, TopicSeqNumInfo.unapply _)
  }

  def getSeqNums(implicit session: RSession): Option[(SequenceNumber, SequenceNumber)] = {
    (for( r <- table ) yield (r.uriSeq, r.bookmarkSeq)).firstOption
  }

  def updateUriSeq(uriSeqNum: SequenceNumber)(implicit session: RWSession): TopicSeqNumInfo = {
    (for (r <- table) yield r).firstOption match {
      case Some(seqInfo) => save(seqInfo.copy(uriSeq = uriSeqNum))
      case None => save(TopicSeqNumInfo(uriSeq = uriSeqNum))
    }
  }

  def updateBookmarkSeq(bookmarkSeqNum: SequenceNumber)(implicit session: RWSession): TopicSeqNumInfo = {
    (for (r <- table) yield r).firstOption match {
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

