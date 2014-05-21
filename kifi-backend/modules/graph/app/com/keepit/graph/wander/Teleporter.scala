package com.keepit.graph.wander

import com.keepit.graph.model.{VertexDataReader, VertexKind, VertexReader, VertexId}
import com.keepit.common.math.ProbabilityDensity

trait Teleporter {
  def surely: VertexId
  def maybe(wanderer: VertexReader): Option[VertexId]
}

case class UniformTeleporter(destinations: Set[VertexId], teleportCondition: Set[VertexKind[_ <: VertexDataReader]], teleportProbability: Double) extends Teleporter {
  require(destinations.nonEmpty, "The supplied destination set is empty.")

  private def mayTeleport(wanderer: VertexReader): Boolean = teleportCondition.isEmpty || teleportCondition.contains(wanderer.kind)

  private val teleportAlmostSurely = {
    val probability = 1d / destinations.size
    val density = destinations.map { vertexId => vertexId -> probability }.toSeq
    ProbabilityDensity(density)
  }

  private val teleportMaybe =  {
    val density = teleportAlmostSurely.density.map { case (destination, probability) => destination -> probability * teleportProbability }
    ProbabilityDensity(density)
  }

  def surely = teleportAlmostSurely.sample(Math.random()).get

  def maybe(wanderer: VertexReader): Option[VertexId] = {
    if (mayTeleport(wanderer)) { teleportMaybe.sample(Math.random()) }
    else None
  }
}
