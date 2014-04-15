package com.keepit.graph.ingestion

import com.keepit.graph.model.GraphManager
import java.io.File
import org.apache.commons.io.FileUtils
import play.api.libs.json.Json
import com.keepit.graph.simple.SimpleGraph
import com.keepit.common.BackedUpDirectory

trait GraphDirectory[G <: GraphManager] extends BackedUpDirectory {

  def persistGraph(graph: G): Unit
  def loadGraph(): G

  protected def getStateFile(): File = new File(getDirectory(), "state")

  def persistState(state: GraphUpdaterState): Unit = {
    val json = GraphUpdaterState.format.writes(state)
    FileUtils.writeStringToFile(getStateFile(), Json.stringify(json))
  }

  def loadState(): GraphUpdaterState = {
    val json = Json.parse(FileUtils.readFileToString(getStateFile()))
    GraphUpdaterState.format.reads(json).get
  }
}

trait SimpleGraphDirectory extends GraphDirectory[SimpleGraph] {

  protected def getSimpleGraphFile(): File = new File(getDirectory(), "simpleGraph")

  def persistGraph(graph: SimpleGraph): Unit = {
    val json = SimpleGraph.format.writes(graph)
    FileUtils.writeStringToFile(getStateFile(), Json.stringify(json))
  }

  def loadGraph(): SimpleGraph = {
    val json = Json.parse(FileUtils.readFileToString(getSimpleGraphFile()))
    SimpleGraph.format.reads(json).get
  }
}