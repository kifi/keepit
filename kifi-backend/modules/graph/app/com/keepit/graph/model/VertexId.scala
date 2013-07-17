package com.keepit.graph.model

import com.keepit.common.db.Id
import com.keepit.model.{Collection, NormalizedURI, User}
import scala.reflect.runtime.universe._

case class VertexId[+T <: VertexData](id: Int) {
  override def toString = id.toString
}

object VertexId {

  def fourBitPrefix[DbType]()(implicit tag: TypeTag[DbType]): Int = tag.tpe match {
    case t if t =:= typeOf[User] => 0
    case t if t =:= typeOf[NormalizedURI] => 1
    case t if t =:= typeOf[Collection] => 2
  }

  def apply[DbType: TypeTag, T <: VertexData](id: Id[DbType]): VertexId[T] = {
    require((id.id >> 28) == 0)
    VertexId[T](id.id.toInt | (fourBitPrefix[DbType]() << 28))
  }

  def databaseId[T <: VertexData, DbType: TypeTag](id: VertexId[T]): Id[DbType] = {
    require((id.id >> 28) == fourBitPrefix[DbType]())
    Id[DbType](id.id & ((15 << 28) ^ 0))
  }

}
