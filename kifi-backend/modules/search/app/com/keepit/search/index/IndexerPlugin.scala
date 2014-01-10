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
import com.keepit.search.sharding.ShardedIndexer
import play.api.Play.current
import play.api.Plugin
import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration._

object IndexerPluginMessages {
  case object UpdateIndex
  case object BackUpIndex
}

trait IndexManager[T <: Indexer[_]] {
  def update(): Int
  def backup(): Unit
  def numDocs: Int
  def sequenceNumber: SequenceNumber
  def commitSequenceNumber: SequenceNumber
  def committedAt: Option[String]
  def refreshSearcher(): Unit
  def reindex(): Unit
  def close(): Unit
  def getIndexerFor(id: Long): T
}

trait IndexerPlugin[T <: Indexer[_]] extends SchedulerPlugin {
  def update(): Int
  def reindex()
  def refreshSearcher()
  def numDocs(): Int
  def sequenceNumber: SequenceNumber
  def commitSequenceNumber: SequenceNumber
  def committedAt: Option[String]
  def getIndexerFor(id: Long): T
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
    serviceDiscovery.thisInstance.filter(_.remoteService.healthyStatus == ServiceStatus.BACKING_UP).foreach { _ =>
      scheduleTaskOnAllMachines(actor.system, 30 minutes, 2 hours, actor.ref, BackUpIndex)
    }
  }
  override def onStop() {
    log.info(s"stopping $name")
    indexer.close()
  }

  def update(): Int = {
    val future = actor.ref.ask(UpdateIndex)(1 minutes).mapTo[Int]
    Await.result(future, 1 minutes)
  }

  override def reindex(): Unit = {
    indexer.reindex()
    actor.ref ! UpdateIndex
  }

  override def refreshSearcher(): Unit = {
    indexer.refreshSearcher()
  }

  override def numDocs: Int = indexer.numDocs

  override def sequenceNumber: SequenceNumber = indexer.sequenceNumber

  override def commitSequenceNumber: SequenceNumber = indexer.commitSequenceNumber

  override def committedAt: Option[String] = indexer.committedAt

  def getIndexerFor(id: Long): T = indexer.getIndexerFor(id)
}

class IndexerActor[T <: Indexer[_]](
  airbrake: AirbrakeNotifier,
  indexer: IndexManager[T]
) extends FortyTwoActor(airbrake) with Logging {

  import IndexerPluginMessages._

  def receive() = {
    case UpdateIndex => try {
        sender ! indexer.update()
      } catch {
        case e: Exception =>
          airbrake.notify(s"Error in indexing [${indexer.getClass.toString}]", e)
          sender ! -1
      }
    case BackUpIndex => indexer.backup()
    case m => throw new UnsupportedActorMessage(m)
  }
}

