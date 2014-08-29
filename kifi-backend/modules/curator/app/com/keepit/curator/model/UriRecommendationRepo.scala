package com.keepit.curator.model

import com.google.inject.{ Singleton, Inject, ImplementedBy }
import com.keepit.common.core._
import com.keepit.common.db.{ State, Id }
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick.{ DBSession, DataBaseComponent, DbRepo }
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.common.time.Clock
import com.keepit.model.{ UriRecommendationFeedback, User, NormalizedURI }
import org.joda.time.DateTime
import play.api.libs.json.Json

import scala.slick.jdbc.StaticQuery

@ImplementedBy(classOf[UriRecommendationRepoImpl])
trait UriRecommendationRepo extends DbRepo[UriRecommendation] {
  def getByUriAndUserId(uriId: Id[NormalizedURI], userId: Id[User], uriRecommendationState: Option[State[UriRecommendation]])(implicit session: RSession): Option[UriRecommendation]
  def getByTopMasterScore(userId: Id[User], maxBatchSize: Int, uriRecommendationState: Option[State[UriRecommendation]] = Some(UriRecommendationStates.ACTIVE))(implicit session: RSession): Seq[UriRecommendation]
  def getRecommendableByTopMasterScore(userId: Id[User], maxBatchSize: Int)(implicit session: RSession): Seq[UriRecommendation]
  def getDigestRecommendableByTopMasterScore(userId: Id[User], maxBatchSize: Int, masterScoreThreshold: Float = 0f)(implicit session: RSession): Seq[UriRecommendation]
  def updateUriRecommendationFeedback(userId: Id[User], uriId: Id[NormalizedURI], feedback: UriRecommendationFeedback)(implicit session: RWSession): Boolean
  def incrementDeliveredCount(recoId: Id[UriRecommendation], withLastPushedAt: Boolean = false)(implicit session: RWSession): Unit
  def cleanupLowMasterScoreRecos(limitNumRecosForUser: Int, before: DateTime)(implicit session: RWSession): Boolean
}

@Singleton
class UriRecommendationRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock)
    extends DbRepo[UriRecommendation] with UriRecommendationRepo with Logging {

  import DBSession._
  import db.Driver.simple._

  implicit val uriScoresMapper = MappedColumnType.base[UriScores, String](
    { scores => Json.stringify(Json.toJson(scores)) },
    { jstr => Json.parse(jstr).as[UriScores] })

  implicit val attributionMapper = MappedColumnType.base[SeedAttribution, String](
    { attr => Json.stringify(Json.toJson(attr)) },
    { jstr => Json.parse(jstr).as[SeedAttribution] })

  type RepoImpl = UriRecommendationTable

  private object QueryBuilder {
    def recommendable(rows: RepoQuery) =
      for (row <- active(rows) if row.kept === false && row.trashed === false) yield row

    def byUri(uriId: Id[NormalizedURI])(rows: RepoQuery): RepoQuery =
      for (row <- rows if row.uriId === uriId) yield row

    def byUser(userId: Id[User])(rows: RepoQuery): RepoQuery =
      for (row <- rows if row.userId === userId) yield row

    def active(rows: RepoQuery) =
      for (row <- rows if row.state === UriRecommendationStates.ACTIVE) yield row

    def withThreshold(masterScoreThreshold: Float)(rows: RepoQuery) =
      for (row <- rows if row.masterScore >= masterScoreThreshold) yield row

    def notPushed(rows: RepoQuery) =
      for (row <- rows if row.lastPushedAt.isNull) yield row
  }
  import QueryBuilder._

  class UriRecommendationTable(tag: Tag) extends RepoTable[UriRecommendation](db, tag, "uri_recommendation") {
    def vote = column[Boolean]("vote", O.Nullable)
    def uriId = column[Id[NormalizedURI]]("uri_id", O.NotNull)
    def userId = column[Id[User]]("user_id", O.NotNull)
    def masterScore = column[Float]("master_score", O.NotNull)
    def allScores = column[UriScores]("all_scores", O.NotNull)
    def delivered = column[Int]("delivered", O.NotNull)
    def clicked = column[Int]("clicked", O.NotNull)
    def kept = column[Boolean]("kept", O.NotNull)
    def trashed = column[Boolean]("trashed", O.NotNull)
    def lastPushedAt = column[DateTime]("last_pushed_at", O.Nullable)
    def attribution = column[SeedAttribution]("attribution", O.NotNull)
    def * = (id.?, createdAt, updatedAt, state, vote.?, uriId, userId, masterScore, allScores, delivered, clicked,
      kept, trashed, lastPushedAt.?, attribution) <> ((UriRecommendation.apply _).tupled, UriRecommendation.unapply _)
  }

  def table(tag: Tag) = new UriRecommendationTable(tag)
  initTable()

  def getByUriAndUserId(uriId: Id[NormalizedURI], userId: Id[User], excludeUriRecommendationState: Option[State[UriRecommendation]])(implicit session: RSession): Option[UriRecommendation] = {
    (for (row <- byUser(userId)(rows) |> byUri(uriId) if row.state =!= excludeUriRecommendationState.orNull) yield row).firstOption
  }

  def getByTopMasterScore(userId: Id[User], maxBatchSize: Int, uriRecommendationState: Option[State[UriRecommendation]] = Some(UriRecommendationStates.ACTIVE))(implicit session: RSession): Seq[UriRecommendation] = {
    (for (row <- byUser(userId)(rows) if row.state === uriRecommendationState) yield row).sortBy(_.masterScore.desc).take(maxBatchSize).list
  }

  def getRecommendableByTopMasterScore(userId: Id[User], maxBatchSize: Int)(implicit session: RSession): Seq[UriRecommendation] = {
    (byUser(userId)(rows) |> recommendable).sortBy(_.masterScore.desc).take(maxBatchSize).list
  }

  def getDigestRecommendableByTopMasterScore(userId: Id[User], maxBatchSize: Int, masterScoreThreshold: Float = 0f)(implicit session: RSession): Seq[UriRecommendation] = {
    (byUser(userId)(rows) |> recommendable |> notPushed |> withThreshold(masterScoreThreshold)).sortBy(_.masterScore.desc).take(maxBatchSize).list
  }

  def updateUriRecommendationFeedback(userId: Id[User], uriId: Id[NormalizedURI], feedback: UriRecommendationFeedback)(implicit session: RWSession): Boolean = {
    import StaticQuery.interpolation

    val clickedResult = if (feedback.clicked.isDefined && feedback.clicked.get)
      sql"UPDATE uri_recommendation SET clicked=clicked+1, updated_at=$currentDateTime WHERE user_id=$userId AND uri_id=$uriId".asUpdate.first > 0
    else true
    val keptResult = if (feedback.kept.isDefined)
      (for (row <- byUser(userId)(rows) |> byUri(uriId)) yield (row.kept, row.updatedAt)).update((feedback.kept.get, currentDateTime)) > 0 else true
    val trashedResult = if (feedback.trashed.isDefined)
      (for (row <- byUser(userId)(rows) |> byUri(uriId)) yield (row.trashed, row.updatedAt)).update((feedback.trashed.get, currentDateTime)) > 0 else true

    clickedResult && keptResult && trashedResult
  }

  def incrementDeliveredCount(recoId: Id[UriRecommendation], withlastPushedAt: Boolean = false)(implicit session: RWSession): Unit = {
    import StaticQuery.interpolation
    if (withlastPushedAt) sqlu"UPDATE uri_recommendation SET delivered=delivered+1, last_pushed_at=$currentDateTime, updated_at=$currentDateTime WHERE id=$recoId".first()
    else sqlu"UPDATE uri_recommendation SET delivered=delivered+1, updated_at=$currentDateTime WHERE id=$recoId".first()
  }

  def incrementDeliveredCount(recoId: Id[UriRecommendation])(implicit session: RWSession): Unit = {
    import StaticQuery.interpolation
    sqlu"UPDATE uri_recommendation SET delivered=delivered+1, updated_at=$currentDateTime WHERE id=$recoId".first()
  }

  def cleanupLowMasterScoreRecos(limitNumRecosForUser: Int, before: DateTime)(implicit session: RWSession): Boolean = {
    import StaticQuery.interpolation
    val userIds = (for (row <- rows) yield row.userId).list.distinct

    userIds.foldLeft(true) { (updated, userId) =>
      val limitScore =
        sql"""SELECT MIN(master_score)
              FROM (
	              SELECT master_score
	              FROM uri_recommendation
	              WHERE state=${UriRecommendationStates.ACTIVE} AND user_id=$userId
	              ORDER BY master_score DESC LIMIT $limitNumRecosForUser
              ) AS mScoreTable""".as[Float].first

      ((for (
        row <- byUser(userId)(rows) |> active if row.updatedAt < before && row.masterScore < limitScore
      ) yield (row.state, row.updatedAt)).
        update((UriRecommendationStates.INACTIVE, currentDateTime)) > 0) || updated
    }

  }

  def deleteCache(model: UriRecommendation)(implicit session: RSession): Unit = {}

  def invalidateCache(model: UriRecommendation)(implicit session: RSession): Unit = {}
}

