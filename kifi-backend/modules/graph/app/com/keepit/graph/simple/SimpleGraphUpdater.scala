package com.keepit.graph.simple

import java.io.File
import org.apache.commons.io.FileUtils
import play.api.libs.json.Json
import com.keepit.graph.ingestion.{GraphUpdaterState, GraphUpdateProcessor, GraphDirectory, GraphUpdater}
import com.google.inject.Inject

class SimpleGraphUpdater @Inject() (
  val graph: SimpleGraph,
  val graphDirectory: GraphDirectory,
  val processUpdate: GraphUpdateProcessor,
  val state: GraphUpdaterState
) extends GraphUpdater {

  type G = SimpleGraph

  val graphName = "simple_graph"

  private def getSimpleGraphFile(): File = new File(graphDirectory.getDirectory(), graphName)

  protected def persistGraph(graph: SimpleGraph): Unit = {
    val json = SimpleGraph.format.writes(graph)
    FileUtils.writeStringToFile(getSimpleGraphFile(), Json.stringify(json))
  }

  protected def loadGraph(): SimpleGraph = {
    val json = Json.parse(FileUtils.readFileToString(getSimpleGraphFile()))
    SimpleGraph.format.reads(json).get
  }

  def backup(): Unit = {
    graph.synchronized { persist(graph, state) }
    graphDirectory.synchronized { graphDirectory.doBackup() }
  }
}
