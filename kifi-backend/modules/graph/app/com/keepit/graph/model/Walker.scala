package com.keepit.graph.model

import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.healthcheck.HealthcheckPlugin

abstract class DrunkmobActor[V <: VertexData, E <: EdgeData](
  healthcheckPlugin: HealthcheckPlugin,
  graph: Graph[V,E],
  numberOfWalkers: Int,
  numberOfSteps: Int
) extends FortyTwoActor(healthcheckPlugin: HealthcheckPlugin) {

  type D <: V
  type S <: D

  def move(vertexId: VertexId[D]): VertexId[D]

  def walk(start: Set[VertexId[S]]): Map[VertexId[D], Int]

}
