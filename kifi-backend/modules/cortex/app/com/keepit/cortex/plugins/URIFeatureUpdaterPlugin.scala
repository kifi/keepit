package com.keepit.cortex.plugins

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

import com.google.inject.{ImplementedBy, Inject, Singleton}
import com.keepit.common.db.Id
import com.keepit.common.db.SequenceNumber
import com.keepit.cortex.core._
import com.keepit.cortex.store._
import com.keepit.model.NormalizedURI
import com.keepit.shoebox.ShoeboxServiceClient

@ImplementedBy(classOf[URIPullerImpl])
trait URIPuller extends DataPuller[NormalizedURI]

@Singleton
class URIPullerImpl @Inject()(
  shoebox: ShoeboxServiceClient
) extends URIPuller{
  def getSince(lowSeq: SequenceNumber[NormalizedURI], limit: Int): Seq[NormalizedURI] = {
    Await.result(shoebox.getScrapedFullURIs(lowSeq, limit), 5 seconds)
  }
  def getBetween(lowSeq: SequenceNumber[NormalizedURI], highSeq: SequenceNumber[NormalizedURI]): Seq[NormalizedURI] = {
    val limit = (highSeq.value - lowSeq.value).toInt
    val uris = Await.result(shoebox.getScrapedFullURIs(lowSeq, limit), 5 seconds)
    uris.filter(_.seq <= highSeq)
  }
}

abstract class URIFeatureUpdater[M <: StatModel](
  representer: FeatureRepresenter[NormalizedURI, M],
  featureStore: VersionedStore[Id[NormalizedURI], M, FeatureRepresentation[NormalizedURI, M]],
  commitInfoStore: CommitInfoStore[NormalizedURI, M],
  dataPuller: DataPuller[NormalizedURI]
) extends FeatureUpdater[Id[NormalizedURI], NormalizedURI, M](representer, featureStore, commitInfoStore, dataPuller){

  protected def getSeqNumber(uri: NormalizedURI): SequenceNumber[NormalizedURI] = uri.seq
  protected def genFeatureKey(uri: NormalizedURI): Id[NormalizedURI] = uri.id.get
}
