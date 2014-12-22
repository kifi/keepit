package com.keepit.cortex.dbmodel

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.{ DataBaseComponent, DbRepo }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.time.Clock
import com.keepit.cortex.core.ModelVersion
import com.keepit.cortex.models.lda.DenseLDA
import com.keepit.cortex.sql.CortexTypeMappers

@ImplementedBy(classOf[LDAInfoRepoImpl])
trait LDAInfoRepo extends DbRepo[LDAInfo] {
  def getDimension(version: ModelVersion[DenseLDA])(implicit session: RSession): Option[Int]
  def getAllByVersion(version: ModelVersion[DenseLDA])(implicit session: RSession): Seq[LDAInfo]
  def getUnamed(version: ModelVersion[DenseLDA], limit: Int)(implicit session: RSession): Seq[LDAInfo]
  def getByTopicId(version: ModelVersion[DenseLDA], topicId: Int)(implicit session: RSession): LDAInfo
}

@Singleton
class LDAInfoRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock,
    airbrake: AirbrakeNotifier) extends DbRepo[LDAInfo] with LDAInfoRepo with CortexTypeMappers {

  import db.Driver.simple._

  type RepoImpl = LDAInfoTable

  class LDAInfoTable(tag: Tag) extends RepoTable[LDAInfo](db, tag, "lda_info") {
    def version = column[ModelVersion[DenseLDA]]("version")
    def dimension = column[Int]("dimension")
    def topicId = column[Int]("topic_id")
    def topicName = column[String]("topic_name")
    def pmiScore = column[Float]("pmi_score", O.Nullable)
    def isActive = column[Boolean]("is_active")
    def isNameable = column[Boolean]("is_nameable")
    def numOfDocs = column[Int]("num_docs")
    def * = (id.?, createdAt, updatedAt, version, dimension, topicId, topicName, pmiScore.?, isActive, isNameable, numOfDocs) <> ((LDAInfo.apply _).tupled, LDAInfo.unapply _)
  }

  def table(tag: Tag) = new LDAInfoTable(tag)
  initTable()

  def deleteCache(model: LDAInfo)(implicit session: RSession): Unit = {}
  def invalidateCache(model: LDAInfo)(implicit session: RSession): Unit = {}

  def getDimension(version: ModelVersion[DenseLDA])(implicit session: RSession): Option[Int] = {
    (for { r <- rows if r.version === version } yield r).firstOption.map { _.dimension }
  }

  def getAllByVersion(version: ModelVersion[DenseLDA])(implicit session: RSession): Seq[LDAInfo] = {
    (for { r <- rows if r.version === version } yield r).list
  }

  def getUnamed(version: ModelVersion[DenseLDA], limit: Int)(implicit session: RSession): Seq[LDAInfo] = {
    (for { r <- rows if r.version === version && r.topicName === LDAInfo.DEFUALT_NAME && r.isNameable === true } yield r).sortBy(_.numOfDocs.desc).take(limit).list
  }

  def getByTopicId(version: ModelVersion[DenseLDA], topicId: Int)(implicit session: RSession): LDAInfo = {
    (for { r <- rows if r.version === version && r.topicId === topicId } yield r).firstOption.get
  }

}
