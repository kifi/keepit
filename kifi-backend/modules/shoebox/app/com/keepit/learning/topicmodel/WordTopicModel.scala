package com.keepit.learning.topicmodel


trait WordTopicModel {
  val vocabulary: Set[String]
  val wordTopic: Map[String, Array[Double]]
  val topicNames: Array[String]
}

// load real ones from disk
class LdaWordTopicModel (
  val vocabulary: Set[String],
  val wordTopic: Map[String, Array[Double]],
  val topicNames: Array[String] = new Array[String](0)        // will use nameMappers to provide names
) extends WordTopicModel {}