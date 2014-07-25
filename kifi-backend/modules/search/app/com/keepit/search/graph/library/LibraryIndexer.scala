package com.keepit.search.graph.library

import com.keepit.model.{ LibraryAndMemberships, Library }
import com.keepit.common.db.SequenceNumber
import com.keepit.search.index._
import com.keepit.common.healthcheck.AirbrakeNotifier
import scala.concurrent.Future
import com.keepit.shoebox.ShoeboxServiceClient
import com.google.inject.Inject
import com.keepit.common.actor.ActorInstance
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.common.plugin.SchedulingProperties
import com.keepit.common.logging.Logging

class LibraryIndexer(indexDirectory: IndexDirectory, shoebox: ShoeboxServiceClient, val airbrake: AirbrakeNotifier) extends Indexer[Library, Library, LibraryIndexer](indexDirectory, LibraryFields.decoders) {
  val name = "LibraryIndexer"
  def update(): Int = throw new UnsupportedOperationException()

  import play.api.libs.concurrent.Execution.Implicits.defaultContext

  private[library] def asyncUpdate(): Future[Boolean] = updateLock.synchronized {
    resetSequenceNumberIfReindex()
    val fetchSize = commitBatchSize
    fetchIndexables(sequenceNumber, fetchSize).map {
      case (indexables, exhausted) =>
        processIndexables(indexables)
        exhausted
    }
  }

  private def fetchIndexables(seq: SequenceNumber[Library], fetchSize: Int): Future[(Seq[LibraryIndexable], Boolean)] = {
    shoebox.getLibrariesAndMembershipsChanged(seq, fetchSize).map { updates =>
      val indexables = updates.map { case LibraryAndMemberships(library, memberships) => new LibraryIndexable(library, memberships) }
      val exhausted = updates.length < fetchSize
      (indexables, exhausted)
    }
  }

  private def processIndexables(indexables: Seq[LibraryIndexable]): Int = updateLock.synchronized {
    doUpdate(name)(indexables.iterator)
  }
}

class LibraryIndexerActor @Inject() (
    airbrake: AirbrakeNotifier,
    indexer: LibraryIndexer) extends CoordinatingIndexerActor(airbrake, indexer) with Logging {

  protected def update(): Future[Boolean] = indexer.asyncUpdate()
}

trait LibraryIndexerPlugin extends IndexerPlugin[Library, LibraryIndexer]

class LibraryIndexerPluginImpl @Inject() (
  actor: ActorInstance[LibraryIndexerActor],
  indexer: LibraryIndexer,
  airbrake: AirbrakeNotifier,
  serviceDiscovery: ServiceDiscovery,
  val scheduling: SchedulingProperties) extends IndexerPluginImpl(indexer, actor, serviceDiscovery) with LibraryIndexerPlugin
