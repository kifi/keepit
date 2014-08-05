package com.keepit.cortex.models.word2vec

import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.common.plugin.SchedulingProperties
import com.keepit.common.actor.ActorInstance
import com.keepit.cortex.plugins._
import com.google.inject.{ Inject, Singleton }
import com.keepit.model.NormalizedURI
import com.keepit.common.db.Id
import scala.concurrent.duration._

@Singleton
class RichWord2VecURIFeatureUpdater @Inject() (
    representer: RichWord2VecURIRepresenter,
    featureStore: RichWord2VecURIFeatureStore,
    commitStore: Word2VecURIFeatureCommitStore,
    uriPuller: URIPuller) extends URIFeatureUpdater(representer, featureStore, commitStore, uriPuller) {
  override val pullSize = 300
}

class RichWord2VecURIFeatureUpdateActor @Inject() (
  airbrake: AirbrakeNotifier,
  updater: RichWord2VecURIFeatureUpdater) extends FeatureUpdateActor(airbrake: AirbrakeNotifier, updater)

trait RichWord2VecURIFeatureUpdatePlugin extends FeatureUpdatePlugin[NormalizedURI, Word2Vec]{
  override val updateFrequency: FiniteDuration = 2 days
}

@Singleton
class RichWord2VecURIFeatureUpdatePluginImpl @Inject() (
  actor: ActorInstance[RichWord2VecURIFeatureUpdateActor],
  discovery: ServiceDiscovery,
  val scheduling: SchedulingProperties) extends BaseFeatureUpdatePlugin(actor, discovery) with RichWord2VecURIFeatureUpdatePlugin

@Singleton
class RichWord2VecURIFeatureRetriever @Inject() (
    featureStore: RichWord2VecURIFeatureStore,
    commitStore: Word2VecURIFeatureCommitStore,
    uriPuller: URIPuller) extends FeatureRetrieval(featureStore, commitStore, uriPuller) {
  override def genFeatureKey(uri: NormalizedURI): Id[NormalizedURI] = uri.id.get
}
