package com.keepit.graph.simple

import com.keepit.graph.model._
import com.keepit.graph.manager._
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.common.service.ServiceStatus

class SimpleGraphManager(
  val simpleGraph: SimpleGraph,
  var state: GraphUpdaterState,
  graphDirectory: SimpleGraphDirectory,
  graphUpdater: GraphUpdater,
  serviceDiscovery: ServiceDiscovery
) extends GraphManager {

  def readOnly[T](f: GraphReader => T): T = simpleGraph.readOnly(f)

  def backup(): Unit = {
    simpleGraph.synchronized { graphDirectory.persist(simpleGraph, state) }
    if (serviceDiscovery.thisInstance.exists(_.remoteService.healthyStatus == ServiceStatus.BACKING_UP)) {
      graphDirectory.synchronized { graphDirectory.doBackup() }
    }
  }

  def update(updates: GraphUpdate*): GraphUpdaterState = {
    val relevantUpdates = updates.filter { graphUpdate => graphUpdate.seq > state.getCurrentSequenceNumber(graphUpdate.kind) }
    simpleGraph.readWrite { implicit writer => relevantUpdates.sortBy(_.seq.value).foreach(graphUpdater(_)) }
    state = state.withUpdates(relevantUpdates) // todo(Léo): not threadsafe, should add transaction callback capabilities to GraphWriter (cf SessionWrapper)
    state
  }
}
