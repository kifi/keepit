package com.keepit.graph.wander

import com.keepit.graph.model.{ VertexDataReader, VertexKind, VertexReader, VertexId }
import com.keepit.common.math.{ Probability, ProbabilityDensity }

trait Teleporter {
  def surely: VertexId
  def maybe(wanderer: VertexReader): Option[VertexId]
}

case class UniformTeleporter(destinations: Set[VertexId])(mayTeleport: VertexReader => Double) extends Teleporter {
  require(destinations.nonEmpty, "The supplied destination set is empty.")

  private val teleportAlmostSurely = {
    val probability = 1d / destinations.size
    val density = destinations.map { vertexId => Probability(vertexId, probability) }.toSeq
    ProbabilityDensity(density)
  }

  def surely = teleportAlmostSurely.sample(Math.random()).get

  def maybe(wanderer: VertexReader): Option[VertexId] = {
    if (mayTeleport(wanderer) > Math.random()) { teleportAlmostSurely.sample(Math.random()) }
    else None
  }
}
