package com.keepit.model
import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.db.slick._
import com.keepit.common.db.Id
import com.keepit.common.time.Clock
import com.keepit.common.db.SequenceNumber
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db.State

@ImplementedBy(classOf[RenormalizedURLRepoImpl])
trait RenormalizedURLRepo extends Repo[RenormalizedURL] with SeqNumberFunction[RenormalizedURL] {
  def getChangesSince(num: SequenceNumber[RenormalizedURL], limit: Int, state: State[RenormalizedURL] = RenormalizedURLStates.APPLIED)(implicit session: RSession): Seq[RenormalizedURL]
  def getChangesBetween(lowSeq: SequenceNumber[RenormalizedURL], highSeq: SequenceNumber[RenormalizedURL], state: State[RenormalizedURL] = RenormalizedURLStates.APPLIED)(implicit session: RSession): Seq[RenormalizedURL] // (low, high]
  def saveWithoutIncreSeqnum(model: RenormalizedURL)(implicit session: RWSession): RenormalizedURL // useful when we track processed merge requests
  def pageView(pageNum: Int, pageSize: Int)(implicit session: RSession): Seq[RenormalizedURL]
  def activeCount()(implicit session: RSession): Int
}

@Singleton
class RenormalizedURLRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock,
    val urlRepo: URLRepoImpl) extends DbRepo[RenormalizedURL] with RenormalizedURLRepo with SeqNumberDbFunction[RenormalizedURL] {
  import db.Driver.simple._

  type RepoImpl = RenormalizedURLTable

  class RenormalizedURLTable(tag: Tag) extends RepoTable[RenormalizedURL](db, tag, "renormalized_url") with SeqNumberColumn[RenormalizedURL] {
    def urlId = column[Id[URL]]("url_id", O.NotNull)
    def newUriId = column[Id[NormalizedURI]]("new_uri_id", O.NotNull)
    def oldUriId = column[Id[NormalizedURI]]("old_uri_id", O.NotNull)
    def * = (id.?, createdAt, updatedAt, urlId, oldUriId, newUriId, state, seq) <> ((RenormalizedURL.apply _).tupled, RenormalizedURL.unapply _)
  }

  def table(tag: Tag) = new RenormalizedURLTable(tag)
  initTable()

  override def save(model: RenormalizedURL)(implicit session: RWSession) = {
    val newModel = model.copy(seq = sequence.incrementAndGet())
    super.save(newModel)
  }

  override def deleteCache(model: RenormalizedURL)(implicit session: RSession): Unit = {}
  override def invalidateCache(model: RenormalizedURL)(implicit session: RSession): Unit = {}

  def getChangesSince(num: SequenceNumber[RenormalizedURL], limit: Int = -1, state: State[RenormalizedURL])(implicit session: RSession): Seq[RenormalizedURL] = {
    super.getBySequenceNumber(num, limit).filter(_.state == state)
  }

  def getChangesBetween(lowSeq: SequenceNumber[RenormalizedURL], highSeq: SequenceNumber[RenormalizedURL], state: State[RenormalizedURL])(implicit session: RSession): Seq[RenormalizedURL] = {
    super.getBySequenceNumber(lowSeq, highSeq).filter(_.state == state)
  }

  def saveWithoutIncreSeqnum(model: RenormalizedURL)(implicit session: RWSession): RenormalizedURL = {
    super.save(model)
  }

  def pageView(pageNum: Int, pageSize: Int)(implicit session: RSession): Seq[RenormalizedURL] = {
    (for {
      r <- rows if r.state =!= RenormalizedURLStates.INACTIVE
      s <- urlRepo.rows if r.urlId === s.id
    } yield (r, s)).sortBy(_._2.url).drop(pageNum * pageSize).take(pageSize).map { _._1 }.list

  }

  def activeCount()(implicit session: RSession): Int = {
    (for (r <- rows if r.state =!= RenormalizedURLStates.INACTIVE) yield r).list.size
  }

}