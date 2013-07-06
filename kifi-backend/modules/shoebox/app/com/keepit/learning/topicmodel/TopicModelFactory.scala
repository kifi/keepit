package com.keepit.learning.topicmodel

import com.google.inject.{Inject, Singleton, Provider}
import com.keepit.model._
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import play.api.Play._

@Singleton
class SwitchableTopicModelAccessorFactory @Inject()(
  db: Database,
  userTopicRepoA: UserTopicRepoA,
  uriTopicRepoA: UriTopicRepoA,
  topicSeqInfoRepoA: TopicSeqNumInfoRepoA,
  topicNameRepoA: TopicNameRepoA,
  userTopicRepoB: UserTopicRepoB,
  uriTopicRepoB: UriTopicRepoB,
  topicSeqInfoRepoB: TopicSeqNumInfoRepoB,
  topicNameRepoB: TopicNameRepoB,
  nameMapperFactory: NameMapperFactory,
  wordTopicModelFactory: WordTopicModelFactory
) {
  def apply() = {
    val nameMapperA = nameMapperFactory(TopicModelAccessorFlag.A)
    val nameMapperB = nameMapperFactory(TopicModelAccessorFlag.B)

    val wordTopicModelA = wordTopicModelFactory()
    val wordTopicModelB = wordTopicModelFactory()       // same for now, different later

    val docTopicModelA = new LDATopicModel(wordTopicModelA)
    val docTopicModelB = new LDATopicModel(wordTopicModelB)

    val accessorA = new TopicModelAccessorA(userTopicRepoA, uriTopicRepoA, topicSeqInfoRepoA, topicNameRepoA, docTopicModelA)
    val accessorB = new TopicModelAccessorB(userTopicRepoB, uriTopicRepoB, topicSeqInfoRepoB, topicNameRepoB, docTopicModelB)

    new SwitchableTopicModelAccessor(accessorA, accessorB)
  }
}

trait NameMapperFactory {
  def apply(flag: String): TopicNameMapper
}


@Singleton
class NameMapperFactoryImpl @Inject()(
  db: Database,
  topicNameRepoA: TopicNameRepoA,
  topicNameRepoB: TopicNameRepoB
) extends NameMapperFactory {

  def apply(flag: String) = {
    flag match {
      case TopicModelAccessorFlag.A => loadFromRepo(topicNameRepoA)
      case TopicModelAccessorFlag.B => loadFromRepo(topicNameRepoB)
    }
  }

  private def loadFromRepo(repo: TopicNameRepoBase) = {
    val rawNames = db.readOnly{ implicit s =>
      repo.getAllNames
    }.toArray

    val (newNames, mapper) = NameMapperConstructer.getMapper(rawNames)
    new ManualTopicNameMapper(rawNames, newNames, mapper)
  }
}

@Singleton
class FakeNameMapperFactoryImpl() extends NameMapperFactory{
  def apply(flag: String) = {
    val topicNames: Array[String] = (0 until TopicModelGlobal.numTopics).map { i => "topic%d".format(i) }.toArray
    new IdentityTopicNameMapper(topicNames)
  }
}

trait WordTopicModelFactory {
  def apply(): WordTopicModel
}

@Singleton
class WordTopicModelFactoryImpl() extends WordTopicModelFactory with Logging{
  // will load from S3 store
  def apply() = {
    val path = current.configuration.getString("learning.topicModel.wordTopic.json.path").get
    log.info("loading word topic model")
    val c = scala.io.Source.fromFile(path).mkString
    // names don't matter much, they will be provided by the nameMapper. May remove this field in the future.
    val topicNames: Array[String] = (0 until TopicModelGlobal.numTopics).map{ i => "topic%d".format(i)}.toArray
    val loader = new LdaTopicModelLoader
    loader.load(c, topicNames)
  }
}

@Singleton
class FakeWordTopicModelFactoryImpl() extends WordTopicModelFactory{
  def apply() = {
    val vocabulary: Set[String] = (0 until TopicModelGlobal.numTopics).map{ i => "word%d".format(i)}.toSet
    val wordTopic: Map[String, Array[Double]] = (0 until TopicModelGlobal.numTopics).foldLeft(Map.empty[String, Array[Double]]){
      (m, i) => { val a = new Array[Double](TopicModelGlobal.numTopics); a(i) = 1.0; m + ("word%d".format(i) -> a) }
    }
    val topicNames: Array[String] = (0 until TopicModelGlobal.numTopics).map{ i => "topic%d".format(i)}.toArray
    print("loading fake topic model")
    new LdaWordTopicModel(vocabulary, wordTopic, topicNames)
  }
}

