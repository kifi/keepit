package com.keepit.cortex.plugins

import com.keepit.cortex.store.CommitInfoStore
import com.keepit.common.db.SequenceNumber
import com.keepit.cortex.core.StatModel
import com.keepit.cortex.store.VersionedStore
import com.keepit.cortex.core.FeatureRepresenter
import com.keepit.model.NormalizedURI
import com.keepit.cortex.core.FeatureRepresentation
import com.keepit.common.db.Id
import com.google.inject.{Singleton, Inject, ImplementedBy}
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.cortex.models.lda.LDAURIFeatureUpdater
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.actor.ActorInstance
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.cortex.models.lda.DenseLDA
import com.keepit.common.plugin.SchedulingProperties

@Singleton
class URIPuller @Inject()(
  shoebox: ShoeboxServiceClient
) extends DataPuller[NormalizedURI]{
  def getSince(lowSeq: SequenceNumber[NormalizedURI], limit: Int): Seq[NormalizedURI] = Seq()
  def getBetween(lowSeq: SequenceNumber[NormalizedURI], highSeq: SequenceNumber[NormalizedURI]): Seq[NormalizedURI] = Seq()
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

class LDAURIFeatureUpdateActor @Inject()(
  airbrake: AirbrakeNotifier,
  updater: LDAURIFeatureUpdater
) extends FeatureUpdateActor[Id[NormalizedURI], NormalizedURI, DenseLDA](airbrake: AirbrakeNotifier, updater)

@ImplementedBy(classOf[LDAURIFeatureUpdatePluginImpl])
trait LDAURIFeatureUpdatePlugin extends FeatureUpdatePlugin[NormalizedURI, DenseLDA]

@Singleton
class LDAURIFeatureUpdatePluginImpl @Inject()(
  actor: ActorInstance[LDAURIFeatureUpdateActor],
  discovery: ServiceDiscovery,
  val scheduling: SchedulingProperties
) extends BaseFeatureUpdatePlugin[Id[NormalizedURI], NormalizedURI, DenseLDA](actor, discovery) with LDAURIFeatureUpdatePlugin
