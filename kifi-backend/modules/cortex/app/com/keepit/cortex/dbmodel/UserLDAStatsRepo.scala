package com.keepit.cortex.dbmodel

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.{ DataBaseComponent, DbRepo }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.time.Clock
import com.keepit.cortex.core.ModelVersion
import com.keepit.cortex.models.lda.{ LDATopic, DenseLDA }
import com.keepit.cortex.sql.CortexTypeMappers
import com.keepit.model.User
import com.keepit.common.db.slick._

@ImplementedBy(classOf[UserLDAStatsRepoImpl])
trait UserLDAStatsRepo extends DbRepo[UserLDAStats] {
  def getByUser(userId: Id[User], version: ModelVersion[DenseLDA])(implicit session: RSession): Option[UserLDAStats]
  def getActiveByUser(userId: Id[User], version: ModelVersion[DenseLDA])(implicit session: RSession): Option[UserLDAStats]
  def getAllUsers(version: ModelVersion[DenseLDA])(implicit session: RSession): Seq[Id[User]]
}

@Singleton
class UserLDAStatsRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock,
    airbrake: AirbrakeNotifier) extends DbRepo[UserLDAStats] with UserLDAStatsRepo with CortexTypeMappers {

  import db.Driver.simple._

  type RepoImpl = UserPersonalLDAStatsTable

  class UserPersonalLDAStatsTable(tag: Tag) extends RepoTable[UserLDAStats](db, tag, "user_lda_stats") {
    def userId = column[Id[User]]("user_id")
    def version = column[ModelVersion[DenseLDA]]("version")
    def numOfEvidence = column[Int]("num_of_evidence")
    def firstTopic = column[LDATopic]("first_topic", O.Nullable)
    def secondTopic = column[LDATopic]("second_topic", O.Nullable)
    def thirdTopic = column[LDATopic]("third_topic", O.Nullable)
    def firstTopicScore = column[Float]("first_topic_score", O.Nullable)
    def userTopicMean = column[UserTopicMean]("user_topic_mean", O.Nullable)
    def userTopicVar = column[UserTopicVar]("user_topic_var", O.Nullable)
    def * = (id.?, createdAt, updatedAt, userId, version, numOfEvidence, firstTopic.?, secondTopic.?, thirdTopic.?, firstTopicScore.?, userTopicMean.?, userTopicVar.?, state) <> ((UserLDAStats.apply _).tupled, UserLDAStats.unapply _)
  }

  def table(tag: Tag) = new UserPersonalLDAStatsTable(tag)
  initTable()

  def deleteCache(model: UserLDAStats)(implicit session: RSession): Unit = {}
  def invalidateCache(model: UserLDAStats)(implicit session: RSession): Unit = {}

  def getByUser(userId: Id[User], version: ModelVersion[DenseLDA])(implicit session: RSession): Option[UserLDAStats] = {
    (for { r <- rows if (r.userId === userId && r.version === version) } yield r).firstOption
  }

  def getActiveByUser(userId: Id[User], version: ModelVersion[DenseLDA])(implicit session: RSession): Option[UserLDAStats] = {
    (for { r <- rows if (r.userId === userId && r.version === version && r.state === UserLDAStatsStates.ACTIVE) } yield r).firstOption
  }

  def getAllUsers(version: ModelVersion[DenseLDA])(implicit session: RSession): Seq[Id[User]] = {
    (for { r <- rows } yield r.userId).list
  }

}
