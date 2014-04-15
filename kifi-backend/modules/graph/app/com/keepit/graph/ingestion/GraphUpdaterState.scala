package com.keepit.graph.ingestion

import com.keepit.common.db.SequenceNumber
import play.api.libs.json._
import play.api.libs.json.JsObject

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
