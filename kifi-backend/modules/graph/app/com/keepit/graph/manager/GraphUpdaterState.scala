package com.keepit.graph.manager

import com.keepit.common.db.SequenceNumber
import play.api.libs.json._
import play.api.libs.json.JsObject

case class GraphUpdaterState(state: Map[GraphUpdateKind[_ <: GraphUpdate], Long]) {
  def withUpdates(updates: Seq[GraphUpdate]): GraphUpdaterState = GraphUpdaterState(state ++ updates.groupBy(_.kind).mapValues(_.map(_.seq.value).max))
  def getCurrentSequenceNumber[U <: GraphUpdate](implicit kind: GraphUpdateKind[U]): SequenceNumber[U] = SequenceNumber[U](state.getOrElse(kind, 0))
}

object GraphUpdaterState {
  implicit val format: Format[GraphUpdaterState] = new Format[GraphUpdaterState] {
    def reads(json: JsValue): JsResult[GraphUpdaterState] = json.validate[JsObject].map {
      case obj =>
        val state = obj.value.map {
          case (kindName, seqValue) =>
            GraphUpdateKind(kindName) -> seqValue.as[Long]
        }.toMap[GraphUpdateKind[_ <: GraphUpdate], Long]
        new GraphUpdaterState(state)
    }
    def writes(state: GraphUpdaterState): JsValue = JsObject(state.state.map { case (kind, seq) => kind.code -> JsNumber(seq) }.toSeq)
  }

  def empty = GraphUpdaterState(Map.empty)

  def prettify(state: GraphUpdaterState): PrettyGraphState = PrettyGraphState(state.state.map {
    case (SparseLDAGraphUpdate, cortexSeq) => SparseLDAGraphUpdate.toString -> CortexSequenceNumber.fromLong(cortexSeq).toString
    case (kind, seq) => kind.toString -> seq.toString
  }.toMap)
}
