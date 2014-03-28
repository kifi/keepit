package com.keepit.graph.model

import com.keepit.model.Reflect
import scala.util.Try

case class KindHeader[T](code: Byte) { // extends AnyVal
  require(code > 0, "Kind header must be positive")
}

trait EdgeKind {
  type E <: EdgeDataReader
  implicit def header: KindHeader[E]
  def apply(rawDataReader: RawDataReader): E
}

object EdgeKind {
  val all: Set[EdgeKind] = Reflect.getCompanionTypeSystem[EdgeDataReader, EdgeKind]("E")
  private val byHeader: Map[Byte, EdgeKind] = {
    require(all.size == all.map(_.header).size, "Duplicate EdgeKind headers")
    all.map { edgeKind => edgeKind.header.code -> edgeKind }.toMap
  }
  def apply(header: Byte): EdgeKind = byHeader(header)
}

trait VertexKind {
  type V <: VertexDataReader
  implicit def header: KindHeader[V]
  def apply(rawDataReader: RawDataReader): V
}

object VertexKind {
  val all: Set[VertexKind] = Reflect.getCompanionTypeSystem[VertexDataReader, VertexKind]("V")
  private val byHeader: Map[Byte, VertexKind] = {
    require(all.size == all.map(_.header).size, "Duplicate VertexKind headers")
    all.map { vertexKind => vertexKind.header.code -> vertexKind }.toMap
  }
  def apply(header: Byte): VertexKind = byHeader(header)
}

case class VertexId(id: Long) extends AnyVal {
  def asId[V <: VertexDataReader](implicit header: KindHeader[V]): VertexDataId[V] = {
    require(code == header.code, "Invalid VertexId")
    VertexDataId[V](dataId)
  }
  def asIdOpt[V <: VertexDataReader](implicit header: KindHeader[V]): Option[VertexDataId[V]] = Try(asId[V]).toOption
  override def toString() = VertexKind(code) + "|" + dataId
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
