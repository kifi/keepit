package com.keepit.graph.simple

import com.keepit.graph.model._
import com.keepit.graph.manager._
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.common.service.ServiceStatus
import play.modules.statsd.api.Statsd
import org.apache.commons.io.FileUtils
import com.keepit.common.logging.Logging

class SimpleGraphManager(
  val simpleGraph: SimpleGraph,
  var state: GraphUpdaterState,
  graphDirectory: SimpleGraphDirectory,
  graphUpdater: GraphUpdater,
  serviceDiscovery: ServiceDiscovery
) extends GraphManager with Logging {

  def readOnly[T](f: GraphReader => T): T = simpleGraph.readOnly(f)

  private def currentHeapSize(): Long = {
    val r = Runtime.getRuntime
    r.totalMemory() - r.freeMemory()
  }

  def backup(): Unit = {
    val runningHeapSize = currentHeapSize()
    log.info(s"Persisting SimpleGraph - Current Heap Size: ${FileUtils.byteCountToDisplaySize(runningHeapSize)}")
    val start = System.currentTimeMillis
    simpleGraph.synchronized { graphDirectory.persist(simpleGraph, state) }
    val end = System.currentTimeMillis
    val persistingHeapSize = currentHeapSize()
    log.info(s"Simple Graph has been persisted in ${ (end - start) / 1000} seconds - Current Heap Size: ${FileUtils.byteCountToDisplaySize(persistingHeapSize)}")
    if (serviceDiscovery.myStatus.exists(_ == ServiceStatus.BACKING_UP)) {
      val start = System.currentTimeMillis
      graphDirectory.synchronized {
        graphDirectory.doBackup()
        graphDirectory.asFile.foreach { dir =>
          Statsd.gauge("graph.directory.size", FileUtils.sizeOfDirectory(dir))
          Statsd.gauge("graph.heap.running", runningHeapSize)
          Statsd.gauge("graph.heap.persisting", persistingHeapSize)
        }
      }
      val end = System.currentTimeMillis
      log.info(s"Simple Graph directory has been backed up in ${ (end - start) / 1000} seconds")
    }
  }

  def update(updates: GraphUpdate*): Unit = {
    val relevantUpdates = updates.filter { graphUpdate => graphUpdate.seq > state.getCurrentSequenceNumber(graphUpdate.kind) }
    simpleGraph.readWrite { implicit writer => relevantUpdates.sortBy(_.seq.value).foreach(graphUpdater(_)) }
    state = state.withUpdates(relevantUpdates) // todo(Léo): not threadsafe
  }

  def statistics: GraphStatistics = simpleGraph.statistics
}
