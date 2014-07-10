package com.keepit.cortex.dbmodel

import com.keepit.common.db.slick._
import com.google.inject.{ ImplementedBy, Provider, Inject, Singleton }
import com.keepit.common.time._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.db.Id
import com.keepit.model.NormalizedURI
import com.keepit.common.db.State
import com.keepit.common.db.SequenceNumber
import com.keepit.cortex.core.ModelVersion
import com.keepit.cortex.models.lda.DenseLDA
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.cortex.sql.CortexTypeMappers
import scala.slick.jdbc.StaticQuery
import com.keepit.cortex.models.lda.LDATopic
import com.keepit.cortex.models.lda.SparseTopicRepresentation
import com.keepit.cortex.models.lda.LDATopicFeature
import org.joda.time.DateTime
import com.keepit.cortex.core.StatModelName

@ImplementedBy(classOf[FeatureCommitInfoRepoImpl])
trait FeatureCommitInfoRepo extends DbRepo[FeatureCommitInfo] {
  def getByModelAndVersion(modelName: StatModelName, version: Int)(implicit session: RSession): Option[FeatureCommitInfo]
}

@Singleton
class FeatureCommitInfoRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock,
    airbrake: AirbrakeNotifier) extends DbRepo[FeatureCommitInfo] with FeatureCommitInfoRepo with CortexTypeMappers {

  import db.Driver.simple._

  type RepoImpl = FeatureCommitInfoTable

  class FeatureCommitInfoTable(tag: Tag) extends RepoTable[FeatureCommitInfo](db, tag, "feature_commit_info") {
    def modelName = column[StatModelName]("model_name")
    def modelVersion = column[Int]("model_version")
    def seq = column[Long]("seq")
    def * = (id.?, createdAt, updatedAt, modelName, modelVersion, seq) <> ((FeatureCommitInfo.apply _).tupled, FeatureCommitInfo.unapply _)
  }

  def table(tag: Tag) = new FeatureCommitInfoTable(tag)
  initTable()

  def deleteCache(model: FeatureCommitInfo)(implicit session: RSession): Unit = {}
  def invalidateCache(model: FeatureCommitInfo)(implicit session: RSession): Unit = {}

  def getByModelAndVersion(modelName: StatModelName, version: Int)(implicit session: RSession): Option[FeatureCommitInfo] = {
    val q = for {
      r <- rows if (r.modelName === modelName && r.modelVersion === version)
    } yield r

    q.firstOption
  }
}
