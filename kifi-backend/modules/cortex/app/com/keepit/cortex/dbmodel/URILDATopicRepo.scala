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

@ImplementedBy(classOf[URILDATopicRepoImpl])
trait URILDATopicRepo extends DbRepo[URILDATopic] {
  def getFeature(uriId: Id[NormalizedURI], version: ModelVersion[DenseLDA])(implicit session: RSession): Option[LDATopicFeature]
  def getByURI(uriId: Id[NormalizedURI], version: ModelVersion[DenseLDA])(implicit session: RSession): Option[URILDATopic]
  def getHighestSeqNumber(version: ModelVersion[DenseLDA])(implicit session: RSession): SequenceNumber[NormalizedURI]
  def getUpdateTimeAndState(uriId: Id[NormalizedURI], version: ModelVersion[DenseLDA])(implicit session: RSession): Option[(DateTime, State[URILDATopic])]
}

@Singleton
class URILDATopicRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock,
    airbrake: AirbrakeNotifier) extends DbRepo[URILDATopic] with URILDATopicRepo with CortexTypeMappers {

  import db.Driver.simple._

  type RepoImpl = URILDATopicTable

  class URILDATopicTable(tag: Tag) extends RepoTable[URILDATopic](db, tag, "uri_lda_topic") {
    def uriId = column[Id[NormalizedURI]]("uri_id")
    def uriSeq = column[SequenceNumber[NormalizedURI]]("uri_seq")
    def version = column[ModelVersion[DenseLDA]]("version")
    def firstTopic = column[LDATopic]("first_topic", O.Nullable)
    def secondTopic = column[LDATopic]("second_topic", O.Nullable)
    def thirdTopic = column[LDATopic]("third_topic", O.Nullable)
    def sparseFeature = column[SparseTopicRepresentation]("sparse_feature", O.Nullable)
    def feature = column[LDATopicFeature]("feature", O.Nullable)
    def * = (id.?, createdAt, updatedAt, uriId, uriSeq, version, firstTopic.?, secondTopic.?, thirdTopic.?, sparseFeature.?, feature.?, state) <> ((URILDATopic.apply _).tupled, URILDATopic.unapply _)
  }

  def table(tag: Tag) = new URILDATopicTable(tag)
  initTable()

  def deleteCache(model: URILDATopic)(implicit session: RSession): Unit = {}
  def invalidateCache(model: URILDATopic)(implicit session: RSession): Unit = {}

  def getFeature(uriId: Id[NormalizedURI], version: ModelVersion[DenseLDA])(implicit session: RSession): Option[LDATopicFeature] = {
    val q = for {
      r <- rows
      if (r.uriId === uriId && r.version === version && r.state === URILDATopicStates.ACTIVE)
    } yield r.feature

    q.firstOption
  }

  def getUpdateTimeAndState(uriId: Id[NormalizedURI], version: ModelVersion[DenseLDA])(implicit session: RSession): Option[(DateTime, State[URILDATopic])] = {
    val q = for {
      r <- rows
      if (r.uriId === uriId && r.version === version)
    } yield (r.updatedAt, r.state)

    q.firstOption
  }

  def getByURI(uriId: Id[NormalizedURI], version: ModelVersion[DenseLDA])(implicit session: RSession): Option[URILDATopic] = {
    val q = for {
      r <- rows
      if (r.uriId === uriId && r.version === version)
    } yield r

    q.firstOption
  }

  def getHighestSeqNumber(version: ModelVersion[DenseLDA])(implicit session: RSession): SequenceNumber[NormalizedURI] = {
    import StaticQuery.interpolation

    val sql = sql"select max(uri_seq) from uri_lda_topic where version = ${version.version}"
    SequenceNumber[NormalizedURI](sql.as[Long].first max 0L)
  }

}
