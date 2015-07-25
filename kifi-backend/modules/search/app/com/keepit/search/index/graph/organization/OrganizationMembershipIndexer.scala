package com.keepit.search.index.graph.organization

import com.google.inject.Inject
import com.keepit.common.actor.ActorInstance
import com.keepit.common.db.SequenceNumber
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.plugin.SchedulingProperties
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.model.OrganizationMembership
import com.keepit.search.index._
import com.keepit.shoebox.ShoeboxServiceClient

import scala.concurrent.Future

class OrganizationMembershipIndexer(indexDirectory: IndexDirectory, shoebox: ShoeboxServiceClient, val airbrake: AirbrakeNotifier) extends Indexer[OrganizationMembership, OrganizationMembership, OrganizationMembershipIndexer](indexDirectory) {
  val name = "OrganizationMembershipIndexer"

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

  private def fetchIndexables(seq: SequenceNumber[OrganizationMembership], fetchSize: Int): Future[(Seq[OrganizationMembershipIndexable], Boolean)] = {
    shoebox.getIngestableOrganizationMemberships(seq, fetchSize).map { updates =>
      val indexables = updates.map { membership => new OrganizationMembershipIndexable(membership) }
      val exhausted = updates.length < fetchSize
      (indexables, exhausted)
    }
  }

  private def processIndexables(indexables: Seq[OrganizationMembershipIndexable]): Int = updateLock.synchronized {
    doUpdate(name)(indexables.iterator)
  }

  override def indexInfos(name: String): Seq[IndexInfo] = {
    super.indexInfos(this.name)
  }
}

class OrganizationMembershipIndexerActor @Inject() (
    airbrake: AirbrakeNotifier,
    indexer: OrganizationMembershipIndexer) extends CoordinatingIndexerActor(airbrake, indexer) with Logging {

  protected def update(): Future[Boolean] = indexer.asyncUpdate()
}

trait OrganizationMembershipIndexerPlugin extends IndexerPlugin[OrganizationMembership, OrganizationMembershipIndexer]

class OrganizationMembershipIndexerPluginImpl @Inject() (
  actor: ActorInstance[OrganizationMembershipIndexerActor],
  indexer: OrganizationMembershipIndexer,
  airbrake: AirbrakeNotifier,
  serviceDiscovery: ServiceDiscovery,
  val scheduling: SchedulingProperties) extends IndexerPluginImpl(indexer, actor, serviceDiscovery) with OrganizationMembershipIndexerPlugin
