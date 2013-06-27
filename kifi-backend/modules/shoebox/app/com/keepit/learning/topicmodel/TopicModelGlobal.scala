package com.keepit.learning.topicmodel

import net.codingwell.scalaguice.ScalaModule
import com.google.inject.{Singleton, Provides}
import com.keepit.common.logging.Logging
import play.api.Play._
import com.keepit.inject.AppScoped

object TopicModelGlobal {
  val numTopics = 100
  val primaryTopicThreshold = 0.1       // need to tune this as numTopics varies
  val topicTailcut = 0.5
}

trait TopicModelModule extends ScalaModule

case class LdaTopicModelModule() extends TopicModelModule with Logging {

  def configure {
    bind[TopicUpdaterPlugin].to[TopicUpdaterPluginImpl].in[AppScoped]
  }

  @Provides
  @Singleton
  def wordTopicModel: WordTopicModel = {
    val path = current.configuration.getString("learning.topicModel.wordTopic.json.path").get
    log.info("loading word topic model")
    val c = scala.io.Source.fromFile(path).mkString
    // names don't matter much, at this moment
    val topicNames: Array[String] = (0 until TopicModelGlobal.numTopics).map{ i => "topic%d".format(i)}.toArray
    val loader = new LdaTopicModelLoader
    loader.load(c, topicNames)
  }
}