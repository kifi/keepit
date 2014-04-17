package com.keepit.graph.simple

import com.keepit.graph.model._
import com.keepit.graph.manager._
import play.api.libs.json.{JsNumber, Json}
import java.io.File
import org.apache.commons.io.FileUtils
import com.google.inject.Inject
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.common.service.ServiceStatus

class SimpleGraphManager @Inject() (
  val simpleGraph: SimpleGraph,
  var state: GraphUpdaterState,
  graphDirectory: GraphDirectory,
  graphUpdater: GraphUpdater,
  serviceDiscovery: ServiceDiscovery
) extends GraphManager {

  def readOnly[T](f: GraphReader => T): T = simpleGraph.readOnly(f)

  def backup(): Unit = {
    simpleGraph.synchronized { SimpleGraphManager.persist(simpleGraph, state, graphDirectory) }
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

object SimpleGraphManager {

  def load(graphDirectory: GraphDirectory): (SimpleGraph, GraphUpdaterState) = graphDirectory.synchronized {
    val graphFile = getGraphFile(graphDirectory)
    val stateFile = getStateFile(graphDirectory)
    val checksumFile = getChecksumFile(graphDirectory)
    val expectedChecksum = loadChecksum(checksumFile)
    val actualChecksum = computeChecksum(graphFile, stateFile)
    require(actualChecksum == expectedChecksum, "Cannot load graph from disk: files are corrupt.")
    (loadGraph(graphFile), loadState(stateFile))
  }

  def persist(graph: SimpleGraph, state: GraphUpdaterState, graphDirectory: GraphDirectory): Unit = graphDirectory.synchronized {
    val tempGraphFile = persistGraph(graph, graphDirectory: GraphDirectory)
    val tempStateFile = persistState(state, graphDirectory: GraphDirectory)
    val tempChecksumFile = persistChecksum(tempGraphFile, tempStateFile, graphDirectory)

    val graphFile = getGraphFile(graphDirectory)
    val stateFile = getStateFile(graphDirectory)
    val checksumFile = getChecksumFile(graphDirectory)

    graphFile.delete()
    stateFile.delete()
    checksumFile.delete()

    FileUtils.moveFile(tempGraphFile, graphFile)
    FileUtils.moveFile(tempStateFile, stateFile)
    FileUtils.moveFile(tempChecksumFile, checksumFile)
  }

  private def persistGraph(graph: SimpleGraph, graphDirectory: GraphDirectory): File = {
    val json = SimpleGraph.format.writes(graph)
    val tempGraphFile = new File(graphDirectory.temp, "graph_" + graph.hashCode())
    FileUtils.writeStringToFile(tempGraphFile, Json.stringify(json))
    tempGraphFile
  }

  private def loadGraph(graphFile: File): SimpleGraph = {
    val json = Json.parse(FileUtils.readFileToString(graphFile))
    SimpleGraph.format.reads(json).get
  }

  private def persistState(state: GraphUpdaterState, graphDirectory: GraphDirectory): File = {
    val json = GraphUpdaterState.format.writes(state)
    val tempStateFile = new File(graphDirectory.temp, "state_" + state.hashCode())
    FileUtils.writeStringToFile(tempStateFile, Json.stringify(json))
    tempStateFile
  }

  private def loadState(stateFile: File): GraphUpdaterState = {
    val json = Json.parse(FileUtils.readFileToString(stateFile))
    GraphUpdaterState.format.reads(json).get
  }

  private def computeChecksum(graphFile: File, stateFile: File): Long = {
    val graphChecksum = FileUtils.checksumCRC32(graphFile)
    val stateChecksum = FileUtils.checksumCRC32(stateFile)
    graphChecksum ^ stateChecksum
  }

  private def persistChecksum(graphFile: File, stateFile: File, graphDirectory: GraphDirectory): File = {
    val checksum = computeChecksum(graphFile, stateFile)
    val tempChecksumFile = new File(graphDirectory.temp, "checksum_" + checksum.hashCode())
    FileUtils.writeStringToFile(tempChecksumFile, Json.stringify(JsNumber(checksum)))
    tempChecksumFile
  }

  private def loadChecksum(checksumFile: File): Long = {
    val json = Json.parse(FileUtils.readFileToString(checksumFile))
    val checksum = json.as[Long]
    checksum
  }

  private def getStateFile(graphDirectory: GraphDirectory): File = new File(graphDirectory.getDirectory(), "state")
  private def getGraphFile(graphDirectory: GraphDirectory): File = new File(graphDirectory.getDirectory(), "graph")
  private def getChecksumFile(graphDirectory: GraphDirectory): File = new File(graphDirectory.getDirectory(), "checksum")
}
