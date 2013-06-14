package com.keepit.search.metricspace

import com.keepit.common.db.Id
import com.keepit.model.User

trait Vectorized[T] {
  def vectorRepresentation: Array[Double]
}

class UserByTopic (val id: Id[User], val topicDist: Array[Double]) extends Vectorized[Id[User]] {
  override def vectorRepresentation = topicDist
}
