package com.keepit.cortex.dbmodel

import com.keepit.common.db.slick._
import com.google.inject.{ImplementedBy, Provider, Inject, Singleton}
import com.keepit.common.time._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.db.Id
import com.keepit.model.NormalizedURI
import com.keepit.common.db.State
import com.keepit.common.db.SequenceNumber
import com.keepit.cortex.core.ModelVersion
import com.keepit.cortex.models.lda.DenseLDA
import com.keepit.common.db.slick.DBSession.RSession



trait URILDATopicRepo extends DbRepo[URILDATopic]

class URILDATopicRepoImpl @Inject()(
  val db: DataBaseComponent,
  val clock: Clock,
  airbrake: AirbrakeNotifier
) extends DbRepo[URILDATopic] with URILDATopicRepo {

  import db.Driver.simple._

  type RepoImpl = URILDATopicTable

  class URILDATopicTable(tag: Tag) extends RepoTable[URILDATopic](db, tag, "uri_lda_topic"){
    def uriId = column[Id[NormalizedURI]]("uri_id")
    def uriState = column[State[NormalizedURI]]("uri_state")
    def uriSeq = column[SequenceNumber[NormalizedURI]]("uri_seq")
    def version = column[ModelVersion[DenseLDA]]("lda_version")
    def feature = column[Array[Byte]]("feature")
    def * = (id.?, createdAt, updatedAt, uriId, uriState, uriSeq, version, feature, state ) <> ((URILDATopic.apply _).tupled, URILDATopic.unapply _)
  }

  def table(tag:Tag) = new URILDATopicTable(tag)
  initTable()

  def deleteCache(model: URILDATopic)(implicit session: RSession): Unit = {}
  def invalidateCache(model: URILDATopic)(implicit session: RSession): Unit = {}



}