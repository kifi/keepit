package com.keepit.search.index.graph.library.membership

import com.keepit.model.{ LibraryMembership, LibraryAndMemberships, Library }
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

class LibraryMembershipIndexer(indexDirectory: IndexDirectory, shoebox: ShoeboxServiceClient, val airbrake: AirbrakeNotifier) extends Indexer[LibraryMembership, LibraryMembership, LibraryMembershipIndexer](indexDirectory, LibraryMembershipFields.decoders) {
  val name = "LibraryMembershipIndexer"

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

  private def fetchIndexables(seq: SequenceNumber[LibraryMembership], fetchSize: Int): Future[(Seq[LibraryMembershipIndexable], Boolean)] = {
    shoebox.getLibraryMembershipsChanged(seq, fetchSize).map { updates =>
      val indexables = updates.map { membership => new LibraryMembershipIndexable(membership) }
      val exhausted = updates.length < fetchSize
      (indexables, exhausted)
    }
  }

  private def processIndexables(indexables: Seq[LibraryMembershipIndexable]): Int = updateLock.synchronized {
    doUpdate(name)(indexables.iterator)
  }

  override def indexInfos(name: String): Seq[IndexInfo] = {
    super.indexInfos(this.name)
  }
}

class LibraryMembershipIndexerActor @Inject() (
    airbrake: AirbrakeNotifier,
    indexer: LibraryMembershipIndexer) extends CoordinatingIndexerActor(airbrake, indexer) with Logging {

  protected def update(): Future[Boolean] = indexer.asyncUpdate()
}

trait LibraryMembershipIndexerPlugin extends IndexerPlugin[LibraryMembership, LibraryMembershipIndexer]

class LibraryMembershipIndexerPluginImpl @Inject() (
  actor: ActorInstance[LibraryMembershipIndexerActor],
  indexer: LibraryMembershipIndexer,
  airbrake: AirbrakeNotifier,
  serviceDiscovery: ServiceDiscovery,
  val scheduling: SchedulingProperties) extends IndexerPluginImpl(indexer, actor, serviceDiscovery) with LibraryMembershipIndexerPlugin
