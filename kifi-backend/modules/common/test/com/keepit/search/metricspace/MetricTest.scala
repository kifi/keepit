package com.keepit.search.metricspace

import org.specs2.mutable.Specification
import com.keepit.common.db.Id
import com.keepit.model.User
import scala.math._

class MetricTest extends Specification {
  "WithMetric" should {
     "compute distance of two objects" in {
        val (id1, id2) = (Id[User](1), Id[User](2))
        val (t1, t2) = (Array(0.4, 0.4, 0.2), Array(0.4, 0.6, 0.0))
        val (u1, u2) = (new UserByTopic(id1, t1), new UserByTopic(id2, t2))
        val (u1e, u2e) = (new UserByTopicWithEucledian(u1), new UserByTopicWithEucledian(u2))
        val (u1i, u2i) = (new UserByTopicWithIntersection(u1), new UserByTopicWithIntersection(u2))

        val edist = 0.282842712474619
        val idist = 0.8
        abs(u1e.distanceTo(u2e) - edist) < 1e-5 === true
        abs(u1i.distanceTo(u2i) - idist) < 1e-5 === true

     }
  }
}