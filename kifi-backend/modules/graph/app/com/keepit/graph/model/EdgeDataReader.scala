package com.keepit.graph.model

import com.keepit.common.reflection.CompanionTypeSystem
import play.api.libs.json._

sealed trait EdgeDataReader { self =>
  type E >: self.type <: EdgeDataReader
  def instance: E = self
  def kind: EdgeKind[E]
}

object EdgeDataReader {
  def apply(rawDataReader: RawDataReader): Map[EdgeKind[_ <: EdgeDataReader], EdgeDataReader] = {
    EdgeKind.all.map { edgeKind => edgeKind -> edgeKind(rawDataReader) }.toMap
  }

  // Json Helpers
  implicit val writes: Writes[EdgeDataReader] = Writes[EdgeDataReader](edgeDataReader => Json.obj(
    "header" -> edgeDataReader.kind.header.toInt,
    "data" -> edgeDataReader.kind.writes.writes(edgeDataReader.instance)
  ))
  implicit val readsAsEdgeData: Reads[EdgeData[_ <: EdgeDataReader]] = Reads(json =>
    (json \ "header").validate[Int].flatMap[EdgeData[_ <: EdgeDataReader]] { header =>
      EdgeKind(header.toByte).readsAsEdgeData.reads(json \ "data")
    }
  )
}

sealed trait EdgeKind[E <: EdgeDataReader] {
  implicit def kind: EdgeKind[E] = this
  implicit def header: Byte
  def apply(rawDataReader: RawDataReader): E

  def writes: Writes[E]
  def readsAsEdgeData: Reads[EdgeData[E]]
}

object EdgeKind {
  val all: Set[EdgeKind[_ <: EdgeDataReader]] = CompanionTypeSystem[EdgeDataReader, EdgeKind[_ <: EdgeDataReader]]("E")
  private val byHeader: Map[Byte, EdgeKind[_ <: EdgeDataReader]] = {
    require(all.forall(_.header > 0), "EdgeKind headers must be positive.")
    require(all.size == all.map(_.header).size, "Duplicate EdgeKind headers")
    all.map { edgeKind => edgeKind.header -> edgeKind }.toMap
  }
  def apply(header: Byte): EdgeKind[_ <: EdgeDataReader] = byHeader(header)
}

trait EmptyEdgeDataReader extends EdgeDataReader {
  def kind = EmptyEdgeDataReader
  type E = EmptyEdgeDataReader
}

case object EmptyEdgeDataReader extends EdgeKind[EmptyEdgeDataReader] {
  val header = 1.toByte
  def apply(rawDataReader: RawDataReader): EmptyEdgeDataReader = ???
  implicit val writes = Writes[EmptyEdgeDataReader](_ => Json.obj())
  implicit val readsAsEdgeData: Reads[EdgeData[EmptyEdgeDataReader]] = Reads[EdgeData[EmptyEdgeDataReader]](json => json.validate[JsObject].map(_ => EmptyEdgeData))
}

trait WeightedEdgeDataReader extends EdgeDataReader {
  def kind = WeightedEdgeDataReader
  type E = WeightedEdgeDataReader
  def getWeight: Float
}

case object WeightedEdgeDataReader extends EdgeKind[WeightedEdgeDataReader] {
  val header = 2.toByte
  def apply(rawDataReader: RawDataReader): WeightedEdgeDataReader = ???
  implicit val writes = Writes[WeightedEdgeDataReader](x => JsNumber(x.getWeight))
  implicit val readsAsEdgeData: Reads[EdgeData[WeightedEdgeDataReader]] = Reads[EdgeData[WeightedEdgeDataReader]](json => json.validate[JsNumber].map{ x => WeightedEdgeData(x.as[Float])})
}
