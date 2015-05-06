package com.keepit.search.index.graph.library

import com.keepit.model.{ LibraryAndMemberships, Library }
import com.keepit.common.db.{ Id, SequenceNumber }
import com.keepit.search.index.IndexInfo
import com.keepit.search.index._
import com.keepit.common.healthcheck.AirbrakeNotifier
import scala.concurrent.Future
import com.keepit.shoebox.ShoeboxServiceClient
import com.google.inject.Inject
import com.keepit.common.actor.ActorInstance
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.common.plugin.SchedulingProperties
import com.keepit.common.logging.Logging

class LibraryIndexer(indexDirectory: IndexDirectory, shoebox: ShoeboxServiceClient, val airbrake: AirbrakeNotifier) extends Indexer[Library, Library, LibraryIndexer](indexDirectory, LibraryFields.maxPrefixLength) {
  val name = "LibraryIndexer"

  def update(): Int = throw new UnsupportedOperationException()

  import play.api.libs.concurrent.Execution.Implicits.defaultContext

  def asyncUpdate(): Future[Boolean] = updateLock.synchronized {
    resetSequenceNumberIfReindex()
    val fetchSize = commitBatchSize
    fetchIndexables(sequenceNumber, fetchSize).map {
      case (indexables, exhausted) =>
        processIndexables(indexables)
        exhausted
    }
  }

  private def fetchIndexables(seq: SequenceNumber[Library], fetchSize: Int): Future[(Seq[LibraryIndexable], Boolean)] = {
    shoebox.getDetailedLibrariesChanged(seq, fetchSize).map { updates =>
      val indexables = updates.map { library => new LibraryIndexable(library) }
      val exhausted = updates.length < fetchSize
      (indexables, exhausted)
    }
  }

  private def processIndexables(indexables: Seq[LibraryIndexable]): Int = updateLock.synchronized {
    doUpdate(name)(indexables.iterator)
  }

  override def indexInfos(name: String): Seq[IndexInfo] = {
    super.indexInfos(this.name)
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
