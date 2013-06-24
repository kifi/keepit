package com.keepit.learning.topicmodel
import scala.util.parsing.json.JSON

trait TopicModelLoader {
  def loadFromJsonText(wordTopic: String, topicNames: String): WordTopicModel
}

class LdaTopicModelLoader extends TopicModelLoader {
  /**
   * wordTopic example format: {"w1":[1,2],"w2":[3,4],"w3":[5,6]}
   * topicNames example format: {"topic A":1,"topic B":2}
   */
  def loadFromJsonText(wordTopicJs: String, topicNamesJs: String) = {
    val wordTopicMap = JSON.parseFull(wordTopicJs).get.asInstanceOf[Map[String, Seq[Double]]]
    val vocabulary = wordTopicMap.keySet
    val topic = wordTopicMap.foldLeft(Map.empty[String, Array[Double]])((m, x) => m + (x._1 -> x._2.toArray))

    val m = JSON.parseFull(topicNamesJs).get.asInstanceOf[Map[String, Double]]
    val topicNames = new Array[String](TopicModelGlobal.numTopics)
    m.map{ x =>
      topicNames(x._2.toInt) = x._1
    }

    topicNames.foreach( name => assume( name != null, "some topic doesn't have a name"))
    assume(topicNames.toSet.size == topicNames.size, "looks like there are repeated topic names")

    new LdaWordTopicModel(vocabulary, topic, topicNames)
  }
}