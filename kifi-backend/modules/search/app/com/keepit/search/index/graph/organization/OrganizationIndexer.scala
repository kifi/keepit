package com.keepit.search.index.graph.organization

import com.google.inject.Inject
import com.keepit.common.actor.ActorInstance
import com.keepit.common.db.SequenceNumber
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.plugin.SchedulingProperties
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.model.Organization
import com.keepit.search.index._
import com.keepit.shoebox.ShoeboxServiceClient

import scala.concurrent.Future

class OrganizationIndexer(indexDirectory: IndexDirectory, shoebox: ShoeboxServiceClient, val airbrake: AirbrakeNotifier) extends Indexer[Organization, Organization, OrganizationIndexer](indexDirectory, OrganizationFields.maxPrefixLength) {
  val name = "OrganizationIndexer"

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

  private def fetchIndexables(seq: SequenceNumber[Organization], fetchSize: Int): Future[(Seq[OrganizationIndexable], Boolean)] = {
    shoebox.getIngestableOrganizations(seq, fetchSize).map { updates =>
      val indexables = updates.map { org => new OrganizationIndexable(org) }
      val exhausted = updates.length < fetchSize
      (indexables, exhausted)
    }
  }

  private def processIndexables(indexables: Seq[OrganizationIndexable]): Int = updateLock.synchronized {
    doUpdate(name)(indexables.iterator)
  }

  override def indexInfos(name: String): Seq[IndexInfo] = {
    super.indexInfos(this.name)
  }
}

class OrganizationIndexerActor @Inject() (
    airbrake: AirbrakeNotifier,
    indexer: OrganizationIndexer) extends CoordinatingIndexerActor(airbrake, indexer) with Logging {

  protected def update(): Future[Boolean] = indexer.asyncUpdate()
}

trait OrganizationIndexerPlugin extends IndexerPlugin[Organization, OrganizationIndexer]

class OrganizationIndexerPluginImpl @Inject() (
  actor: ActorInstance[OrganizationIndexerActor],
  indexer: OrganizationIndexer,
  airbrake: AirbrakeNotifier,
  serviceDiscovery: ServiceDiscovery,
  val scheduling: SchedulingProperties) extends IndexerPluginImpl(indexer, actor, serviceDiscovery) with OrganizationIndexerPlugin
