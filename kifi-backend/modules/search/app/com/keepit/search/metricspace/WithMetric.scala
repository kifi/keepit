package com.keepit.search.metricspace

import com.keepit.common.db.Id
import com.keepit.model.User

/**
 * represent an object of type T in a metric space. Distance function is defined
 * for two objects only if these two objects are of same type T, using same vectorization
 * method V, and equipped with same metric M.
 */
trait WithMetric[T, V <: Vectorized[T], M <: Metric] extends Vectorized[T] {
  val vectorized: V
  val metric : M
  override def vectorRepresentation = vectorized.vectorRepresentation
  final def distanceTo(other: WithMetric[T, V, M]): Double = metric.distance(this.vectorRepresentation, other.vectorRepresentation)
}

class UserByTopicWithEucledian (val vectorized: UserByTopic) extends WithMetric[Id[User], UserByTopic, EucledianMetric] {
  override val metric = new EucledianMetric
}

class UserByTopicWithIntersection(val vectorized: UserByTopic) extends WithMetric[Id[User], UserByTopic, Intersection] {
  override val metric = new Intersection
}
