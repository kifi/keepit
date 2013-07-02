package com.keepit.learning.topicmodel

import org.specs2.mutable.Specification
import scala.math._
import play.api.test.Helpers._
import com.keepit.test._
import com.keepit.inject.ApplicationInjector


class TopicModelTest extends Specification with ApplicationInjector {
  "LDATopicModel" should {
    "correctly compute topic distribution for documents" in {
      running(new ShoeboxApplication().withTinyWordTopicModule()) {

        def equals(a: Array[Double], b: Array[Double]) = {
          if (a.length != b.length) false
          else {
            (a zip b).forall(x => abs(x._1 - x._2) < 1e-5)
          }
        }

        val docModel = inject[DocumentTopicModel]

        val docs = Array("Bootstrap sampling", "twitter bootstrap", "Apple iphone iphone", "apple and orange")
        val dists = Array(
          Array(0.25, 0.75, 0, 0),
          Array(0.5, 0.5, 0, 0),
          Array(0, 0, 0.5 / 3.0, 2.5 / 3.0),
          Array(0, 0, 0.75, 0.25))
        val topics = Array(1, 0, 3, 2)
        for (i <- 0 until 4) {
          val dist = docModel.getDocumentTopicDistribution(docs(i))
          val id = docModel.getDocumentTopicId(docs(i))
          equals(dist, dists(i)) === true
          id === topics(i)
        }
      }
    }
  }
}
