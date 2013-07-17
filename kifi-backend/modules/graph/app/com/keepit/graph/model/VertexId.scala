package com.keepit.graph.model

import com.keepit.common.db.Id
import com.keepit.model.{Collection, NormalizedURI, User}
import scala.reflect.runtime.universe._

trait VertexId

case class RealVertexId[T](id: Int) extends VertexId {
  override def toString = id.toString
}

object RealVertexId {

  def fourBitPrefix[T]()(implicit tag: TypeTag[T]): Int = tag.tpe match {
    case t if t =:= typeOf[User] => 0
    case t if t =:= typeOf[NormalizedURI] => 1
    case t if t =:= typeOf[Collection] => 2
  }

  def apply[T: TypeTag](id: Id[T]): RealVertexId[T] = {
    require((id.id >> 28) == 0)
    RealVertexId[T](id.id.toInt | (fourBitPrefix[T]() << 28))
  }

  def databaseId[T: TypeTag](id: RealVertexId[T]): Id[T] = {
    val dbId = Id[T](id.id & ((15 << 28) ^ 0))
    require((id.id >> 28) == fourBitPrefix[T]())
    dbId
  }

}
