package com.keepit.search.index

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.akka.UnsupportedActorMessage
import com.keepit.common.db.SequenceNumber
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.actor.ActorInstance
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.common.plugin.SchedulerPlugin
import com.keepit.common.plugin.SchedulingProperties
import com.keepit.common.service.ServiceStatus
import play.api.Play.current
import play.api.Plugin
import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration._
import com.keepit.search.IndexInfo
import java.util.concurrent.atomic.AtomicBoolean

object IndexerPluginMessages {
  case object UpdateIndex
  case object BackUpIndex
  case object RefreshSearcher
  case object WarmUpIndexDirectory
}

trait IndexManager[T <: Indexer[_]] {
  def update(): Int
  def backup(): Unit
  def numDocs: Int
  def sequenceNumber: SequenceNumber
  def commitSequenceNumber: SequenceNumber
  def committedAt: Option[String]
  def refreshSearcher(): Unit
  def warmUpIndexDirectory(): Unit
  def reindex(): Unit
  def close(): Unit
  def indexInfos(name: String): Seq[IndexInfo]

  val pendingUpdateReq = new AtomicBoolean(false)
}

trait IndexerPlugin[T <: Indexer[_]] extends SchedulerPlugin {
  def update()
  def reindex()
  def refreshSearcher()
  def warmUpIndexDirectory()
  def numDocs(): Int
  def sequenceNumber: SequenceNumber
  def commitSequenceNumber: SequenceNumber
  def committedAt: Option[String]
  def indexInfos: Seq[IndexInfo]
}

abstract class IndexerPluginImpl[T <: Indexer[_], A <: IndexerActor[T]](
  indexer: IndexManager[T],
  actor: ActorInstance[A],
  serviceDiscovery: ServiceDiscovery
) extends IndexerPlugin[T] {

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
    if (indexer.pendingUpdateReq.compareAndSet(false, true)) {
      actor.ref ! UpdateIndex
    }
  }

  override def reindex(): Unit = {
    log.info(s"reindexing $name")
    indexer.reindex()
    actor.ref ! UpdateIndex
  }

  override def refreshSearcher(): Unit = {
    actor.ref ! RefreshSearcher
  }

  override def warmUpIndexDirectory(): Unit = {
    actor.ref ! WarmUpIndexDirectory
  }

  override def numDocs: Int = indexer.numDocs

  override def sequenceNumber: SequenceNumber = indexer.sequenceNumber

  override def commitSequenceNumber: SequenceNumber = indexer.commitSequenceNumber

  override def committedAt: Option[String] = indexer.committedAt

  def indexInfos: Seq[IndexInfo] = indexer.indexInfos("")
}

class IndexerActor[T <: Indexer[_]](
  airbrake: AirbrakeNotifier,
  indexer: IndexManager[T]
) extends FortyTwoActor(airbrake) with Logging {

  import IndexerPluginMessages._

  def receive() = {
    case UpdateIndex => try {
        indexer.pendingUpdateReq.set(false)
        indexer.update()
      } catch {
        case e: Exception =>
          airbrake.notify(s"Error in indexing [${indexer.getClass.toString}]", e)
      }
    case BackUpIndex => indexer.backup()
    case RefreshSearcher => indexer.refreshSearcher()
    case WarmUpIndexDirectory => indexer.warmUpIndexDirectory()
    case m => throw new UnsupportedActorMessage(m)
  }
}

