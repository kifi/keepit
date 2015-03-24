package com.keepit.curator.model

import com.google.inject.{ ImplementedBy, Singleton, Inject }
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db.{ State, Id }
import com.keepit.common.db.slick.{ SeqNumberFunction, SeqNumberDbFunction, DBSession, DbRepo, DataBaseComponent }
import com.keepit.common.logging.Logging
import com.keepit.common.time.{ currentDateTime, Clock }
import com.keepit.model.{ NormalizedURI }
import org.joda.time.DateTime
import play.api.libs.json.Json
import com.keepit.common.time._
import org.joda.time.DateTime

import scala.slick.jdbc.StaticQuery

@ImplementedBy(classOf[PublicFeedRepoImpl])
trait PublicFeedRepo extends DbRepo[PublicFeed] {
  def getByUri(uriId: Id[NormalizedURI])(implicit session: RSession): Option[PublicFeed]
  def getByTopMasterScore(maxBatchSize: Int, publicFeedState: Option[State[PublicFeed]] = Some(PublicFeedStates.ACTIVE))(implicit session: RSession): Seq[PublicFeed]
  def cleanupOldFeeds(limitNumFeeds: Int)(implicit session: RWSession): Unit
}

@Singleton
class PublicFeedRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock)
    extends DbRepo[PublicFeed] with PublicFeedRepo with Logging {

  import DBSession._
  import db.Driver.simple._

  implicit val uriPublicScoresMapper = MappedColumnType.base[PublicUriScores, String](
    { scores => Json.stringify(Json.toJson(scores)) },
    { jstr => Json.parse(jstr).as[PublicUriScores] })

  type RepoImpl = PublicFeedTable

  class PublicFeedTable(tag: Tag) extends RepoTable[PublicFeed](db, tag, "public_feed") {
    def uriId = column[Id[NormalizedURI]]("uri_id", O.NotNull)
    def publicMasterScore = column[Float]("master_score", O.NotNull)
    def publicAllScores = column[PublicUriScores]("all_scores", O.NotNull)
    def * = (id.?, createdAt, updatedAt, state, uriId, publicMasterScore, publicAllScores) <> ((PublicFeed.apply _).tupled, PublicFeed.unapply _)
  }

  def table(tag: Tag) = new PublicFeedTable(tag)
  initTable()

  def deleteCache(model: PublicFeed)(implicit session: RSession): Unit = {}

  def invalidateCache(model: PublicFeed)(implicit session: RSession): Unit = {}

  def getByUri(uriId: Id[NormalizedURI])(implicit session: RSession): Option[PublicFeed] = {
    (for (row <- rows if row.uriId === uriId) yield row).firstOption
  }

  def getByTopMasterScore(maxBatchSize: Int, publicFeedState: Option[State[PublicFeed]])(implicit session: RSession): Seq[PublicFeed] = {
    (for (row <- rows if row.state === publicFeedState) yield row).
      sortBy(_.publicMasterScore.desc).take(maxBatchSize).list
  }

  def cleanupOldFeeds(limitNumFeeds: Int)(implicit session: RWSession): Unit = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation

    val cutoff = sql"""SELECT MIN(updated_at) FROM (SELECT updated_at FROM public_feed ORDER BY updated_at DESC LIMIT $limitNumFeeds) x""".as[DateTime].firstOption
    cutoff.map { cutoff =>
      sqlu"""DELETE FROM public_feed WHERE updated_at < $cutoff""".first
    }
  }

}
