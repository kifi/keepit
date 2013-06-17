package com.keepit.search.topicModel

class FakeWordTopicModel extends WordTopicModel {
  val vocabulary = Set("bootstrap", "sampling", "apple", "orange", "iphone")
  val wordTopic = Map( "bootstrap" -> Array(0.5, 0.5, 0, 0),
                        "sampling" -> Array(0, 1.0, 0, 0),
                        "apple" -> Array(0, 0, 0.5, 0.5),
                        "orange" -> Array(0, 0, 1.0, 0),
                        "iphone" -> Array(0, 0, 0, 1.0))
  val topicNames = Array("frontend", "statistics", "fruit", "electronics")
}
