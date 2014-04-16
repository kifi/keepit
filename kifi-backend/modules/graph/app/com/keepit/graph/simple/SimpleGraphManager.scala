package com.keepit.graph.simple

import com.keepit.graph.model._
import com.keepit.graph.manager._
import play.api.libs.json.Json
import java.io.File
import org.apache.commons.io.FileUtils

class SimpleGraphManager(
  val simpleGraph: SimpleGraph,
  var state: GraphUpdaterState,
  graphDirectory: GraphDirectory,
  graphUpdater: GraphUpdater
) extends GraphManager {

  def readOnly[T](f: GraphReader => T): T = simpleGraph.readOnly(f)

  def backup(): Unit = {
    simpleGraph.synchronized { SimpleGraphManager.persist(simpleGraph, state, graphDirectory) }
    graphDirectory.synchronized { graphDirectory.doBackup() }
  }

  def update(updates: GraphUpdate*): GraphUpdaterState = {
    val relevantUpdates = updates.filter { graphUpdate => graphUpdate.seq > state.getCurrentSequenceNumber(graphUpdate.kind) }
    simpleGraph.readWrite { implicit writer => relevantUpdates.sortBy(_.seq.value).foreach(graphUpdater(_)) }
    state = state.withUpdates(relevantUpdates) // todo(Léo): not threadsafe, should add transaction callback capabilities to GraphWriter (cf SessionWrapper)
    state
  }
}

object SimpleGraphManager {

  def load(graphDirectory: GraphDirectory): (SimpleGraph, GraphUpdaterState) = (loadGraph(graphDirectory), loadState(graphDirectory))
  def persist(simpleGraph: SimpleGraph, state: GraphUpdaterState, graphDirectory: GraphDirectory): Unit = graphDirectory.synchronized {
    persistGraph(simpleGraph, graphDirectory: GraphDirectory)
    persistState(state, graphDirectory: GraphDirectory)
  }

  private def getStateFile(graphDirectory: GraphDirectory): File = new File(graphDirectory.getDirectory(), "state")
  private def getSimpleGraphFile(graphDirectory: GraphDirectory): File = new File(graphDirectory.getDirectory(), "graph")

  private def loadGraph(graphDirectory: GraphDirectory): SimpleGraph = {
    val json = Json.parse(FileUtils.readFileToString(getSimpleGraphFile(graphDirectory)))
    SimpleGraph.format.reads(json).get
  }

  private def loadState(graphDirectory: GraphDirectory): GraphUpdaterState = {
    val json = Json.parse(FileUtils.readFileToString(getStateFile(graphDirectory)))
    GraphUpdaterState.format.reads(json).get
  }

  private def persistGraph(simpleGraph: SimpleGraph, graphDirectory: GraphDirectory): Unit = {
    val json = SimpleGraph.format.writes(simpleGraph)
    FileUtils.writeStringToFile(SimpleGraphManager.getSimpleGraphFile(graphDirectory), Json.stringify(json))
  }

  private def persistState(state: GraphUpdaterState, graphDirectory: GraphDirectory): Unit = {
    val json = GraphUpdaterState.format.writes(state)
    FileUtils.writeStringToFile(SimpleGraphManager.getStateFile(graphDirectory), Json.stringify(json))
  }
}
