package com.keepit.learning.topicmodel
import scala.util.parsing.json.JSON

trait TopicModelLoader {
  def load(wordTopicJson: String): WordTopicModel
}

class LdaTopicModelLoader extends TopicModelLoader {
  /**
   * wordTopic example format: {"w1":[1,2],"w2":[3,4],"w3":[5,6]}
   */
  def load(wordTopicJson: String) = {
    val wordTopicMap = JSON.parseFull(wordTopicJson).get.asInstanceOf[Map[String, Seq[Double]]]
    val vocabulary = wordTopicMap.keySet
    val topic = wordTopicMap.foldLeft(Map.empty[String, Array[Double]])((m, x) => m + (x._1 -> x._2.toArray))
    new LdaWordTopicModel(vocabulary, topic)
  }

  def load(words: Array[String], topicVector: Array[Double]) = {
    val numWords = words.size
    val numTopics = topicVector.size / numWords
    var m = Map.empty[String, Array[Double]]
    for(i <- 0 until numWords){
      val topic = topicVector.slice(i*numTopics, (i + 1)*numTopics)
      m += (words(i) -> topic)
    }
    new LdaWordTopicModel(words.toSet, m)
  }
}