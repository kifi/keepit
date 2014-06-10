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

  // Binary Helpers
  implicit def header: Byte
  def apply(rawDataReader: RawDataReader): E

  // Json helpers
  val code: String = toString.stripSuffix("Reader")
  def writes: Writes[E]
  def readsAsEdgeData: Reads[EdgeData[E]]
}

object EdgeKind {
  type EdgeType = EdgeKind[_ <: EdgeDataReader]
  val all: Set[EdgeType] = CompanionTypeSystem[EdgeDataReader, EdgeKind[_ <: EdgeDataReader]]("E")

  private val byHeader: Map[Byte, EdgeType] = {
    require(all.forall(_.header > 0), "EdgeKind headers must be positive.")
    require(all.size == all.map(_.header).size, "Duplicate EdgeKind headers")
    all.map { edgeKind => edgeKind.header -> edgeKind }.toMap
  }
  def apply(header: Byte): EdgeType = byHeader(header)

  private val byCode: Map[String, EdgeType] = {
    require(all.size == all.map(_.code).size, "Duplicate EdgeKind codes.")
    all.map { edgeKind => edgeKind.code -> edgeKind }.toMap
  }
  def apply(code: String): EdgeType = byCode(code)
}

trait EmptyEdgeReader extends EdgeDataReader {
  def kind = EmptyEdgeReader
  type E = EmptyEdgeReader
}

case object EmptyEdgeReader extends EdgeKind[EmptyEdgeReader] {
  val header = 1.toByte
  def apply(rawDataReader: RawDataReader): EmptyEdgeReader = ???
  implicit val writes = Writes[EmptyEdgeReader](_ => Json.obj())
  implicit val readsAsEdgeData: Reads[EdgeData[EmptyEdgeReader]] = Reads[EdgeData[EmptyEdgeReader]](json => json.validate[JsObject].map(_ => EmptyEdgeData))
}

trait WeightedEdgeReader extends EdgeDataReader {
  def kind = WeightedEdgeReader
  type E = WeightedEdgeReader
  def weight: Float
}

case object WeightedEdgeReader extends EdgeKind[WeightedEdgeReader] {
  val header = 2.toByte
  def apply(rawDataReader: RawDataReader): WeightedEdgeReader = ???
  implicit val writes = Writes[WeightedEdgeReader](x => JsNumber(x.weight))
  implicit val readsAsEdgeData: Reads[EdgeData[WeightedEdgeReader]] = Reads[EdgeData[WeightedEdgeReader]](json => json.validate[JsNumber].map{ x => WeightedEdgeData(x.as[Float])})
}

trait TimestampReader extends EdgeDataReader {
  def kind = TimestampReader
  type E = TimestampReader
  def timestamp: Long
}

case object TimestampReader extends EdgeKind[TimestampReader] {
  val header = 3.toByte
  def apply(rawDataReader: RawDataReader): TimestampReader = ???
  implicit val writes = Writes[TimestampReader](x => JsNumber(x.timestamp))
  implicit val readsAsEdgeData: Reads[EdgeData[TimestampReader]] = Reads[EdgeData[TimestampReader]](json => json.validate[JsNumber].map{ x => TimestampData(x.as[Long])})
}
