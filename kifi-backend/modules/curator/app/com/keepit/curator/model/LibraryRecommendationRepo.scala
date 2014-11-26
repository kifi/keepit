package com.keepit.curator.model

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.core._
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick.{ DBSession, DataBaseComponent, DbRepo }
import com.keepit.common.db.{ Id, State }
import com.keepit.common.logging.Logging
import com.keepit.common.time.Clock
import com.keepit.model.{ Library, User }
import org.joda.time.DateTime
import play.api.libs.json.Json

import scala.slick.jdbc.StaticQuery

@ImplementedBy(classOf[LibraryRecommendationRepoImpl])
trait LibraryRecommendationRepo extends DbRepo[LibraryRecommendation] {
  def getByUserId(userId: Id[User])(implicit session: RSession): Seq[LibraryRecommendation]
  def getByLibraryAndUserId(libraryId: Id[Library], userId: Id[User], LibraryRecommendationState: Option[State[LibraryRecommendation]])(implicit session: RSession): Option[LibraryRecommendation]
  def getByTopMasterScore(userId: Id[User], maxBatchSize: Int, LibraryRecommendationState: Option[State[LibraryRecommendation]] = Some(LibraryRecommendationStates.ACTIVE))(implicit session: RSession): Seq[LibraryRecommendation]
  def cleanupLowMasterScoreRecos(userId: Id[User], limitNumRecosForUser: Int, before: DateTime)(implicit session: RWSession): Unit
  def getLibraryIdsForUser(userId: Id[User])(implicit session: RSession): Set[Id[Library]]
  def getUsersWithRecommendations()(implicit session: RSession): Set[Id[User]]
}

@Singleton
class LibraryRecommendationRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock)
    extends DbRepo[LibraryRecommendation] with LibraryRecommendationRepo with Logging {

  import com.keepit.common.db.slick.DBSession._
  import db.Driver.simple._

  implicit val libraryScoresMapper = MappedColumnType.base[LibraryScores, String](
    { scores => Json.stringify(Json.toJson(scores)) },
    { jstr => Json.parse(jstr).as[LibraryScores] })

  type RepoImpl = LibraryRecommendationTable

  private object QueryBuilder {
    def recommendable(rows: RepoQuery) =
      for (row <- active(rows) if row.followed === false) yield row

    def byLibrary(libraryId: Id[Library])(rows: RepoQuery): RepoQuery =
      for (row <- rows if row.libraryId === libraryId) yield row

    def byUser(userId: Id[User])(rows: RepoQuery): RepoQuery =
      for (row <- rows if row.userId === userId) yield row

    def active(rows: RepoQuery) =
      for (row <- rows if row.state === LibraryRecommendationStates.ACTIVE) yield row

    def withThreshold(masterScoreThreshold: Float)(rows: RepoQuery) =
      for (row <- rows if row.masterScore >= masterScoreThreshold) yield row
  }
  import QueryBuilder._

  class LibraryRecommendationTable(tag: Tag) extends RepoTable[LibraryRecommendation](db, tag, "library_recommendation") {
    def libraryId = column[Id[Library]]("library_id", O.NotNull)
    def userId = column[Id[User]]("user_id", O.NotNull)
    def masterScore = column[Float]("master_score", O.NotNull)
    def allScores = column[LibraryScores]("all_scores", O.NotNull)
    def followed = column[Boolean]("followed", O.NotNull)
    def * = (id.?, createdAt, updatedAt, state, libraryId, userId, masterScore, allScores, followed) <>
      ((LibraryRecommendation.apply _).tupled, LibraryRecommendation.unapply)
  }

  def table(tag: Tag) = new LibraryRecommendationTable(tag)
  initTable()

  def getByUserId(userId: Id[User])(implicit session: RSession): Seq[LibraryRecommendation] = {
    (for (row <- rows if row.userId === userId) yield row).list
  }

  def getByLibraryAndUserId(libraryId: Id[Library], userId: Id[User], excludeLibraryRecommendationState: Option[State[LibraryRecommendation]])(implicit session: RSession): Option[LibraryRecommendation] = {
    (for (row <- byUser(userId)(rows) |> byLibrary(libraryId) if row.state =!= excludeLibraryRecommendationState.orNull) yield row).firstOption
  }

  def getByTopMasterScore(userId: Id[User], maxBatchSize: Int, LibraryRecommendationState: Option[State[LibraryRecommendation]] = Some(LibraryRecommendationStates.ACTIVE))(implicit session: RSession): Seq[LibraryRecommendation] = {
    (for (row <- byUser(userId)(rows) if row.state === LibraryRecommendationState) yield row).sortBy(_.masterScore.desc).take(maxBatchSize).list
  }

  def getRecommendableByTopMasterScore(userId: Id[User], maxBatchSize: Int)(implicit session: RSession): Seq[LibraryRecommendation] = {
    (byUser(userId)(rows) |> recommendable).sortBy(_.masterScore.desc).take(maxBatchSize).list
  }

  def cleanupLowMasterScoreRecos(userId: Id[User], limitNumRecosForUser: Int, before: DateTime)(implicit session: RWSession): Unit = {
    import scala.slick.jdbc.StaticQuery.interpolation
    sqlu"""
      DELETE FROM library_recommendation WHERE user_id=$userId AND master_score < (SELECT MIN(master_score) FROM (
        SELECT master_score FROM library_recommendation WHERE user_id=$userId ORDER BY master_score DESC LIMIT $limitNumRecosForUser
      ) AS mScoreTable) AND updated_at < $before""".first()

  }

  def getLibraryIdsForUser(userId: Id[User])(implicit session: RSession): Set[Id[Library]] = {
    (for (row <- byUser(userId)(rows)) yield row.libraryId).list.toSet
  }

  def getUsersWithRecommendations()(implicit session: RSession): Set[Id[User]] = {
    import scala.slick.jdbc.StaticQuery.interpolation
    sql"SELECT DISTINCT user_id FROM library_recommendation".as[Id[User]].list.toSet
  }

  def deleteCache(model: LibraryRecommendation)(implicit session: RSession): Unit = {}

  def invalidateCache(model: LibraryRecommendation)(implicit session: RSession): Unit = {}
}

