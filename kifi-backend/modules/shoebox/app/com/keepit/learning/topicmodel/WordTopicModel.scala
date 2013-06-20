package com.keepit.learning.topicmodel


trait WordTopicModel {
  val vocabulary: Set[String]
  val wordTopic: Map[String, Array[Double]]
  val topicNames: Array[String]
}
