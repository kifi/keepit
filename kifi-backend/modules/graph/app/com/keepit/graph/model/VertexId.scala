package com.keepit.graph.model

import scala.util.Try
import play.api.libs.json.{ JsNumber, Writes, Reads, Format }

case class InvalidVertexIdException[V <: VertexDataReader](vertexId: VertexId, expectedKind: VertexKind[V])
  extends Exception(s"VertexId $vertexId is not of extected kind $expectedKind.")

case class VertexId(id: Long) extends AnyVal {
  def asId[V <: VertexDataReader](implicit kind: VertexKind[V]): VertexDataId[V] = {
    if (header != kind.header) { throw InvalidVertexIdException(this, kind) }
    VertexDataId[V](dataId)
  }
  def asIdOpt[V <: VertexDataReader](implicit kind: VertexKind[V]): Option[VertexDataId[V]] = Try(asId[V]).toOption
  def kind: VertexKind[_ <: VertexDataReader] = VertexKind(header)
  override def toString() = kind.code + "::" + dataId
  private def header: Byte = (id >> VertexId.dataIdSpace).toByte
  private def dataId: Long = id & VertexId.maxVertexDataId
}

object VertexId {
  val totalSpace = 63 // To ensure non-negative VertexIds
  val headerSpace = 8
  val dataIdSpace = 55
  val maxVertexDataId: Long = (1L << dataIdSpace) - 1
  val maxHeader: Long = (1L << headerSpace) - 1
  def apply[V <: VertexDataReader](id: VertexDataId[V])(implicit kind: VertexKind[V]): VertexId = {
    require(id.id <= maxVertexDataId, s"VertexDataId $id is too large")
    require(kind.header.toLong <= maxHeader, s"Header ${kind.header} is too large")
    VertexId((kind.header.toLong << dataIdSpace) | id.id)
  }

  def apply[V <: VertexDataReader](kind: VertexKind[V])(id: Long): VertexId = VertexId(kind.id(id))(kind)

  implicit val format: Format[VertexId] = Format(Reads.of[Long].map(VertexId(_)), Writes(id => JsNumber(id.id)))
}
