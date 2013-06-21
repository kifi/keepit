package com.keepit.learning.topicmodel

import org.specs2.mutable.Specification
import play.api.libs.json._

class TopicModelLoaderTest extends Specification {

  def makeTopicNameMap = {
    val m = (0 until TopicModelGlobal.numTopics).foldLeft(Map.empty[String, Int])( (m, i) =>
      m + ("topic%d".format(i) -> i)
    )
    m
  }

  def makeWordTopicMap= {
    val arr = new Array[Double](TopicModelGlobal.numTopics)
    val m = (0 until TopicModelGlobal.numTopics).foldLeft(Map.empty[String, Array[Double]])( (m, i) =>
       m + ( "word%d".format(i) -> arr )
    )
    m
  }

  "loader" should {
    "correctly load model" in {
      val loader = new LdaTopicModelLoader
      val topic = makeWordTopicMap
      val names = makeTopicNameMap
      val model = loader.loadFromJsonText(Json.toJson(topic).toString, Json.toJson(names).toString)
      model.vocabulary === topic.keySet
      model.wordTopic.foreach{ x =>
        topic.get(x._1).get === x._2
      }
      model.topicNames.toSet === names.keySet

    }
  }

}