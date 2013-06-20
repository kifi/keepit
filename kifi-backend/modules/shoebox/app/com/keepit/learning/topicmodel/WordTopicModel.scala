package com.keepit.learning.topicmodel


trait WordTopicModel {
  val vocabulary: Set[String]
  val wordTopic: Map[String, Array[Double]]
  val topicNames: Array[String]
}

// real one coming soon
class LdaWordTopicModel extends WordTopicModel {
  val numTopics = TopicModelGlobal.numTopics
  val vocabulary = (0 until numTopics).map{ i => "word%d".format(i)}.toSet
  val wordTopic = (0 until numTopics).foldLeft(Map.empty[String, Array[Double]]){
        (m, i) => { val a = new Array[Double](numTopics); a(i) = 1.0; m + ("word%d".format(i) -> a) }
      }
  val topicNames = (0 until numTopics).map{ i => "topic%d".format(i)}.toArray
}