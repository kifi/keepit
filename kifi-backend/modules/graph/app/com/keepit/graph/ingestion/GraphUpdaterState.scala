package com.keepit.graph.ingestion

import com.keepit.common.db.SequenceNumber
import play.api.libs.json._
import play.api.libs.json.JsObject

case class GraphUpdaterState(state: Map[GraphUpdateKind[_ <: GraphUpdate], Long]) extends AnyVal {
  def withUpdates(updates: Seq[GraphUpdate]): GraphUpdaterState = {
    val newState = state ++ updates.groupBy(_.kind).mapValues(_.map(_.seq.value).max)
    GraphUpdaterState(newState)
  }
  def getCurrentSequenceNumber[U <: GraphUpdate](implicit kind: GraphUpdateKind[U]): SequenceNumber[U] = SequenceNumber[U](state.getOrElse(kind, 0))
}

object GraphUpdaterState {
  val format: Format[GraphUpdaterState] = new Format[GraphUpdaterState] {
    def reads(json: JsValue): JsResult[GraphUpdaterState] = json.validate[JsObject].map { case obj =>
      val state = obj.value.map { case (kindName, seqValue) => GraphUpdateKind(kindName) -> seqValue.as[Long] }.toMap
      GraphUpdaterState(state)
    }
    def writes(state: GraphUpdaterState): JsValue = JsObject(state.state.map { case (kind, seq) => kind.code -> JsNumber(seq) }.toSeq)
  }

  val empty = GraphUpdaterState(Map.empty)
}
