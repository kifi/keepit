package com.keepit.search.index

import java.util.concurrent.atomic.AtomicInteger

import akka.actor._
import akka.util.Timeout
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.akka.UnsupportedActorMessage
import com.keepit.common.db.SequenceNumber
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.actor.ActorInstance
import com.keepit.common.util.RecurringTaskManager
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.common.plugin.SchedulerPlugin
import com.keepit.search.index.Indexer.CommitData
import scala.concurrent.duration._
import java.util.Random
import scala.concurrent.Future
import scala.util.{ Failure, Success }

object IndexerPluginMessages {
  case object UpdateIndex
  case object BackUpIndex
  case object RefreshSearcher
  case object RefreshWriter
  case object WarmUpIndexDirectory
  case object Close
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

  val airbrake: AirbrakeNotifier
  private[this] val updateTaskManager = new IndexUpdateTaskManager[S, I](this, airbrake)
  def updateAsync(): Unit = updateTaskManager.request()
}

class IndexUpdateTaskManager[S, I <: Indexer[_, S, I]](indexer: IndexManager[S, I], airbrake: AirbrakeNotifier) extends RecurringTaskManager {
  private[this] val errorCount = new AtomicInteger(0)

  override def doTask(): Unit = {
    indexer.update()
    errorCount.set(0)
  }

  override def onError(e: Throwable): Unit = {
    if (errorCount.getAndIncrement() > 0) { // ignore the first error (shoebox deployment may be going on)
      airbrake.notify(s"Error in indexing [${indexer.getClass.toString}]", e)
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
    serviceDiscovery: ServiceDiscovery) extends IndexerPlugin[S, I] {

  import IndexerPluginMessages._

  val name: String = getClass.toString

  implicit val actorTimeout = Timeout(5 seconds)

  // plugin lifecycle methods
  override def enabled: Boolean = true

  val indexingInterval = 1 minute

  override def onStart() {
    val rnd = new Random

    scheduleTaskOnAllMachines(actor.system, (60 + rnd.nextInt(60)) seconds, indexingInterval, actor.ref, UpdateIndex)

    if (serviceDiscovery.hasBackupCapability) scheduleTaskOnAllMachines(actor.system, 30 minutes, 2 hours, actor.ref, BackUpIndex)
    else {
      // regular search instance
      actor.ref ! WarmUpIndexDirectory
      scheduleTaskOnAllMachines(actor.system, 6 hours, 6 hours, actor.ref, WarmUpIndexDirectory)
    }
  }

  override def onStop() {
    actor.ref ! Close
    indexer.close()
    super.onStop()
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

sealed abstract class IndexerActor[S, I <: Indexer[_, S, I]](airbrake: AirbrakeNotifier) extends FortyTwoActor(airbrake)

class BasicIndexerActor[S, I <: Indexer[_, S, I]](
    airbrake: AirbrakeNotifier,
    indexer: IndexManager[S, I]) extends IndexerActor[S, I](airbrake) {

  import IndexerPluginMessages._

  private[this] var isClosing = false

  def receive() = {
    case UpdateIndex =>
      if (!isClosing) indexer.updateAsync()
    case BackUpIndex => {
      val minBackupInterval = 600000 // 10 minutes
      if (System.currentTimeMillis - indexer.lastBackup > minBackupInterval) indexer.backup()
    }
    case RefreshSearcher => indexer.refreshSearcher()
    case RefreshWriter => indexer.refreshWriter()
    case WarmUpIndexDirectory => indexer.warmUpIndexDirectory()
    case Close => isClosing = true
    case m => throw new UnsupportedActorMessage(m)
  }
}

// todo(Léo): Switch all indexers to using a coordinating actor *internally* to manage their state
// todo(Léo): Expose indexers instead of multiple plugins for API calls, have a single plugin that schedules all of them

abstract class CoordinatingIndexerActor[S, I <: Indexer[_, S, I]](
    airbrake: AirbrakeNotifier,
    indexer: IndexManager[S, I]) extends IndexerActor[S, I](airbrake) {

  protected def update(): Future[Boolean]

  import IndexerPluginMessages._

  private case class SuccessfulUpdate(done: Boolean)
  private case class FailedUpdate(ex: Throwable)

  @volatile private[this] var updating = false

  implicit val executionContext = com.keepit.common.concurrent.ExecutionContext.immediate

  private def startUpdate(): Unit = {
    updating = true
    update().onComplete {
      case Success(done) => self ! SuccessfulUpdate(done)
      case Failure(ex) => self ! FailedUpdate(ex)
    }
  }

  private def endUpdate(): Unit = { updating = false }

  private[this] var isClosing = false
  private[this] val errorCount = new AtomicInteger(0)

  override def receive() = {
    case UpdateIndex => if (!isClosing && !updating) { startUpdate() }
    case FailedUpdate(ex) => {
      if (errorCount.getAndIncrement() > 0) { // ignore the first error (shoebox deployment may be going on)
        airbrake.notify(s"Failed to update index $indexer", ex)
      }
      endUpdate()
    }
    case SuccessfulUpdate(done) => {
      errorCount.set(0)
      if (isClosing || done) { endUpdate() }
      else { startUpdate() }
    }
    case BackUpIndex => {
      val minBackupInterval = 600000 // 10 minutes
      if (System.currentTimeMillis - indexer.lastBackup > minBackupInterval) indexer.backup()
    }
    case RefreshSearcher => indexer.refreshSearcher()
    case RefreshWriter => indexer.refreshWriter()
    case WarmUpIndexDirectory => indexer.warmUpIndexDirectory()
    case Close => isClosing = true
    case m => throw new UnsupportedActorMessage(m)
  }
}
