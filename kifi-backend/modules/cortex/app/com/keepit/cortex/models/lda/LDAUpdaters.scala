package com.keepit.cortex.models.lda

import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.common.plugin.SchedulingProperties
import com.keepit.common.actor.ActorInstance
import com.keepit.cortex.plugins._
import com.google.inject.{Inject, Singleton}
import com.keepit.model.NormalizedURI
import com.keepit.common.db.Id

@Singleton
class LDAURIFeatureUpdater @Inject()(
  representer: LDAURIRepresenter,
  featureStore: LDAURIFeatureStore,
  commitStore: LDAURIFeatureCommitStore,
  uriPuller: URIPuller
) extends URIFeatureUpdater[DenseLDA](representer, featureStore, commitStore, uriPuller)

class LDAURIFeatureUpdateActor @Inject()(
  airbrake: AirbrakeNotifier,
  updater: LDAURIFeatureUpdater
) extends FeatureUpdateActor[Id[NormalizedURI], NormalizedURI, DenseLDA](airbrake: AirbrakeNotifier, updater)

trait LDAURIFeatureUpdatePlugin extends FeatureUpdatePlugin[NormalizedURI, DenseLDA]

@Singleton
class LDAURIFeatureUpdatePluginImpl @Inject()(
  actor: ActorInstance[LDAURIFeatureUpdateActor],
  discovery: ServiceDiscovery,
  val scheduling: SchedulingProperties
) extends BaseFeatureUpdatePlugin[Id[NormalizedURI], NormalizedURI, DenseLDA](actor, discovery) with LDAURIFeatureUpdatePlugin
