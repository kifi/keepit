package com.keepit.model

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.db.slick._
import com.keepit.common.db.Id
import com.keepit.common.time.Clock
import com.keepit.common.db.SequenceNumber
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db.State

@ImplementedBy(classOf[ChangedURIRepoImpl])
trait ChangedURIRepo extends Repo[ChangedURI] with SeqNumberFunction[ChangedURI] {
  def getChangesSince(num: SequenceNumber[ChangedURI], limit: Int, state: State[ChangedURI] = ChangedURIStates.APPLIED)(implicit session: RSession): Seq[ChangedURI]
  def getChangesBetween(lowSeq: SequenceNumber[ChangedURI], highSeq: SequenceNumber[ChangedURI], state: State[ChangedURI] = ChangedURIStates.APPLIED)(implicit session: RSession): Seq[ChangedURI] // (low, high]
  def getHighestSeqNum()(implicit session: RSession): Option[SequenceNumber[ChangedURI]]
  def page(pageNum: Int, pageSize: Int)(implicit session: RSession): Seq[ChangedURI]
  def countState(state: State[ChangedURI])(implicit session: RSession): Int
  def saveWithoutIncreSeqnum(model: ChangedURI)(implicit session: RWSession): ChangedURI // useful when we track processed merge requests
}

@Singleton
class ChangedURIRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock) extends DbRepo[ChangedURI] with ChangedURIRepo with SeqNumberDbFunction[ChangedURI] {

  import db.Driver.simple._

  type RepoImpl = ChangedURITable

  class ChangedURITable(tag: Tag) extends RepoTable[ChangedURI](db, tag, "changed_uri") with SeqNumberColumn[ChangedURI] {
    def oldUriId = column[Id[NormalizedURI]]("old_uri_id", O.NotNull)
    def newUriId = column[Id[NormalizedURI]]("new_uri_id", O.NotNull)
    def * = (id.?, createdAt, updatedAt, oldUriId, newUriId, state, seq) <> ((ChangedURI.apply _).tupled, ChangedURI.unapply _)
  }

  def table(tag: Tag) = new ChangedURITable(tag)
  initTable()

  override def invalidateCache(model: ChangedURI)(implicit session: RSession): Unit = {}
  override def deleteCache(model: ChangedURI)(implicit session: RSession): Unit = {}

  override def save(model: ChangedURI)(implicit session: RWSession) = {
    val newModel = model.copy(seq = sequence.incrementAndGet())
    super.save(newModel)
  }

  def getChangesSince(num: SequenceNumber[ChangedURI], limit: Int = -1, state: State[ChangedURI] = ChangedURIStates.APPLIED)(implicit session: RSession): Seq[ChangedURI] = {
    super.getBySequenceNumber(num, limit).filter(_.state == state)
  }

  def getChangesBetween(lowSeq: SequenceNumber[ChangedURI], highSeq: SequenceNumber[ChangedURI], state: State[ChangedURI] = ChangedURIStates.APPLIED)(implicit session: RSession): Seq[ChangedURI] = {
    super.getBySequenceNumber(lowSeq, highSeq).filter(_.state == state)
  }

  def getHighestSeqNum()(implicit session: RSession): Option[SequenceNumber[ChangedURI]] = {
    Some(sequence.getLastGeneratedSeq())
  }

  override def page(pageNum: Int, pageSize: Int)(implicit session: RSession): Seq[ChangedURI] = {
    val q = for (r <- rows if r.state === ChangedURIStates.APPLIED) yield r
    q.sortBy(_.updatedAt desc).drop(pageSize * pageNum).take(pageSize).list
  }

  def countState(state: State[ChangedURI])(implicit session: RSession): Int =
    (for (r <- rows if r.state === state) yield r).list.size

  def saveWithoutIncreSeqnum(model: ChangedURI)(implicit session: RWSession): ChangedURI = {
    super.save(model)
  }

}
