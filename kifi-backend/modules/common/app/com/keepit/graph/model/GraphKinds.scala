package com.keepit.graph.model

import play.api.libs.json.Json

case class GraphKinds(vertexKinds: Set[String], edgeKinds: Set[String])

object GraphKinds {
  implicit val format = Json.format[GraphKinds]
  def empty = GraphKinds(Set.empty, Set.empty)
}
