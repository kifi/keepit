package com.keepit.graph.ingestion

import com.keepit.graph.model.GraphManager
import com.kifi.franz.SQSMessage
import com.keepit.common.db.SequenceNumber
import play.api.libs.json._
import play.api.libs.json.JsObject
import java.io.File
import org.apache.commons.io.FileUtils

trait GraphUpdater extends GraphPersister {
  type G <: GraphManager
  val graph: G
  val state: GraphUpdaterState
  val processUpdate: GraphUpdateProcessor

  def process(updates: Seq[SQSMessage[GraphUpdate]]): Unit = {
    val relevantUpdates = updates.collect { case SQSMessage(update, _, _) if update.seq > state.getCurrentSequenceNumber(update.kind) => update }
    graph.write { implicit writer => relevantUpdates.sortBy(_.seq.value).foreach(processUpdate(_)) }
    state.commit(relevantUpdates) // todo(Léo): add transaction callback capabilities to GraphWriter (cf SessionWrapper)
    updates.foreach(_.consume)
  }
}

trait GraphPersister { self: GraphUpdater =>
  val graphDirectory: GraphDirectory

  def persist(graph: G, state: GraphUpdaterState): Unit = { persistGraph(graph); persistState(state) }
  def load(): (G, GraphUpdaterState) = (loadGraph(), loadState())
  def backup(): Unit

  private def getStateFile(): File = new File(graphDirectory.getDirectory(), "state")

  protected def persistState(state: GraphUpdaterState): Unit = {
    val json = GraphUpdaterState.format.writes(state)
    FileUtils.writeStringToFile(getStateFile(), Json.stringify(json))
  }

  protected def loadState(): GraphUpdaterState = {
    val json = Json.parse(FileUtils.readFileToString(getStateFile()))
    GraphUpdaterState.format.reads(json).get
  }

  protected def persistGraph(graph: G): Unit
  protected def loadGraph(): G
}

class GraphUpdaterState(var state: Map[GraphUpdateKind[_ <: GraphUpdate], Long]) {
  def commit(updates: Seq[GraphUpdate]): Unit = { state ++= updates.groupBy(_.kind).mapValues(_.map(_.seq.value).max) }
  def getCurrentSequenceNumber[U <: GraphUpdate](implicit kind: GraphUpdateKind[U]): SequenceNumber[U] = SequenceNumber[U](state.getOrElse(kind, 0))
}

object GraphUpdaterState {
  val format: Format[GraphUpdaterState] = new Format[GraphUpdaterState] {
    def reads(json: JsValue): JsResult[GraphUpdaterState] = json.validate[JsObject].map { case obj =>
      val state = obj.value.map { case (kindName, seqValue) =>
        GraphUpdateKind(kindName) -> seqValue.as[Long]
      }.toMap[GraphUpdateKind[_ <: GraphUpdate], Long]
      new GraphUpdaterState(state)
    }
    def writes(state: GraphUpdaterState): JsValue = JsObject(state.state.map { case (kind, seq) => kind.code -> JsNumber(seq) }.toSeq)
  }

  val empty = new GraphUpdaterState(Map.empty)
}
