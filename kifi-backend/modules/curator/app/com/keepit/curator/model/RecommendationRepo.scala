package com.keepit.curator.model

import com.google.inject.{ Inject, ImplementedBy }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.{ DBSession, DataBaseComponent, DbRepo }
import com.keepit.common.logging.Logging
import com.keepit.common.time.Clock
import com.keepit.model.{ User, NormalizedURI }
import play.api.libs.json.JsObject

@ImplementedBy(classOf[RecommendationRepoImpl])
trait RecommendationRepo extends DbRepo[Recommendation] {
  def getByUriId(uriId: Id[NormalizedURI])(implicit session: RSession): Seq[Recommendation]
  def getByUriAndUserId(uriId: Id[NormalizedURI], userId: Id[User])(implicit session: RSession): Option[Recommendation]
  def getFirstByUriId(uriId: Id[NormalizedURI])(implicit session: RSession): Option[Recommendation]
  def getByTopFinalScore(userId: Id[User], maxBatchSize: Int)(implicit session: RSession): Seq[Recommendation]
}

class RecommendationRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock)
    extends DbRepo[Recommendation] with RecommendationRepo with Logging {

  import DBSession._
  import db.Driver.simple._

  type RepoImpl = RecommendationTable

  class RecommendationTable(tag: Tag) extends RepoTable[Recommendation](db, tag, "recommendation_item") {
    def uriId = column[Id[NormalizedURI]]("uri_id", O.NotNull)
    def userId = column[Id[User]]("user_id", O.NotNull)
    def finalScore = column[Float]("final_score", O.NotNull)
    def allScore = column[JsObject]("all_score", O.NotNull)
    def seen = column[Boolean]("was_seen", O.NotNull)
    def clicked = column[Boolean]("was_clicked", O.NotNull)
    def kept = column[Boolean]("was_kept", O.NotNull)
    def * = (id.?, createdAt, updatedAt, uriId, userId, finalScore, allScore, seen, clicked, kept, state) <> ((Recommendation.apply _).tupled, Recommendation.unapply _)
  }

  def table(tag: Tag) = new RecommendationTable(tag)
  initTable()

  def getByUriId(uriId: Id[NormalizedURI])(implicit session: RSession): Seq[Recommendation] = {
    (for (row <- rows if row.uriId === uriId && row.state === RecommendationStates.ACTIVE) yield row).list
  }

  def getByUriAndUserId(uriId: Id[NormalizedURI], userId: Id[User])(implicit session: RSession): Option[Recommendation] = {
    (for (row <- rows if row.uriId === uriId && row.userId === userId && row.state === RecommendationStates.ACTIVE) yield row).firstOption
  }

  def getFirstByUriId(uriId: Id[NormalizedURI])(implicit session: RSession): Option[Recommendation] = {
    (for (row <- rows if row.uriId === uriId && row.state === RecommendationStates.ACTIVE) yield row).firstOption
  }

  def getByTopFinalScore(userId: Id[User], maxBatchSize: Int)(implicit session: RSession): Seq[Recommendation] = {
    (for (row <- rows if row.userId === userId && row.state === RecommendationStates.ACTIVE) yield row).sortBy(_.finalScore.desc).take(maxBatchSize).list
  }

  def deleteCache(model: Recommendation)(implicit session: RSession): Unit = {}

  def invalidateCache(model: Recommendation)(implicit session: RSession): Unit = {}
}