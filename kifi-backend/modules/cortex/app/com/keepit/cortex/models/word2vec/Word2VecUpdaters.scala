package com.keepit.cortex.models.word2vec

import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.common.plugin.SchedulingProperties
import com.keepit.common.actor.ActorInstance
import com.keepit.cortex.plugins._
import com.google.inject.{Inject, Singleton}
import com.keepit.model.NormalizedURI
import com.keepit.common.db.Id
import scala.concurrent.duration._

@Singleton
class Word2VecURIFeatureUpdater @Inject()(
  representer: Word2VecURIRepresenter,
  featureStore: Word2VecURIFeatureStore,
  commitStore: Word2VecURIFeatureCommitStore,
  uriPuller: URIPuller
) extends URIFeatureUpdater(representer, featureStore, commitStore, uriPuller){
  override val pullSize = 200
}

class Word2VecURIFeatureUpdateActor @Inject()(
  airbrake: AirbrakeNotifier,
  updater: Word2VecURIFeatureUpdater
) extends FeatureUpdateActor(airbrake: AirbrakeNotifier, updater)

trait Word2VecURIFeatureUpdatePlugin extends FeatureUpdatePlugin[NormalizedURI, Word2Vec]

@Singleton
class Word2VecURIFeatureUpdatePluginImpl @Inject()(
  actor: ActorInstance[Word2VecURIFeatureUpdateActor],
  discovery: ServiceDiscovery,
  val scheduling: SchedulingProperties
) extends BaseFeatureUpdatePlugin(actor, discovery) with Word2VecURIFeatureUpdatePlugin {
  override val startTime: FiniteDuration = 45 seconds
  override val updateFrequency: FiniteDuration = 2 minutes
}

@Singleton
class Word2VecURIFeatureRetriever @Inject()(
  featureStore: Word2VecURIFeatureStore,
  commitStore: Word2VecURIFeatureCommitStore,
  uriPuller: URIPuller
) extends FeatureRetrieval(featureStore, commitStore, uriPuller){
  override def genFeatureKey(uri: NormalizedURI): Id[NormalizedURI] = uri.id.get
}


/**
 * rich feature plugins
 */

@Singleton
class RichWord2VecURIFeatureUpdater @Inject()(
  representer: RichWord2VecURIRepresenter,
  featureStore: RichWord2VecURIFeatureStore,
  commitStore: Word2VecURIFeatureCommitStore,
  uriPuller: URIPuller
) extends URIFeatureUpdater(representer, featureStore, commitStore, uriPuller){
  override val pullSize = 250
}

class RichWord2VecURIFeatureUpdateActor @Inject()(
  airbrake: AirbrakeNotifier,
  updater: RichWord2VecURIFeatureUpdater
) extends FeatureUpdateActor(airbrake: AirbrakeNotifier, updater)


trait RichWord2VecURIFeatureUpdatePlugin extends FeatureUpdatePlugin[NormalizedURI, Word2Vec]

@Singleton
class RichWord2VecURIFeatureUpdatePluginImpl @Inject()(
  actor: ActorInstance[RichWord2VecURIFeatureUpdateActor],
  discovery: ServiceDiscovery,
  val scheduling: SchedulingProperties
) extends BaseFeatureUpdatePlugin(actor, discovery) with RichWord2VecURIFeatureUpdatePlugin {

  override val startTime: FiniteDuration = 45 seconds
  override val updateFrequency: FiniteDuration = 2 minutes
}

@Singleton
class RichWord2VecURIFeatureRetriever @Inject()(
  featureStore: RichWord2VecURIFeatureStore,
  commitStore: Word2VecURIFeatureCommitStore,
  uriPuller: URIPuller
) extends FeatureRetrieval(featureStore, commitStore, uriPuller){
  override def genFeatureKey(uri: NormalizedURI): Id[NormalizedURI] = uri.id.get
}
