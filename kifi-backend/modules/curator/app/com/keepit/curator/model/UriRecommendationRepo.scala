package com.keepit.curator.model

import com.google.inject.{ Singleton, Inject, ImplementedBy }
import com.keepit.common.core._
import com.keepit.common.db.{ State, Id }
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick.{ DBSession, DataBaseComponent, DbRepo }
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.common.time.Clock
import com.keepit.cortex.models.lda.LDATopic
import com.keepit.model.{ UriRecommendationFeedback, User, NormalizedURI }
import org.joda.time.DateTime
import play.api.libs.json.Json
import scala.slick.jdbc.StaticQuery

@ImplementedBy(classOf[UriRecommendationRepoImpl])
trait UriRecommendationRepo extends DbRepo[UriRecommendation] {
  def getByUserId(userId: Id[User])(implicit session: RSession): Seq[UriRecommendation]
  def getByUriAndUserId(uriId: Id[NormalizedURI], userId: Id[User], excludeUriRecommendationState: Option[State[UriRecommendation]])(implicit session: RSession): Option[UriRecommendation]
  def getByTopMasterScore(userId: Id[User], maxBatchSize: Int, uriRecommendationState: Option[State[UriRecommendation]] = Some(UriRecommendationStates.ACTIVE))(implicit session: RSession): Seq[UriRecommendation]
  def getRecommendableByTopMasterScore(userId: Id[User], maxBatchSize: Int)(implicit session: RSession): Seq[UriRecommendation]
  def getDigestRecommendableByTopMasterScore(userId: Id[User], maxBatchSize: Int, masterScoreThreshold: Float = 0f)(implicit session: RSession): Seq[UriRecommendation]
  def updateUriRecommendationFeedback(userId: Id[User], uriId: Id[NormalizedURI], feedback: UriRecommendationFeedback)(implicit session: RWSession): Boolean
  def incrementDeliveredCount(recoId: Id[UriRecommendation], withLastPushedAt: Boolean = false)(implicit session: RWSession): Unit
  def cleanupLowMasterScoreRecos(userId: Id[User], limitNumRecosForUser: Int, before: DateTime)(implicit session: RWSession): Unit
  def cleanupOldRecos(userId: Id[User], expiration: DateTime)(implicit session: RWSession): Unit
  def getUriIdsForUser(userId: Id[User])(implicit session: RSession): Set[Id[NormalizedURI]]
  def getTopUriIdsForUser(userId: Id[User])(implicit session: RSession): Set[Id[NormalizedURI]]
  def getUsersWithRecommendations()(implicit session: RSession): Set[Id[User]]
  def getGeneralRecommendationScore(uriId: Id[NormalizedURI], minClickedUsers: Int = 3)(implicit session: RSession): Option[Float]
  def getGeneralRecommendationCandidates(limit: Int, minClickedUsers: Int = 3)(implicit session: RSession): Seq[Id[NormalizedURI]]
  def deleteByUriId(uriId: Id[NormalizedURI])(implicit session: RWSession): Unit
  def insertAll(recos: Seq[UriRecommendation])(implicit session: RWSession): Int
  def insertOrUpdate(reco: UriRecommendation)(implicit session: RWSession): String
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
      for (row <- active(rows) if row.kept === false && row.trashed === false && row.delivered < 10) yield row

    def byUri(uriId: Id[NormalizedURI])(rows: RepoQuery): RepoQuery =
      for (row <- rows if row.uriId === uriId) yield row

    def byUser(userId: Id[User])(rows: RepoQuery): RepoQuery =
      for (row <- rows if row.userId === userId) yield row

    def active(rows: RepoQuery) =
      for (row <- rows if row.state === UriRecommendationStates.ACTIVE) yield row

    def withThreshold(masterScoreThreshold: Float)(rows: RepoQuery) =
      for (row <- rows if row.masterScore >= masterScoreThreshold) yield row

    def notPushed(rows: RepoQuery) =
      for (row <- rows if row.lastPushedAt.isEmpty) yield row
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
    def lastPushedAt = column[Option[DateTime]]("last_pushed_at", O.Nullable)
    def attribution = column[SeedAttribution]("attribution", O.NotNull)
    def topic1 = column[LDATopic]("topic1", O.Nullable)
    def topic2 = column[LDATopic]("topic2", O.Nullable)
    def * = (id.?, createdAt, updatedAt, state, vote.?, uriId, userId, masterScore, allScores, delivered, clicked,
      kept, trashed, lastPushedAt, attribution, topic1.?, topic2.?) <> ((UriRecommendation.apply _).tupled, UriRecommendation.unapply _)
  }

  def table(tag: Tag) = new UriRecommendationTable(tag)
  initTable()

  def getByUserId(userId: Id[User])(implicit session: RSession): Seq[UriRecommendation] = {
    (for (row <- rows if row.userId === userId) yield row).list
  }

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
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation

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
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    if (withlastPushedAt) sqlu"UPDATE uri_recommendation SET delivered=delivered+1, last_pushed_at=$currentDateTime, updated_at=$currentDateTime WHERE id=$recoId".first
    else sqlu"UPDATE uri_recommendation SET delivered=delivered+1, updated_at=$currentDateTime WHERE id=$recoId".first
  }

  def cleanupLowMasterScoreRecos(userId: Id[User], limitNumRecosForUser: Int, before: DateTime)(implicit session: RWSession): Unit = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    sqlu"""
      DELETE FROM uri_recommendation WHERE user_id=$userId AND master_score < (SELECT MIN(master_score) FROM (
        SELECT master_score FROM uri_recommendation WHERE user_id=$userId ORDER BY master_score DESC LIMIT $limitNumRecosForUser
      ) AS mScoreTable) AND (updated_at < $before OR delivered>0)""".first

  }

  def cleanupOldRecos(userId: Id[User], expiration: DateTime)(implicit session: RWSession): Unit = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    sqlu"""DELETE FROM uri_recommendation WHERE user_id=$userId AND updated_at < $expiration""".first
  }

  def getTopUriIdsForUser(userId: Id[User])(implicit session: RSession): Set[Id[NormalizedURI]] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    sql"""SELECT uri_id FROM uri_recommendation WHERE user_id=$userId ORDER BY master_score DESC LIMIT 200""".as[Id[NormalizedURI]].list.toSet
  }

  def getUriIdsForUser(userId: Id[User])(implicit session: RSession): Set[Id[NormalizedURI]] = {
    (for (row <- byUser(userId)(rows)) yield row.uriId).list.toSet
  }

  def getUsersWithRecommendations()(implicit session: RSession): Set[Id[User]] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    sql"SELECT DISTINCT user_id FROM uri_recommendation".as[Id[User]].list.toSet
  }

  def getGeneralRecommendationScore(uriId: Id[NormalizedURI], minClickedUsers: Int = 3)(implicit session: RSession): Option[Float] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation

    // take recos clicked by many users (minClickUsers)
    // compute scores (the root-mean-square of the inverse of master_scores) for each URI
    sql"""
      select sqrt(sum(score * score)/count(*))
      from (select 1/master_score score from uri_recommendation where uri_id = $uriId and clicked > 0) x
      having count(*) >= $minClickedUsers
    """.as[Float].firstOption
  }

  def getGeneralRecommendationCandidates(limit: Int, minClickedUsers: Int = 3)(implicit session: RSession): Seq[Id[NormalizedURI]] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation

    // take recos clicked by many users (minClickUsers)
    sql"""
      select uri_id from uri_recommendation where uri_id >= 0 and clicked > 0
      group by uri_id having count(*) >= $minClickedUsers limit $limit
    """.as[Id[NormalizedURI]].list
  }

  def deleteByUriId(uriId: Id[NormalizedURI])(implicit session: RWSession): Unit = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    sqlu"""DELETE FROM uri_recommendation WHERE uri_id=$uriId""".first
  }

  def deleteCache(model: UriRecommendation)(implicit session: RSession): Unit = {}

  def invalidateCache(model: UriRecommendation)(implicit session: RSession): Unit = {}

  def insertAll(recos: Seq[UriRecommendation])(implicit session: RWSession): Int = {
    rows.insertAll(recos: _*).get
  }

  def insertOrUpdate(reco: UriRecommendation)(implicit session: RWSession): String = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    import Json._

    val lastPush: String = reco.lastPushedAt.map(t => s"'${SQL_DATETIME_FORMAT.print(t)}'").getOrElse("null")
    def topic(maybeTopic: Option[LDATopic]): String = maybeTopic.map(t => s"'${t.index}'").getOrElse("null")

    val query = sqlu"""
          INSERT INTO uri_recommendation
            (created_at,updated_at,state,vote,uri_id,user_id,master_score,all_scores,
             delivered,clicked,kept,trashed,last_pushed_at,attribution,topic1,topic2)
          values
            (
              ${reco.createdAt},${reco.updatedAt},'active',${reco.vote},${reco.uriId},${reco.userId.id},${reco.masterScore},${stringify(toJson(reco.allScores))},
              ${reco.delivered},${reco.clicked},${reco.kept},${reco.trashed},#$lastPush,${stringify(toJson(reco.attribution))},${topic(reco.topic1)},${topic(reco.topic2)}
            )
          ON DUPLICATE KEY UPDATE
             updated_at=VALUES(updated_at),state=VALUES(state),master_score=VALUES(master_score),all_scores=VALUES(all_scores),
             attribution=VALUES(attribution),topic1=VALUES(topic1),topic2=VALUES(topic2)
        """
    val stmt = query.getStatement
    stmt + " ==> " + query.first.toString
  }
}

