package com.keepit.curator.model

import com.google.inject.{ Inject, ImplementedBy }
import com.keepit.common.db.{ Id }
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.{ DBSession, DataBaseComponent, DbRepo }
import com.keepit.common.logging.Logging
import com.keepit.common.time.Clock
import com.keepit.model.{ User, NormalizedURI }
import play.api.libs.json.{ Json }

@ImplementedBy(classOf[UriRecommendationRepoImpl])
trait UriRecommendationRepo extends DbRepo[UriRecommendation] {
  def getByUriAndUserId(uriId: Id[NormalizedURI], userId: Id[User])(implicit session: RSession): Option[UriRecommendation]
  def getByTopFinalScore(userId: Id[User], maxBatchSize: Int)(implicit session: RSession): Seq[UriRecommendation]
}

class UriRecommendationRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock)
    extends DbRepo[UriRecommendation] with UriRecommendationRepo with Logging {

  import DBSession._
  import db.Driver.simple._

  implicit val uriScoresMapper = MappedColumnType.base[UriScores, String](UriScoresSerializer.writes(_).toString, s => UriScoresSerializer.reads(Json.parse(s)).get)

  type RepoImpl = RecommendationTable

  class RecommendationTable(tag: Tag) extends RepoTable[UriRecommendation](db, tag, "uri_recommendation_item") {
    def uriId = column[Id[NormalizedURI]]("uri_id", O.NotNull)
    def userId = column[Id[User]]("user_id", O.NotNull)
    def masterScore = column[Float]("master_score", O.NotNull)
    def allScore = column[UriScores]("all_score", O.NotNull)
    def seen = column[Boolean]("seen", O.NotNull)
    def clicked = column[Boolean]("clicked", O.NotNull)
    def kept = column[Boolean]("kept", O.NotNull)
    def * = (id.?, createdAt, updatedAt, state, uriId, userId, masterScore, allScore, seen, clicked, kept) <> ((UriRecommendation.apply _).tupled, UriRecommendation.unapply _)
  }

  def table(tag: Tag) = new RecommendationTable(tag)
  initTable()

  def getByUriAndUserId(uriId: Id[NormalizedURI], userId: Id[User])(implicit session: RSession): Option[UriRecommendation] = {
    (for (row <- rows if row.uriId === uriId && row.userId === userId && row.state === UriRecommendationStates.ACTIVE) yield row).firstOption
  }

  def getByTopFinalScore(userId: Id[User], maxBatchSize: Int)(implicit session: RSession): Seq[UriRecommendation] = {
    (for (row <- rows if row.userId === userId && row.state === UriRecommendationStates.ACTIVE) yield row).sortBy(_.masterScore.desc).take(maxBatchSize).list
  }

  def deleteCache(model: UriRecommendation)(implicit session: RSession): Unit = {}

  def invalidateCache(model: UriRecommendation)(implicit session: RSession): Unit = {}
}

