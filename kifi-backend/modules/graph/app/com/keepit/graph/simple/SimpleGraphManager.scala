package com.keepit.graph.simple

import com.keepit.graph.model._
import com.keepit.graph.manager._
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.common.service.ServiceStatus
import play.modules.statsd.api.Statsd
import org.apache.commons.io.FileUtils
import com.keepit.common.logging.Logging
import com.keepit.common.healthcheck.AirbrakeNotifier

class SimpleGraphManager(
    val simpleGraph: SimpleGraph,
    var state: GraphUpdaterState,
    graphDirectory: SimpleGraphDirectory,
    graphUpdater: GraphUpdater,
    serviceDiscovery: ServiceDiscovery,
    airbrake: AirbrakeNotifier) extends GraphManager with Logging {

  def readOnly[T](f: GraphReader => T): T = simpleGraph.readOnly(f)

  private def currentHeapSize(): Long = {
    val r = Runtime.getRuntime
    r.totalMemory() - r.freeMemory()
  }

  def backup(): Unit = try {
    val runningHeapSize = currentHeapSize()
    log.info(s"Persisting SimpleGraph - Current Heap Size: ${FileUtils.byteCountToDisplaySize(runningHeapSize)}")
    val start = System.currentTimeMillis
    simpleGraph.synchronized { graphDirectory.persist(simpleGraph, state) }
    val end = System.currentTimeMillis
    val persistingHeapSize = currentHeapSize()
    log.info(s"Simple Graph has been persisted in ${(end - start) / 1000} seconds - Current Heap Size: ${FileUtils.byteCountToDisplaySize(persistingHeapSize)}")
    if (serviceDiscovery.thisInstance.exists(_.instanceInfo.capabilities.contains("backup"))) {
      val start = System.currentTimeMillis
      graphDirectory.synchronized {
        graphDirectory.doBackup()
        graphDirectory.asFile.foreach { dir =>
          statsd.gauge("graph.directory.size", FileUtils.sizeOfDirectory(dir))
          statsd.gauge("graph.heap.running", runningHeapSize)
          statsd.gauge("graph.heap.persisting", persistingHeapSize)
        }
      }
      val end = System.currentTimeMillis
      log.info(s"Simple Graph directory has been backed up in ${(end - start) / 1000} seconds")
    }
  } catch {
    case ex: Throwable =>
      log.error(s"Failed to backup SimpleGraph to disk - ${ex}")
      airbrake.notify("Failed to backup SimpleGraph to disk", ex)
  }

  def update(updates: GraphUpdate*): Unit = {
    val (relevantUpdates, irrelevantUpdates) = updates.partition { graphUpdate => graphUpdate.seq > state.getCurrentSequenceNumber(graphUpdate.kind) }
    if (irrelevantUpdates.nonEmpty) { airbrake.notify(new IrrelevantGraphUpdatesException(irrelevantUpdates)) }
    simpleGraph.readWrite { implicit writer => relevantUpdates.sortBy(_.seq.value).foreach(graphUpdater(_)) }
    state = state.withUpdates(relevantUpdates) // todo(Léo): not threadsafe
    log.info(s"Processed ${relevantUpdates.length} updates. Graph state:\n${state}")
  }

  def statistics: GraphStatistics = simpleGraph.statistics
}
