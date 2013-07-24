package com.keepit.graph.model

import scala.reflect.runtime.universe._
import play.api.libs.json._

trait Vertex[+V <: VertexData] {
  val id: VertexId[V]
  val data: V
}

case class VertexId[+V <: VertexData](id: Long) {
  override def toString = id.toString
}

object VertexId {

  def format[V <: VertexData]: Format[VertexId[V]] =
    Format(__.read[Long].map(VertexId(_)), new Writes[VertexId[V]]{ def writes(o: VertexId[V]) = JsNumber(o.id) })
}

trait VertexData

object VertexData {

  def format[V <: VertexData]()(implicit tag: TypeTag[V]): Format[V] = tag.tpe match {
    case t if t =:= typeOf[UserData] => UserData.format.asInstanceOf[Format[V]]
    case t if t =:= typeOf[UriData] => UriData.format.asInstanceOf[Format[V]]
    case t if t =:= typeOf[CollectionData] => CollectionData.format.asInstanceOf[Format[V]]
  }
}
