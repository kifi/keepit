package com.keepit.search.graph.library

import com.keepit.search.index._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.akka.UnsupportedActorMessage
import com.keepit.common.logging.Logging
import com.keepit.model.Library
import com.google.inject.Inject
import com.keepit.common.actor.ActorInstance
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.common.plugin.SchedulingProperties
import scala.util.Success
import scala.util.Failure

trait LibraryIndexerPlugin extends IndexerPlugin[Library, LibraryIndexer]

class LibraryIndexerPluginImpl @Inject() (
  actor: ActorInstance[LibraryIndexerActor],
  indexer: LibraryIndexer,
  airbrake: AirbrakeNotifier,
  serviceDiscovery: ServiceDiscovery,
  val scheduling: SchedulingProperties) extends IndexerPluginImpl(indexer, actor, serviceDiscovery) with LibraryIndexerPlugin

class LibraryIndexerActor @Inject() (
    airbrake: AirbrakeNotifier,
    indexer: LibraryIndexer) extends IndexerActor(airbrake, indexer) with Logging {

  import IndexerPluginMessages._

  private case class ProcessUpdates(updates: Seq[Indexable[Library, Library]], fetchSize: Int)
  private case object CancelUpdate

  private var updating = false

  implicit val executionContext = com.keepit.common.concurrent.ExecutionContext.immediate

  private def update(): Unit = if (!updating) {
    updating = true
    val fetchSize = indexer.commitBatchSize
    indexer.fetchUpdates(indexer.sequenceNumber, fetchSize).onComplete {
      case Success(updates) =>
        self ! ProcessUpdates(updates, fetchSize)
      case Failure(ex) =>
        airbrake.notify(s"Failed to fetch updates for ${indexer}", ex)
        self ! CancelUpdate
    }
  }

  private def cancelUpdate(): Unit = { updating = false }

  override def receive() = {
    case UpdateIndex => update()
    case CancelUpdate => cancelUpdate()
    case ProcessUpdates(updates, fetchSize) => {
      if (updates.nonEmpty) { indexer.processUpdates(updates) }
      if (updates.length < fetchSize) { cancelUpdate() }
      else { update() }
    }
    case BackUpIndex => {
      val minBackupInterval = 600000 // 10 minutes
      if (System.currentTimeMillis - indexer.lastBackup > minBackupInterval) indexer.backup()
    }
    case RefreshSearcher => indexer.refreshSearcher()
    case RefreshWriter => indexer.refreshWriter()
    case WarmUpIndexDirectory => indexer.warmUpIndexDirectory()
    case m => throw new UnsupportedActorMessage(m)
  }
}
