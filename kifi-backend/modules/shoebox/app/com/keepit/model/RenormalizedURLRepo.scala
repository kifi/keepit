package com.keepit.model
import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.db.slick._
import com.keepit.common.db.Id
import com.keepit.common.time.Clock
import com.keepit.common.db.SequenceNumber
import com.keepit.common.db.slick.DBSession.{RWSession, RSession}
import com.keepit.common.db.State

@ImplementedBy(classOf[RenormalizedURLRepoImpl])
trait RenormalizedURLRepo extends Repo[RenormalizedURL]{
  def getChangesSince(num: SequenceNumber, limit: Int, state: State[RenormalizedURL] = RenormalizedURLStates.APPLIED)(implicit session: RSession): Seq[RenormalizedURL]
  def getChangesBetween(lowSeq: SequenceNumber, highSeq: SequenceNumber, state: State[RenormalizedURL] = RenormalizedURLStates.APPLIED)(implicit session: RSession): Seq[RenormalizedURL]   // (low, high]
  def saveWithoutIncreSeqnum(model: RenormalizedURL)(implicit session: RWSession): RenormalizedURL     // useful when we track processed merge requests
  def pageView(pageNum: Int, pageSize: Int)(implicit session: RSession): Seq[RenormalizedURL]
  def activeCount()(implicit session: RSession): Int
}

@Singleton
class RenormalizedURLRepoImpl @Inject()(
  val db: DataBaseComponent,
  val clock: Clock,
  val urlRepo: URLRepoImpl
) extends DbRepo[RenormalizedURL] with RenormalizedURLRepo {
  import FortyTwoTypeMappers._
  import db.Driver.Implicit._

  private val sequence = db.getSequence("renormalized_url_sequence")

  override val table = new RepoTable[RenormalizedURL](db, "renormalized_url"){
    def urlId = column[Id[URL]]("url_id", O.NotNull)
    def newUriId = column[Id[NormalizedURI]]("new_uri_id", O.NotNull)
    def oldUriId = column[Id[NormalizedURI]]("old_uri_id", O.NotNull)
    def seq = column[SequenceNumber]("seq", O.NotNull)
    def * = id.? ~ createdAt ~ updatedAt ~ urlId ~ oldUriId ~ newUriId ~ state ~ seq <> (RenormalizedURL.apply _, RenormalizedURL.unapply _)
  }

  override def save(model: RenormalizedURL)(implicit session: RWSession) = {
    val newModel = model.copy(seq = sequence.incrementAndGet())
    super.save(newModel)
  }

  override def deleteCache(model: RenormalizedURL)(implicit session: RSession): Unit = {}
  override def invalidateCache(model: RenormalizedURL)(implicit session: RSession): Unit = {}

  def getChangesSince(num: SequenceNumber, limit: Int = -1, state: State[RenormalizedURL])(implicit session: RSession): Seq[RenormalizedURL] = {
    val q = (for (r <- table if r.seq > num && r.state === state) yield r).sortBy(_.seq).list
    if (limit == -1) q else q.take(limit)
  }

  def getChangesBetween(lowSeq: SequenceNumber, highSeq: SequenceNumber, state: State[RenormalizedURL])(implicit session: RSession): Seq[RenormalizedURL] = {
    if (highSeq <= lowSeq) Nil
    else (for (r <- table if r.seq > lowSeq && r.seq <= highSeq && r.state === state) yield r).sortBy(_.seq).list
  }

  def saveWithoutIncreSeqnum(model: RenormalizedURL)(implicit session: RWSession): RenormalizedURL = {
    super.save(model)
  }

  def pageView(pageNum: Int, pageSize: Int)(implicit session: RSession): Seq[RenormalizedURL] = {
    (for {
      r <- table if r.state =!= RenormalizedURLStates.INACTIVE
      s <- urlRepo.table if r.urlId === s.id
    } yield (r, s)).sortBy(_._2.url).drop(pageNum * pageSize).take(pageSize).map{_._1}.list

  }

  def activeCount()(implicit session: RSession): Int = {
    (for (r <- table if r.state =!= RenormalizedURLStates.INACTIVE) yield r).list.size
  }

}