package com.keepit.graph.model

import com.keepit.common.db.Id
import scala.reflect.runtime.universe._
import play.api.libs.json._

case class VertexId[+V <: VertexData](id: Int) {
  override def toString = id.toString
}

object VertexId {

  def apply[DbType, V <: VertexData](id: Id[DbType])(implicit tag: TypeTag[V]): VertexId[V] = {
    require((id.id >>> 28) == 0)
    VertexId[V](id.id.toInt | (VertexData.fourBitRepresentation[V]() << 28))
  }

  def toDbId[V <: VertexData, DbType](id: VertexId[V])(implicit tag: TypeTag[V]): Id[DbType] = {
    require((id.id >>> 28) == VertexData.fourBitRepresentation[V]())
    Id[DbType](id.id & ((0x0f << 28) ^ 0))
  }

  def format[V <: VertexData]: Format[VertexId[V]] =
    Format(__.read[Int].map(VertexId(_)), new Writes[VertexId[V]]{ def writes(o: VertexId[V]) = JsNumber(o.id) })
}
