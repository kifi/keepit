package com.keepit.model

import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.db.slick._
import com.keepit.common.db.Id
import com.keepit.common.time.Clock
import com.keepit.common.db.SequenceNumber
import com.keepit.common.db.slick.DBSession.{RWSession, RSession}
import com.keepit.common.db.State

@ImplementedBy(classOf[ChangedURIRepoImpl])
trait ChangedURIRepo extends Repo[ChangedURI] {
  def getChangesSince(num: SequenceNumber, limit: Int, state: State[ChangedURI] = ChangedURIStates.APPLIED)(implicit session: RSession): Seq[ChangedURI]
  def getChangesBetween(lowSeq: SequenceNumber, highSeq: SequenceNumber, state: State[ChangedURI] = ChangedURIStates.APPLIED)(implicit session: RSession): Seq[ChangedURI]   // (low, high]
  def getHighestSeqNum()(implicit session: RSession): Option[SequenceNumber]
  def page(pageNum: Int, pageSize: Int)(implicit session: RSession): Seq[ChangedURI]
  def allAppliedCount()(implicit session: RSession): Int
  def saveWithoutIncreSeqnum(model: ChangedURI)(implicit session: RWSession): ChangedURI     // useful when we track processed merge requests
}

@Singleton
class ChangedURIRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock
) extends DbRepo[ChangedURI] with ChangedURIRepo {
    import FortyTwoTypeMappers._
  import db.Driver.Implicit._

  private val sequence = db.getSequence("changed_uri_sequence")

  override val table = new RepoTable[ChangedURI](db, "changed_uri") {
    def oldUriId = column[Id[NormalizedURI]]("old_uri_id", O.NotNull)
    def newUriId = column[Id[NormalizedURI]]("new_uri_id", O.NotNull)
    def seq = column[SequenceNumber]("seq", O.Nullable)
    def * = id.? ~ createdAt ~ updatedAt ~ oldUriId ~ newUriId ~ state ~ seq <> (ChangedURI.apply _, ChangedURI.unapply _)
  }
    
  override def save(model: ChangedURI)(implicit session: RWSession) = {
    val newModel = model.copy(seq = sequence.incrementAndGet())
    super.save(newModel)
  }
  
  def getChangesSince(num: SequenceNumber, limit: Int = -1, state: State[ChangedURI] = ChangedURIStates.APPLIED)(implicit session: RSession): Seq[ChangedURI] = {
    val q = (for (r <- table if r.seq > num && r.state === state) yield r).sortBy(_.seq).list
    if (limit == -1) q else q.take(limit)
  }
  
  def getChangesBetween(lowSeq: SequenceNumber, highSeq: SequenceNumber, state: State[ChangedURI] = ChangedURIStates.APPLIED)(implicit session: RSession): Seq[ChangedURI] = {
    if (highSeq <= lowSeq) Nil
    else (for (r <- table if r.seq > lowSeq && r.seq <= highSeq && r.state === state) yield r).sortBy(_.seq).list
  }

  def getHighestSeqNum()(implicit session: RSession): Option[SequenceNumber] = {
    (for (r <- table) yield r.seq).sortBy(x => x).list.lastOption
  }
  
  override def page(pageNum: Int, pageSize: Int)(implicit session: RSession): Seq[ChangedURI] = {
    val q = for( r <- table if r.state === ChangedURIStates.APPLIED) yield r
    q.sortBy(_.updatedAt desc).drop(pageSize * pageNum).take(pageSize).list
  }
  
  def allAppliedCount()(implicit session: RSession): Int = {
    (for( r <- table if r.state === ChangedURIStates.APPLIED) yield r).list.size
  }
  
  def saveWithoutIncreSeqnum(model: ChangedURI)(implicit session: RWSession): ChangedURI = {
    super.save(model)
  }

}
