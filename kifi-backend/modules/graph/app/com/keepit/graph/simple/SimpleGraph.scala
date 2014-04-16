package com.keepit.graph.simple

import com.keepit.graph.model._
import scala.collection.concurrent.{Map => ConcurrentMap, TrieMap}
import play.api.libs.json._
import java.io.File
import org.apache.commons.io.FileUtils
import play.api.libs.json.JsArray
import com.keepit.graph.ingestion.{GraphUpdateProcessor, GraphUpdate, GraphDirectory, GraphUpdaterState}
import com.keepit.graph.GraphManager

class SimpleGraphManager(
  val simpleGraph: SimpleGraph,
  var state: GraphUpdaterState,
  graphDirectory: GraphDirectory,
  processUpdate: GraphUpdateProcessor
) extends GraphManager {

  private val reusableGraphReader = simpleGraph.getNewReader()

  def readOnly[T](f: GraphReader => T): T = f(reusableGraphReader)

  def backup(): Unit = {
    simpleGraph.synchronized {
      persistSimpleGraph(simpleGraph)
      persistState(state)
    }
    graphDirectory.synchronized { graphDirectory.doBackup() }
  }

  def update(updates: GraphUpdate*): GraphUpdaterState = {
    val relevantUpdates = updates.filter { update => update.seq > state.getCurrentSequenceNumber(update.kind) }
    simpleGraph.readWrite { implicit writer => relevantUpdates.sortBy(_.seq.value).foreach(processUpdate(_)) }
    state = state.withUpdates(relevantUpdates) // todo(Léo): add transaction callback capabilities to GraphWriter (cf SessionWrapper)
    state
  }

  protected def persistSimpleGraph(simpleGraph: SimpleGraph): Unit = {
    val json = SimpleGraph.format.writes(simpleGraph)
    FileUtils.writeStringToFile(SimpleGraphManager.getSimpleGraphFile(graphDirectory), Json.stringify(json))
  }

  protected def persistState(state: GraphUpdaterState): Unit = {
    val json = GraphUpdaterState.format.writes(state)
    FileUtils.writeStringToFile(SimpleGraphManager.getStateFile(graphDirectory), Json.stringify(json))
  }
}

object SimpleGraphManager {
  def getStateFile(graphDirectory: GraphDirectory): File = new File(graphDirectory.getDirectory(), "state")
  def getSimpleGraphFile(graphDirectory: GraphDirectory): File = new File(graphDirectory.getDirectory(), "graph")
  def load(graphDirectory: GraphDirectory): (SimpleGraph, GraphUpdaterState) = (loadVertices(graphDirectory), loadState(graphDirectory))

  private def loadVertices(graphDirectory: GraphDirectory): SimpleGraph = {
    val json = Json.parse(FileUtils.readFileToString(getSimpleGraphFile(graphDirectory)))
    SimpleGraph.format.reads(json).get
  }

  private def loadState(graphDirectory: GraphDirectory): GraphUpdaterState = {
    val json = Json.parse(FileUtils.readFileToString(getStateFile(graphDirectory)))
    GraphUpdaterState.format.reads(json).get
  }
}

case class SimpleGraph(vertices: ConcurrentMap[VertexId, MutableVertex] = TrieMap()) {
  def getNewReader(): GraphReader = new GraphReaderImpl(vertices)

  private def getNewWriter(): GraphWriter = {
    val bufferedVertices = new BufferedMap(vertices)
    new GraphWriterImpl(bufferedVertices)
  }

  def readWrite[T](f: GraphWriter => T): T = {
    val graphWriter = getNewWriter()
    val result = f(graphWriter)
    this.synchronized { graphWriter.commit() }
    result
  }
}

object SimpleGraph {
  implicit val format: Format[SimpleGraph] = new Format[SimpleGraph] {
    def writes(simpleGraph: SimpleGraph): JsValue = JsArray(simpleGraph.vertices.values.map(MutableVertex.format.writes).toSeq)
    def reads(json: JsValue): JsResult[SimpleGraph] = json.validate[JsArray].map { jsArray =>
      val mutableVertices = jsArray.value.map(_.as[MutableVertex])
      val vertices = TrieMap[VertexId, MutableVertex]()
      vertices ++= mutableVertices.map { mutableVertex =>
        val vertexData = mutableVertex.data
        (VertexId(vertexData.id)(vertexData.kind) -> mutableVertex)
      }
      SimpleGraph(vertices)
    }
  }
}

