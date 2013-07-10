package com.keepit.learning.topicmodel

import com.google.inject.{Inject, Singleton, Provider}
import com.keepit.model._
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import play.api.Play._
import scala.concurrent.Promise

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
    val accessorA = makeA()
    val accessorB = makeB()
    new SwitchableTopicModelAccessor(Promise.successful(accessorA).future, Promise.successful(accessorB).future)
  }

  def makeA() = {
    val nameMapperA = nameMapperFactory(TopicModelAccessorFlag.A)
    val wordTopicModelA = wordTopicModelFactory(TopicModelAccessorFlag.A)
    val docTopicModelA = new LDATopicModel(wordTopicModelA)
    new TopicModelAccessorA(userTopicRepoA, uriTopicRepoA, topicSeqInfoRepoA, topicNameRepoA, docTopicModelA, wordTopicModelA, nameMapperA)
  }

  def makeB() = {
    val nameMapperB = nameMapperFactory(TopicModelAccessorFlag.B)
    val wordTopicModelB = wordTopicModelFactory(TopicModelAccessorFlag.B)
    val docTopicModelB = new LDATopicModel(wordTopicModelB)
    new TopicModelAccessorB(userTopicRepoB, uriTopicRepoB, topicSeqInfoRepoB, topicNameRepoB, docTopicModelB, wordTopicModelB, nameMapperB)

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
) extends NameMapperFactory with Logging{

  def apply(flag: String) = {
    flag match {
      case TopicModelAccessorFlag.A => loadFromRepo(topicNameRepoA)
      case TopicModelAccessorFlag.B => loadFromRepo(topicNameRepoB)
    }
  }

  private def loadFromRepo(repo: TopicNameRepoBase) = {
    log.info(s"loading topic names from ${repo.tableName}")
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
  def apply(flag: String): WordTopicModel
}

@Singleton
class WordTopicModelFactoryImpl @Inject()(wordTopicStore: WordTopicStore) extends WordTopicModelFactory with Logging{

  def apply(flag: String) = {

    val id = flag match {
      case TopicModelAccessorFlag.A => "model_a"          // file name in S3
      case TopicModelAccessorFlag.B => "model_b"
    }

    val content = wordTopicStore.get(id).get
    val topicNames: Array[String] = (0 until TopicModelGlobal.numTopics).map{ i => "topic%d".format(i)}.toArray
    val loader = new LdaTopicModelLoader
    loader.load(content, topicNames)
  }

}

@Singleton
class FakeWordTopicModelFactoryImpl() extends WordTopicModelFactory{
  def apply(flag: String) = {
    val vocabulary: Set[String] = (0 until TopicModelGlobal.numTopics).map{ i => "word%d".format(i)}.toSet
    val wordTopic: Map[String, Array[Double]] = (0 until TopicModelGlobal.numTopics).foldLeft(Map.empty[String, Array[Double]]){
      (m, i) => { val a = new Array[Double](TopicModelGlobal.numTopics); a(i) = 1.0; m + ("word%d".format(i) -> a) }
    }
    val topicNames: Array[String] = (0 until TopicModelGlobal.numTopics).map{ i => "topic%d".format(i)}.toArray
    print("loading fake topic model")
    new LdaWordTopicModel(vocabulary, wordTopic, topicNames)
  }
}
