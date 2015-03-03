package com.keepit.cortex.dbmodel

import com.keepit.common.db.slick._
import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.time._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.db.Id
import com.keepit.cortex.core.ModelVersion
import com.keepit.cortex.models.lda.DenseLDA
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.cortex.sql.CortexTypeMappers
import com.keepit.model.User
import org.joda.time.DateTime

@ImplementedBy(classOf[UserLDAInterestsRepoImpl])
trait UserLDAInterestsRepo extends DbRepo[UserLDAInterests] {
  def getByUser(userId: Id[User], version: ModelVersion[DenseLDA])(implicit session: RSession): Option[UserLDAInterests]
  def getTopicMeanByUser(userId: Id[User], version: ModelVersion[DenseLDA])(implicit session: RSession): Option[UserTopicMean]
  def getAllUserTopicMean(version: ModelVersion[DenseLDA], minEvidence: Int)(implicit session: RSession): (Seq[Id[User]], Seq[UserTopicMean])
}

@Singleton
class UserLDAInterestsRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock,
    airbrake: AirbrakeNotifier) extends DbRepo[UserLDAInterests] with UserLDAInterestsRepo with CortexTypeMappers {

  import db.Driver.simple._

  type RepoImpl = UserLDATopicTable

  class UserLDATopicTable(tag: Tag) extends RepoTable[UserLDAInterests](db, tag, "user_lda_interests") {
    def userId = column[Id[User]]("user_id")
    def version = column[ModelVersion[DenseLDA]]("version")
    def numOfEvidence = column[Int]("num_of_evidence")
    def userTopicMean = column[UserTopicMean]("user_topic_mean", O.Nullable)
    def numOfRecentEvidence = column[Int]("num_of_recent_evidence")
    def userRecentTopicMean = column[UserTopicMean]("user_recent_topic_mean", O.Nullable)
    def overallSnapshotAt = column[DateTime]("overall_snapshot_at", O.Nullable)
    def overallSnapshot = column[UserTopicMean]("overall_snapshot", O.Nullable)
    def recencySnapshotAt = column[DateTime]("recency_snapshot_at", O.Nullable)
    def recencySnapshot = column[UserTopicMean]("recency_snapshot", O.Nullable)
    def * = (id.?, createdAt, updatedAt, userId, version, numOfEvidence, userTopicMean.?, numOfRecentEvidence, userRecentTopicMean.?,
      overallSnapshotAt.?, overallSnapshot.?, recencySnapshotAt.?, recencySnapshot.?, state) <> ((UserLDAInterests.apply _).tupled, UserLDAInterests.unapply _)
  }

  def table(tag: Tag) = new UserLDATopicTable(tag)
  initTable()

  def deleteCache(model: UserLDAInterests)(implicit session: RSession): Unit = {}
  def invalidateCache(model: UserLDAInterests)(implicit session: RSession): Unit = {}

  def getByUser(userId: Id[User], version: ModelVersion[DenseLDA])(implicit session: RSession): Option[UserLDAInterests] = {
    (for { r <- rows if (r.userId === userId && r.version === version) } yield r).firstOption
  }

  def getTopicMeanByUser(userId: Id[User], version: ModelVersion[DenseLDA])(implicit session: RSession): Option[UserTopicMean] = {
    (for { r <- rows if (r.userId === userId && r.version === version && r.state === UserLDAInterestsStates.ACTIVE) } yield r.userTopicMean).firstOption
  }

  def getAllUserTopicMean(version: ModelVersion[DenseLDA], minEvidence: Int)(implicit session: RSession): (Seq[Id[User]], Seq[UserTopicMean]) = {
    val res = (for { r <- rows if (r.version === version && r.numOfEvidence > minEvidence && r.state === UserLDAInterestsStates.ACTIVE) } yield (r.userId, r.userTopicMean)).list
    res.unzip
  }

}
