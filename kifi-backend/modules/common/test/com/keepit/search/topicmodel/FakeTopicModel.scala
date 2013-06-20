package com.keepit.search.topicmodel

import com.keepit.search.topicModel.WordTopicModel
import com.keepit.search.topicModel.TopicModelGlobal

class FakeWordTopicModel extends WordTopicModel {
  val vocabulary = Set("bootstrap", "sampling", "apple", "orange", "iphone")
  val wordTopic = Map( "bootstrap" -> Array(0.5, 0.5, 0, 0),
                        "sampling" -> Array(0, 1.0, 0, 0),
                        "apple" -> Array(0, 0, 0.5, 0.5),
                        "orange" -> Array(0, 0, 1.0, 0),
                        "iphone" -> Array(0, 0, 0, 1.0))
  val topicNames = Array("frontend", "statistics", "fruit", "electronics")
}

class FakeWordTopicModel2 extends WordTopicModel {
  val numTopics = TopicModelGlobal.numTopics
  val vocabulary = (0 until numTopics).map{ i => "word%d".format(i)}.toSet
  val wordTopic = (0 until numTopics).foldLeft(Map.empty[String, Array[Double]]){
        (m, i) => { val a = new Array[Double](numTopics); a(i) = 1.0; m + ("word%d".format(i) -> a) }
      }
  val topicNames = (0 until numTopics).map{ i => "topic%d".format(i)}.toArray
}

