package com.keepit.cortex.plugins

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.Id
import com.keepit.common.db.SequenceNumber
import com.keepit.cortex.core._
import com.keepit.cortex.store._
import com.keepit.model.NormalizedURI
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.model.serialize.UriIdAndSeq
import com.keepit.model.UrlHash
import com.keepit.common.db.slick.Database
import com.keepit.cortex.dbmodel.CortexURIRepo
import com.keepit.cortex.dbmodel.CortexURI
import com.keepit.model.NormalizedURIStates

@ImplementedBy(classOf[URIPullerImpl])
trait URIPuller extends DataPuller[NormalizedURI]

@Singleton
class URIPullerImpl @Inject() (
    db: Database,
    uriRepo: CortexURIRepo) extends URIPuller {

  // temp solution for type match.
  private def convertToNormalizedURI(uri: CortexURI): NormalizedURI = {
    NormalizedURI(id = Some(uri.uriId), seq = SequenceNumber[NormalizedURI](uri.seq.value), url = "", urlHash = UrlHash(""))
  }

  def getSince(lowSeq: SequenceNumber[NormalizedURI], limit: Int): Seq[NormalizedURI] = {
    db.readOnlyReplica { implicit s =>
      uriRepo.getSince(lowSeq, limit).map { convertToNormalizedURI(_) }
    }
  }
}

abstract class URIFeatureUpdater[M <: StatModel, FT <: FeatureRepresentation[NormalizedURI, M]](
    representer: FeatureRepresenter[NormalizedURI, M, FT],
    featureStore: VersionedStore[Id[NormalizedURI], M, FT],
    commitInfoStore: CommitInfoStore[NormalizedURI, M],
    dataPuller: DataPuller[NormalizedURI]) extends FeatureUpdater[Id[NormalizedURI], NormalizedURI, M, FT](representer, featureStore, commitInfoStore, dataPuller) {

  protected def getSeqNumber(uri: NormalizedURI): SequenceNumber[NormalizedURI] = uri.seq
  protected def genFeatureKey(uri: NormalizedURI): Id[NormalizedURI] = uri.id.get
}
