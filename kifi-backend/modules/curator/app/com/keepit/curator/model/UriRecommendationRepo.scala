package com.keepit.curator.model

import com.google.inject.{ Singleton, Inject, ImplementedBy }
import com.keepit.common.db.{ State, Id }
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.{ DBSession, DataBaseComponent, DbRepo }
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.common.time.Clock
import com.keepit.model.{ UriRecommendationUserInteraction, UriRecommendationFeedback, User, NormalizedURI }
import play.api.libs.json.{ Json }

@ImplementedBy(classOf[UriRecommendationRepoImpl])
trait UriRecommendationRepo extends DbRepo[UriRecommendation] {
  def getByUriAndUserId(uriId: Id[NormalizedURI], userId: Id[User], uriRecommendationState: Option[State[UriRecommendation]])(implicit session: RSession): Option[UriRecommendation]
  def getByTopMasterScore(userId: Id[User], maxBatchSize: Int, uriRecommendationState: Option[State[UriRecommendation]] = Some(UriRecommendationStates.ACTIVE))(implicit session: RSession): Seq[UriRecommendation]
  def updateUriRecommendationFeedback(userId: Id[User], uriId: Id[NormalizedURI], feedback: UriRecommendationFeedback)(implicit session: RSession): Boolean
  def updateUriRecommendationUserInteraction(userId: Id[User], uriId: Id[NormalizedURI], interaction: UriRecommendationUserInteraction)(implicit session: RSession): Boolean
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
    { jstr => Json.parse(jstr).as[UriScores] }
  )

  implicit val attributionMapper = MappedColumnType.base[SeedAttribution, String](
    { attr => Json.stringify(Json.toJson(attr)) },
    { jstr => Json.parse(jstr).as[SeedAttribution] }
  )

  type RepoImpl = UriRecommendationTable

  class UriRecommendationTable(tag: Tag) extends RepoTable[UriRecommendation](db, tag, "uri_recommendation") {
    def vote = column[Boolean]("vote", O.Nullable)
    def uriId = column[Id[NormalizedURI]]("uri_id", O.NotNull)
    def userId = column[Id[User]]("user_id", O.NotNull)
    def masterScore = column[Float]("master_score", O.NotNull)
    def allScores = column[UriScores]("all_scores", O.NotNull)
    def seen = column[Boolean]("seen", O.NotNull)
    def clicked = column[Boolean]("clicked", O.NotNull)
    def kept = column[Boolean]("kept", O.NotNull)
    def attribution = column[SeedAttribution]("attribution", O.NotNull)
    def * = (id.?, createdAt, updatedAt, state, vote.?, uriId, userId, masterScore, allScores, seen, clicked, kept, attribution) <> ((UriRecommendation.apply _).tupled, UriRecommendation.unapply _)
  }

  def table(tag: Tag) = new UriRecommendationTable(tag)
  initTable()

  def getByUriAndUserId(uriId: Id[NormalizedURI], userId: Id[User], uriRecommendationState: Option[State[UriRecommendation]])(implicit session: RSession): Option[UriRecommendation] = {
    (for (row <- rows if row.uriId === uriId && row.userId === userId && row.state =!= uriRecommendationState.orNull) yield row).firstOption
  }

  def getByTopMasterScore(userId: Id[User], maxBatchSize: Int, uriRecommendationState: Option[State[UriRecommendation]] = Some(UriRecommendationStates.ACTIVE))(implicit session: RSession): Seq[UriRecommendation] = {
    (for (row <- rows if row.userId === userId) yield row).sortBy(_.masterScore.desc).take(maxBatchSize).list
  }

  def updateUriRecommendationFeedback(userId: Id[User], uriId: Id[NormalizedURI], feedback: UriRecommendationFeedback)(implicit session: RSession): Boolean = {
    (if (feedback.seen.get) (for (row <- rows if row.uriId === uriId && row.userId === userId) yield (row.seen, row.updatedAt)).update((feedback.seen.get, currentDateTime)) > 0 else true) ||
      (if (feedback.clicked.get) (for (row <- rows if row.uriId === uriId && row.userId === userId) yield (row.clicked, row.updatedAt)).update((feedback.clicked.get, currentDateTime)) > 0 else true) ||
      (if (feedback.kept.get) (for (row <- rows if row.uriId === uriId && row.userId === userId) yield (row.kept, row.updatedAt)).update((feedback.kept.get, currentDateTime)) > 0 else true)
  }

  def updateUriRecommendationUserInteraction(userId: Id[User], uriId: Id[NormalizedURI], interaction: UriRecommendationUserInteraction)(implicit session: RSession): Boolean = {
    (for (row <- rows if row.uriId === uriId && row.userId === userId) yield (row.vote.?, row.updatedAt)).update((interaction.vote, currentDateTime)) > 0
  }

  def deleteCache(model: UriRecommendation)(implicit session: RSession): Unit = {}

  def invalidateCache(model: UriRecommendation)(implicit session: RSession): Unit = {}
}

