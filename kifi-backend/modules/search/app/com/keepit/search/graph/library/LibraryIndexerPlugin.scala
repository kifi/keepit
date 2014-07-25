package com.keepit.search.graph.library

import com.keepit.search.index._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.model.Library
import com.google.inject.Inject
import com.keepit.common.actor.ActorInstance
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.common.plugin.SchedulingProperties
import scala.concurrent.Future

trait LibraryIndexerPlugin extends IndexerPlugin[Library, LibraryIndexer]

class LibraryIndexerPluginImpl @Inject() (
  actor: ActorInstance[LibraryIndexerActor],
  indexer: LibraryIndexer,
  airbrake: AirbrakeNotifier,
  serviceDiscovery: ServiceDiscovery,
  val scheduling: SchedulingProperties) extends IndexerPluginImpl(indexer, actor, serviceDiscovery) with LibraryIndexerPlugin

class LibraryIndexerActor @Inject() (
    airbrake: AirbrakeNotifier,
    indexer: LibraryIndexer) extends CoordinatingIndexerActor(airbrake, indexer) with Logging {

  protected def update(): Future[Boolean] = indexer.asyncUpdate()
}
