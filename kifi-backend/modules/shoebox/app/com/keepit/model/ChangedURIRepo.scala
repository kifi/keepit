package com.keepit.model

import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.db.slick._
import com.keepit.common.db.Id
import com.keepit.common.time.Clock
import com.keepit.common.db.SequenceNumber
import com.keepit.common.db.slick.DBSession.{RWSession, RSession}

@ImplementedBy(classOf[ChangedURIRepoImpl])
trait ChangedURIRepo extends Repo[ChangedURI] {
  def getChangesSince(num: SequenceNumber, limit: Int)(implicit session: RSession): Seq[ChangedURI]
  def getHighestSeqNum()(implicit session: RSession): Option[SequenceNumber]
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

  def getChangesSince(num: SequenceNumber, limit: Int = -1)(implicit session: RSession): Seq[ChangedURI] = {
    val q = (for (r <- table if r.seq > num) yield r).sortBy(_.seq).list
    if (limit == -1) q else q.take(limit)
  }

  def getHighestSeqNum()(implicit session: RSession): Option[SequenceNumber] = {
    (for (r <- table) yield r.seq).sortBy(x => x).list.lastOption
  }
}
