package com.keepit.graph.model

import scala.util.Try

case class KindHeader[T](code: Byte) { // extends AnyVal
  require(code > 0, "Kind header must be positive")
}

case class VertexId(id: Long) extends AnyVal {
  def asId[V <: VertexDataReader](implicit header: KindHeader[V]): VertexDataId[V] = {
    require(code == header.code, "Invalid VertexId")
    VertexDataId[V](dataId)
  }
  def asIdOpt[V <: VertexDataReader](implicit header: KindHeader[V]): Option[VertexDataId[V]] = Try(asId[V]).toOption
  override def toString() =  kind + "|" + dataId
  private def kind: VertexKind[_ <: VertexDataReader] = VertexKind(code)
  private def code: Byte = (id >> VertexId.dataIdSpace).toByte
  private def dataId: Long = id & VertexId.maxVertexDataId
}

object VertexId {
  val totalSpace = 64
  val headerSpace = 8
  val dataIdSpace = totalSpace - headerSpace
  val maxVertexDataId: Long = (1.toLong << dataIdSpace) - 1
  def apply[V <: VertexDataReader](id: VertexDataId[V])(implicit header: KindHeader[V]): VertexId = {
    require(id.id <= maxVertexDataId, s"VertexDataId $id is too large to be globalized")
    VertexId((header.code.toLong << dataIdSpace) | id.id)
  }
}
