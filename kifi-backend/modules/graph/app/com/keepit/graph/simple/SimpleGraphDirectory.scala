package com.keepit.graph.simple

import com.keepit.graph.manager.{ArchivedGraphDirectory, GraphStore, GraphUpdaterState, GraphDirectory}
import com.keepit.common.BackedUpDirectory
import java.io.File
import org.apache.commons.io.FileUtils
import play.api.libs.json.{JsNumber, Json}
import com.keepit.common.logging.Logging

trait SimpleGraphDirectory extends GraphDirectory with BackedUpDirectory {
  def load(): (SimpleGraph, GraphUpdaterState)
  def persist(graph: SimpleGraph, state: GraphUpdaterState): Unit
}

class ArchivedSimpleGraphDirectory(dir: File, protected val store: GraphStore) extends SimpleGraphDirectory with ArchivedGraphDirectory {
  def getDirectory() = dir

  private val temp: File = {
    val tempDir = new File(getDirectory(), "temp").getCanonicalFile
    FileUtils.deleteDirectory(tempDir)
    FileUtils.forceMkdir(tempDir)
    tempDir
  }

  def load(): (SimpleGraph, GraphUpdaterState) = this.synchronized {
    val graphFile = getGraphFile()
    val stateFile = getStateFile()
    val checksumFile = getChecksumFile()
    val expectedChecksum = loadChecksum(checksumFile)
    val actualChecksum = computeChecksum(graphFile, stateFile)
    require(actualChecksum == expectedChecksum, "Cannot load graph from disk: files are corrupt.")
    (loadGraph(graphFile), loadState(stateFile))
  }

  def persist(graph: SimpleGraph, state: GraphUpdaterState): Unit = this.synchronized {
    val tempGraphFile = persistGraph(graph)
    val tempStateFile = persistState(state)
    val tempChecksumFile = persistChecksum(tempGraphFile, tempStateFile)

    val graphFile = getGraphFile()
    val stateFile = getStateFile()
    val checksumFile = getChecksumFile()

    graphFile.delete()
    stateFile.delete()
    checksumFile.delete()

    FileUtils.moveFile(tempGraphFile, graphFile)
    FileUtils.moveFile(tempStateFile, stateFile)
    FileUtils.moveFile(tempChecksumFile, checksumFile)
  }

  private def persistGraph(graph: SimpleGraph): File = {
    val json = SimpleGraph.format.writes(graph)
    val tempGraphFile = new File(temp, "graph_" + graph.hashCode())
    FileUtils.writeStringToFile(tempGraphFile, Json.stringify(json))
    tempGraphFile
  }

  private def loadGraph(graphFile: File): SimpleGraph = {
    val json = Json.parse(FileUtils.readFileToString(graphFile))
    SimpleGraph.format.reads(json).get
  }

  private def persistState(state: GraphUpdaterState): File = {
    val json = GraphUpdaterState.format.writes(state)
    val tempStateFile = new File(temp, "state_" + state.hashCode())
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

  private def persistChecksum(graphFile: File, stateFile: File): File = {
    val checksum = computeChecksum(graphFile, stateFile)
    val tempChecksumFile = new File(temp, "checksum_" + checksum.hashCode())
    FileUtils.writeStringToFile(tempChecksumFile, Json.stringify(JsNumber(checksum)))
    tempChecksumFile
  }

  private def loadChecksum(checksumFile: File): Long = {
    val json = Json.parse(FileUtils.readFileToString(checksumFile))
    val checksum = json.as[Long]
    checksum
  }

  private def getStateFile(): File = new File(getDirectory(), "state")
  private def getGraphFile(): File = new File(getDirectory(), "graph")
  private def getChecksumFile(): File = new File(getDirectory(), "checksum")
}

class RatherUselessSimpleGraphDirectory extends SimpleGraphDirectory with Logging {
  def persist(graph: SimpleGraph, state: GraphUpdaterState): Unit = {}
  def load(): (SimpleGraph, GraphUpdaterState) = throw new UnsupportedOperationException("Cannot load anything from RatherUselessSimpleGraphDirectory.")
  def scheduleBackup(): Unit = log.warn("Cannot schedule backup of RatherUselessSimpleGraphDirectory")
  def cancelBackup(): Unit = log.warn("Cannot cancel backup of RatherUselessSimpleGraphDirectory")
  def doBackup(): Boolean = false
  def restoreFromBackup(): Unit = log.warn("Cannot restore RatherUselessSimpleGraphDirectory")
}
