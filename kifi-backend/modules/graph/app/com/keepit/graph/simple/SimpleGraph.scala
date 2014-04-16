package com.keepit.graph.simple

import com.keepit.graph.model._
import scala.collection.concurrent.{Map => ConcurrentMap, TrieMap}
import play.api.libs.json._
import java.io.File
import org.apache.commons.io.FileUtils
import play.api.libs.json.JsArray
import com.keepit.graph.ingestion.{GraphUpdateProcessor, GraphUpdate, GraphDirectory, GraphUpdaterState}

class SimpleGraph(
  val vertices: Vertices,
  val state: GraphUpdaterState,
  graphDirectory: GraphDirectory,
  processUpdate: GraphUpdateProcessor
) extends GraphManager {

  private val graphReader = new GraphReaderImpl(vertices.vertices)

  def readOnly[T](f: GraphReader => T): T = f(graphReader)

  def readWrite[T](f: GraphWriter => T): T = {
    val bufferedVertices = new BufferedMap(vertices.vertices)
    val graphWriter = new GraphWriterImpl(bufferedVertices)
    val result = f(graphWriter)
    this.synchronized { graphWriter.commit() }
    result
  }

  def backup(): Unit = {
    this.synchronized {
      persistVertices(vertices)
      persistState(state)
    }
    graphDirectory.synchronized { graphDirectory.doBackup() }
  }

  def update(updates: GraphUpdate*): GraphUpdaterState = {
    val relevantUpdates = updates.filter { update => update.seq > state.getCurrentSequenceNumber(update.kind) }
    readWrite { implicit writer => relevantUpdates.sortBy(_.seq.value).foreach(processUpdate(_)) }
    state.commit(relevantUpdates) // todo(Léo): add transaction callback capabilities to GraphWriter (cf SessionWrapper)
    state
  }

  protected def persistVertices(vertices: Vertices): Unit = {
    val json = Vertices.format.writes(vertices)
    FileUtils.writeStringToFile(SimpleGraph.getVerticesFile(graphDirectory), Json.stringify(json))
  }

  protected def persistState(state: GraphUpdaterState): Unit = {
    val json = GraphUpdaterState.format.writes(state)
    FileUtils.writeStringToFile(SimpleGraph.getStateFile(graphDirectory), Json.stringify(json))
  }
}

object SimpleGraph {
  def getStateFile(graphDirectory: GraphDirectory): File = new File(graphDirectory.getDirectory(), "state")
  def getVerticesFile(graphDirectory: GraphDirectory): File = new File(graphDirectory.getDirectory(), "vertices")
  def load(graphDirectory: GraphDirectory): (Vertices, GraphUpdaterState) = (loadVertices(graphDirectory), loadState(graphDirectory))

  private def loadVertices(graphDirectory: GraphDirectory): Vertices = {
    val json = Json.parse(FileUtils.readFileToString(getVerticesFile(graphDirectory)))
    Vertices.format.reads(json).get
  }

  private def loadState(graphDirectory: GraphDirectory): GraphUpdaterState = {
    val json = Json.parse(FileUtils.readFileToString(getStateFile(graphDirectory)))
    GraphUpdaterState.format.reads(json).get
  }
}

case class Vertices(vertices: ConcurrentMap[VertexId, MutableVertex])

object Vertices {
  def empty() = Vertices(TrieMap())

  implicit val format: Format[Vertices] = new Format[Vertices] {
    def writes(vertices: Vertices): JsValue = JsArray(vertices.vertices.values.map(MutableVertex.format.writes).toSeq)
    def reads(json: JsValue): JsResult[Vertices] = json.validate[JsArray].map { jsArray =>
      val mutableVertices = jsArray.value.map(_.as[MutableVertex])
      val vertices = TrieMap[VertexId, MutableVertex]()
      vertices ++= mutableVertices.map { mutableVertex =>
        val vertexData = mutableVertex.data
        (VertexId(vertexData.id)(vertexData.kind) -> mutableVertex)
      }
      Vertices(vertices)
    }
  }
}

