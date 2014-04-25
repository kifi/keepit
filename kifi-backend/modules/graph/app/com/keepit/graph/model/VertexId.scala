package com.keepit.graph.model

import scala.util.Try
import play.api.libs.json.{JsNumber, Writes, Reads, Format}

case class VertexId(id: Long) extends AnyVal {
  def asId[V <: VertexDataReader](implicit kind: VertexKind[V]): VertexDataId[V] = {
    require(code == kind.header, "Invalid VertexId")
    VertexDataId[V](dataId)
  }
  def asIdOpt[V <: VertexDataReader](implicit kind: VertexKind[V]): Option[VertexDataId[V]] = Try(asId[V]).toOption
  def kind: VertexKind[_ <: VertexDataReader] = VertexKind(code)
  override def toString() =  kind + "|" + dataId
  private def code: Byte = (id >> VertexId.dataIdSpace).toByte
  private def dataId: Long = id & VertexId.maxVertexDataId
}

object VertexId {
  val totalSpace = 64
  val headerSpace = 8
  val dataIdSpace = totalSpace - headerSpace
  val maxVertexDataId: Long = (1.toLong << dataIdSpace) - 1
  def apply[V <: VertexDataReader](id: VertexDataId[V])(implicit kind: VertexKind[V]): VertexId = {
    require(id.id <= maxVertexDataId, s"VertexDataId $id is too large to be globalized")
    VertexId((kind.header.toLong << dataIdSpace) | id.id)
  }

  implicit val format: Format[VertexId] = Format(Reads.of[Long].map(VertexId(_)), Writes(id => JsNumber(id.id)))
}
