package com.keepit.learning.topicmodel

import org.specs2.mutable.Specification
import play.api.libs.json._

class TopicModelLoaderTest extends Specification {
  val numTopics = 10

  def makeWordTopicMap = {
    val arr = new Array[Double](numTopics)
    val m = (0 until numTopics).foldLeft(Map.empty[String, Array[Double]])((m, i) =>
      m + ("word%d".format(i) -> arr))
    m
  }

  "loader" should {
    "correctly load model" in {
      val loader = new LdaTopicModelLoader
      val topic = makeWordTopicMap
      val model = loader.load(Json.toJson(topic).toString)
      model.vocabulary === topic.keySet
      model.wordTopic.foreach { x =>
        topic.get(x._1).get === x._2
      }
    }
  }

}