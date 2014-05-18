package com.keepit.cortex.models.word2vec

import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.common.plugin.SchedulingProperties
import com.keepit.common.actor.ActorInstance
import com.keepit.cortex.plugins._
import com.google.inject.{Inject, Singleton}
import com.keepit.model.NormalizedURI
import com.keepit.common.db.Id

@Singleton
class Word2VecURIFeatureUpdater @Inject()(
  representer: Word2VecURIRepresenter,
  featureStore: Word2VecURIFeatureStore,
  commitStore: Word2VecURIFeatureCommitStore,
  uriPuller: URIPuller
) extends URIFeatureUpdater[Word2Vec](representer, featureStore, commitStore, uriPuller){
  override val pullSize = 200
}

class Word2VecURIFeatureUpdateActor @Inject()(
  airbrake: AirbrakeNotifier,
  updater: Word2VecURIFeatureUpdater
) extends FeatureUpdateActor[Id[NormalizedURI], NormalizedURI, Word2Vec](airbrake: AirbrakeNotifier, updater)

trait Word2VecURIFeatureUpdatePlugin extends FeatureUpdatePlugin[NormalizedURI, Word2Vec]

@Singleton
class Word2VecURIFeatureUpdatePluginImpl @Inject()(
  actor: ActorInstance[Word2VecURIFeatureUpdateActor],
  discovery: ServiceDiscovery,
  val scheduling: SchedulingProperties
) extends BaseFeatureUpdatePlugin[Id[NormalizedURI], NormalizedURI, Word2Vec](actor, discovery) with Word2VecURIFeatureUpdatePlugin

@Singleton
class Word2VecURIFeatureRetriever @Inject()(
  featureStore: Word2VecURIFeatureStore,
  commitStore: Word2VecURIFeatureCommitStore,
  uriPuller: URIPuller
) extends FeatureRetrieval(featureStore, commitStore, uriPuller){
 override def genFeatureKey(uri: NormalizedURI): Id[NormalizedURI] = uri.id.get
}
