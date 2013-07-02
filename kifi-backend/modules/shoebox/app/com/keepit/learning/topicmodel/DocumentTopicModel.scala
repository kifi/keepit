package com.keepit.learning.topicmodel
import com.google.inject.{Provider, Inject, ImplementedBy, Singleton}
import scala.Array.canBuildFrom

@ImplementedBy(classOf[LDATopicModel])
trait DocumentTopicModel {
  def getDocumentTopicDistribution(content: String): Array[Double]
  def getDocumentTopicId(content: String): Int
}

@Singleton
class LDATopicModel @Inject()(model: Provider[WordTopicModel]) extends DocumentTopicModel{
  def getDocumentTopicDistribution(content: String) = {
    val words = content.split(" ").filter(!_.isEmpty).map(_.toLowerCase).filter(model.get.vocabulary.contains(_))
    val wordCounts = words.groupBy(x => x).foldLeft(Map.empty[String,Int]){(m, pair) => m + (pair._1 -> pair._2.size)}
    var dist = new Array[Double](model.get.topicNames.size)
    for(x <- wordCounts){
      val y = ArrayUtils.scale(model.get.wordTopic.get(x._1).get, x._2)
      dist = ArrayUtils.add(dist, y)
    }
    val s = ArrayUtils.sum(dist)
    if ( s == 0.0) dist
    else ArrayUtils.scale(dist, 1.0/s)
  }

  def getDocumentTopicId(content: String) = {
    val dist = getDocumentTopicDistribution(content)
    ArrayUtils.findMax(dist)
  }

}

object ArrayUtils {
  def add(x: Array[Double], y: Array[Double]) = {
    (x zip y).map{ a => a._1 + a._2}
  }
  def scale(x: Array[Double], s: Double) = {
    x.map(_*s)
  }

  def sum(x: Array[Double]) = {
    x.foldLeft(0.0)((s, a) => s + a)
  }

  def findMax(x: Array[Double]) = {
    if (x.length == 0) -1
    else {
      var i = 0
      var m = x(0)
      for(j <- 1 until x.length){
        if (x(j) > m) {
          i = j; m = x(j);
        }
      }
      i
    }
  }
}
