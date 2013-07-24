package com.keepit.graph.model

import play.api.libs.json._
import scala.reflect.runtime.universe._

trait Edge[S <: VertexData, D <: VertexData, +E <: EdgeData] {
  val source: VertexId[S]
  val destination: VertexId[D]
  val data: E
}

trait EdgeData

object EdgeData {

  def format[E <: EdgeData]()(implicit tag: TypeTag[E]): Format[E] = tag.tpe match {
    case t if t =:= typeOf[KeptData] => KeptData.format.asInstanceOf[Format[E]]
    case t if t =:= typeOf[FollowsData] => FollowsData.format.asInstanceOf[Format[E]]
    case t if t =:= typeOf[CollectsData] => CollectsData.format.asInstanceOf[Format[E]]
    case t if t =:= typeOf[ContainsData] => ContainsData.format.asInstanceOf[Format[E]]
  }
}
