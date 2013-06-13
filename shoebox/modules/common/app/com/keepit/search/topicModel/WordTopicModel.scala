package com.keepit.search.topicModel


trait WordTopicModel {
  val vocabulary: Set[String]
  val wordTopic: Map[String, Array[Double]]
  val topicNames: Array[String]
}
