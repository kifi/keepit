package com.keepit.curator.model

import com.google.inject.{ ImplementedBy, Singleton, Inject }
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.{ State, Id }
import com.keepit.common.db.slick.{ SeqNumberFunction, SeqNumberDbFunction, DBSession, DbRepo, DataBaseComponent }
import com.keepit.common.logging.Logging
import com.keepit.common.time.Clock
import com.keepit.model.{ NormalizedURI }
import play.api.libs.json.Json

@ImplementedBy(classOf[PublicFeedRepoImpl])
trait PublicFeedRepo extends DbRepo[PublicFeed] with SeqNumberFunction[PublicFeed] {
  def getByUri(uriId: Id[NormalizedURI], publicFeedState: Option[State[PublicFeed]])(implicit session: RSession): Option[PublicFeed]
  def getByTopMasterScore(maxBatchSize: Int, publicFeedState: Option[State[PublicFeed]] = Some(PublicFeedStates.ACTIVE))(implicit session: RSession): Seq[PublicFeed]
}

@Singleton
class PublicFeedRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock)
    extends DbRepo[PublicFeed] with PublicFeedRepo with SeqNumberDbFunction[PublicFeed] with Logging {

  private val sequence = db.getSequence[PublicFeed]("public_feed_sequence")

  import DBSession._
  import db.Driver.simple._

  implicit val uriPublicScoresMapper = MappedColumnType.base[PublicUriScores, String](
    { scores => Json.stringify(Json.toJson(scores)) },
    { jstr => Json.parse(jstr).as[PublicUriScores] })

  type RepoImpl = PublicFeedTable

  class PublicFeedTable(tag: Tag) extends RepoTable[PublicFeed](db, tag, "public_feed") with SeqNumberColumn[PublicFeed] {
    def uriId = column[Id[NormalizedURI]]("uri_id", O.NotNull)
    def publicMasterScore = column[Float]("public_master_score", O.NotNull)
    def publicAllScores = column[PublicUriScores]("public_all_scores", O.NotNull)
    def * = (id.?, createdAt, updatedAt, state, seq, uriId, publicMasterScore, publicAllScores) <> ((PublicFeed.apply _).tupled, PublicFeed.unapply _)
  }

  def table(tag: Tag) = new PublicFeedTable(tag)
  initTable()

  def deleteCache(model: PublicFeed)(implicit session: RSession): Unit = {}

  def invalidateCache(model: PublicFeed)(implicit session: RSession): Unit = {}

  def getByUri(uriId: Id[NormalizedURI], publicFeedState: Option[State[PublicFeed]])(implicit session: RSession): Option[PublicFeed] = {
    (for (row <- rows if row.uriId === uriId && row.state === publicFeedState) yield row).firstOption
  }

  def getByTopMasterScore(maxBatchSize: Int, publicFeedState: Option[State[PublicFeed]])(implicit session: RSession): Seq[PublicFeed] = {
    (for (row <- rows if row.state === publicFeedState) yield row).
      sortBy(_.publicMasterScore.desc).take(maxBatchSize).list
  }

  override def assignSequenceNumbers(limit: Int = 20)(implicit session: RWSession): Int = {
    assignSequenceNumbers(sequence, "public_feed_sequence", limit)
  }
}
