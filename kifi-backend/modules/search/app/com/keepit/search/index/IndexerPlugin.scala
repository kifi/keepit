package com.keepit.search.index

import akka.actor._
import akka.util.Timeout
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.akka.UnsupportedActorMessage
import com.keepit.common.db.SequenceNumber
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.actor.ActorInstance
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.common.plugin.SchedulerPlugin
import com.keepit.common.service.ServiceStatus
import com.keepit.search.IndexInfo
import scala.concurrent.Future
import scala.concurrent.duration._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import java.util.concurrent.atomic.AtomicInteger

object IndexerPluginMessages {
  case object UpdateIndex
  case object BackUpIndex
  case object RefreshSearcher
  case object RefreshWriter
  case object WarmUpIndexDirectory
}

trait IndexManager[S, I <: Indexer[_, S, I]] {
  def update(): Int
  def backup(): Unit
  def numDocs: Int
  def sequenceNumber: SequenceNumber[S]
  def commitSequenceNumber: SequenceNumber[S]
  def committedAt: Option[String]
  def refreshSearcher(): Unit
  def refreshWriter(): Unit
  def warmUpIndexDirectory(): Unit
  def reindex(): Unit
  def close(): Unit
  def indexInfos(name: String): Seq[IndexInfo]
  def lastBackup: Long

  private[this] val updateTaskManager = new IndexUpdateTaskManager[S, I]()
  def updateAsync(onError: Throwable=>Unit): Unit = updateTaskManager.requestUpdate(this, onError)
}

class IndexUpdateTaskManager[S, I <: Indexer[_, S, I]] {
  private[this] val state = new AtomicInteger(0) // 0: idle, 1: updating, 2-or-greater: pending

  def requestUpdate(indexer: IndexManager[S, I], onError: Throwable=>Unit) = {
    if (state.getAndAdd(2) == 0) {
      // the state was 0, we need to start the update task
      Future {
        try {
          while (state.decrementAndGet() > 0) {
            // the state was 2-or-greater, there are pending requests, start the update task
            // all pending requests are consumed by this
            state.set(1)
            indexer.update()
          }
        } catch {
          case e: Throwable =>
            state.set(0) // go to the idle state so that the next request will start the update task
            onError(e)
        }
      }
    }
  }
}

trait IndexerPlugin[S, I <: Indexer[_, S, I]] extends SchedulerPlugin {
  def update()
  def reindex()
  def refreshSearcher()
  def refreshWriter()
  def warmUpIndexDirectory()
  def numDocs(): Int
  def sequenceNumber: SequenceNumber[S]
  def commitSequenceNumber: SequenceNumber[S]
  def committedAt: Option[String]
  def indexInfos: Seq[IndexInfo]
}

abstract class IndexerPluginImpl[S, I <: Indexer[_, S, I], A <: IndexerActor[S, I]](
  indexer: IndexManager[S, I],
  actor: ActorInstance[A],
  serviceDiscovery: ServiceDiscovery
) extends IndexerPlugin[S, I] {

  import IndexerPluginMessages._

  val name: String = getClass.toString

  implicit val actorTimeout = Timeout(5 seconds)

  // plugin lifecycle methods
  override def enabled: Boolean = true

  override def onStart() {
    log.info(s"starting $name")
    scheduleTaskOnAllMachines(actor.system, 30 seconds, 1 minutes, actor.ref, UpdateIndex)

    serviceDiscovery.thisInstance.filter(_.remoteService.healthyStatus == ServiceStatus.BACKING_UP) match {
      case Some(_) => // search_backup
        scheduleTaskOnAllMachines(actor.system, 30 minutes, 2 hours, actor.ref, BackUpIndex)
      case None => // regular search instance
        actor.ref ! WarmUpIndexDirectory
        scheduleTaskOnAllMachines(actor.system, 6 hours, 6 hours, actor.ref, WarmUpIndexDirectory)
    }
  }

  override def onStop() {
    log.info(s"stopping $name")
    indexer.close()
  }

  def update(): Unit = {
    actor.ref ! UpdateIndex
  }

  override def reindex(): Unit = {
    log.info(s"reindexing $name")
    indexer.reindex()
    actor.ref ! UpdateIndex
  }

  override def refreshSearcher(): Unit = {
    actor.ref ! RefreshSearcher
  }

  override def refreshWriter(): Unit = {
    actor.ref ! RefreshWriter
  }

  override def warmUpIndexDirectory(): Unit = {
    actor.ref ! WarmUpIndexDirectory
  }

  override def numDocs: Int = indexer.numDocs

  override def sequenceNumber: SequenceNumber[S] = indexer.sequenceNumber

  override def commitSequenceNumber: SequenceNumber[S] = indexer.commitSequenceNumber

  override def committedAt: Option[String] = indexer.committedAt

  def indexInfos: Seq[IndexInfo] = indexer.indexInfos("")
}

class IndexerActor[S, I <: Indexer[_, S, I]](
  airbrake: AirbrakeNotifier,
  indexer: IndexManager[S, I]
) extends FortyTwoActor(airbrake) with Logging {

  import IndexerPluginMessages._

  def receive() = {
    case UpdateIndex =>
      indexer.updateAsync(
        onError = { e => airbrake.notify(s"Error in indexing [${indexer.getClass.toString}]", e) }
      )
    case BackUpIndex => {
      val minBackupInterval = 300000 // 5 minutes
      if (System.currentTimeMillis - indexer.lastBackup > minBackupInterval) indexer.backup()
    }
    case RefreshSearcher => indexer.refreshSearcher()
    case RefreshWriter => indexer.refreshWriter()
    case WarmUpIndexDirectory => indexer.warmUpIndexDirectory()
    case m => throw new UnsupportedActorMessage(m)
  }
}

