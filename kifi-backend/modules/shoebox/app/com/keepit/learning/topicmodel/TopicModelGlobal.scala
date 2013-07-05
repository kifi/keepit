package com.keepit.learning.topicmodel

import net.codingwell.scalaguice.ScalaModule
import com.google.inject.{Provider, Singleton, Provides}
import com.keepit.common.logging.Logging
import play.api.Play._
import com.keepit.inject.AppScoped
import com.keepit.model.TopicNameRepoA
import com.keepit.common.db.slick.Database

object TopicModelGlobal {
  val numTopics = 100
  val primaryTopicThreshold = 0.07       // need to tune this as numTopics varies
  val topicTailcut = 0.7
  val naString = "NA"
}

trait TopicModelModule extends ScalaModule {
  def configure {
    bind[TopicUpdaterPlugin].to[TopicUpdaterPluginImpl].in[AppScoped]
  }
}

case class LdaTopicModelModule() extends TopicModelModule with Logging {

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

  @Provides
  @Singleton
  def topicNameMapper(db: Database, topicNameRepo: TopicNameRepoA): TopicNameMapper = {
    // test read from db. will be removed soon.
    log.info("loading topic names from DB ...")
    val names = db.readOnly{ implicit s =>
      topicNameRepo.getAllNames
    }

    names.zipWithIndex.foreach{ case (name, i) =>
      log.info(s"topic ${i+1}: ${name}")
    }

    log.info("loading topic name list")
    val path = current.configuration.getString("learning.topicModel.topicNames.path").get
    val rows = scala.io.Source.fromFile(path).mkString.split("\n")
    assume(rows.size == TopicModelGlobal.numTopics, "insufficient raw topic names")
    val sep = "\t"
    val rawNames = rows.map{_.split(sep)(1)}
    val (newNames, mapper) = NameMapperConstructer.getMapper(rawNames)
    new ManualTopicNameMapper(rawNames, newNames, mapper)
  }
}

case class DevTopicModelModule() extends TopicModelModule {

  @Provides
  @Singleton
  def wordTopicModel: WordTopicModel = {
    val vocabulary: Set[String] = (0 until TopicModelGlobal.numTopics).map{ i => "word%d".format(i)}.toSet
    val wordTopic: Map[String, Array[Double]] = (0 until TopicModelGlobal.numTopics).foldLeft(Map.empty[String, Array[Double]]){
      (m, i) => { val a = new Array[Double](TopicModelGlobal.numTopics); a(i) = 1.0; m + ("word%d".format(i) -> a) }
    }
    val topicNames: Array[String] = (0 until TopicModelGlobal.numTopics).map{ i => "topic%d".format(i)}.toArray
    print("loading fake topic model")
    new LdaWordTopicModel(vocabulary, wordTopic, topicNames)
  }

  @Provides
  @Singleton
  def topicNameMapper: TopicNameMapper = {
    val topicNames: Array[String] = (0 until TopicModelGlobal.numTopics).map { i => "topic%d".format(i) }.toArray
    new IdentityTopicNameMapper(topicNames)
  }
}
