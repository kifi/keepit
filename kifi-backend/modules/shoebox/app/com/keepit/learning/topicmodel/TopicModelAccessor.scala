package com.keepit.learning.topicmodel

import com.keepit.model.{UserTopicRepo, UserTopicRepoA, UserTopicRepoB}
import com.keepit.model.{UriTopicRepo, UriTopicRepoA, UriTopicRepoB}
import com.keepit.model.{TopicSeqNumInfoRepo, TopicSeqNumInfoRepoA, TopicSeqNumInfoRepoB}
import com.keepit.model.{TopicNameRepo, TopicNameRepoA, TopicNameRepoB}
import com.google.inject.{Inject, Singleton}
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.concurrent._


/**
 * A pain point of topic model is: models are not backward compatible. When a new model is available,
 * e.g. from 100 topics to 200 topics, we have to re-compute values in various DBs. Also, the underlying
 * documentTopicModel will be different, the membership vector produced will be in-compatible with the one
 * from old model.
 *
 * A temporary solution is to have two copies of various resources: DB repos and models. They should be bundled
 * together. model A produces data to Repo A, model B talks to Repo B.
 *
 * Most of the time, only one type of resoure is active, either A or B. If A is active, then when a new model is
 * available, we use B to start a "catch up process": compute user topics, uri topics, etc. In the meanwhile,
 * resource A can still handle various requests. Once we have caught up, we can safely switch from A to B.
 */

trait TopicModelAccessor {
  val userTopicRepo: UserTopicRepo
  val uriTopicRepo: UriTopicRepo
  val topicSeqInfoRepo: TopicSeqNumInfoRepo
  val topicNameRepo: TopicNameRepo
  val documentTopicModel: DocumentTopicModel
  val wordTopicModel: WordTopicModel
  val topicNameMapper: TopicNameMapper
}

class TopicModelAccessorA (
  val userTopicRepo: UserTopicRepoA,
  val uriTopicRepo: UriTopicRepoA,
  val topicSeqInfoRepo: TopicSeqNumInfoRepoA,
  val topicNameRepo: TopicNameRepoA,
  val documentTopicModel: DocumentTopicModel,
  val wordTopicModel: WordTopicModel,
  val topicNameMapper: TopicNameMapper
) extends TopicModelAccessor

class TopicModelAccessorB (
  val userTopicRepo: UserTopicRepoB,
  val uriTopicRepo: UriTopicRepoB,
  val topicSeqInfoRepo: TopicSeqNumInfoRepoB,
  val topicNameRepo: TopicNameRepoB,
  val documentTopicModel: DocumentTopicModel,
  val wordTopicModel: WordTopicModel,
  val topicNameMapper: TopicNameMapper
) extends TopicModelAccessor

object TopicModelAccessorFlag {
  val A = "a"
  val B = "b"
}

class SwitchableTopicModelAccessor (
  var accessorA: Future[TopicModelAccessorA],
  var accessorB: Future[TopicModelAccessorB]
) {
  private var accessorFlag = TopicModelAccessorFlag.A       // default to A for now. Will read this from configuration or zookeeper or DB

  def getCurrentFlag = accessorFlag

  def getActiveAccessor = accessorFlag match {
    case TopicModelAccessorFlag.A  => Await.result(accessorA, 5 minutes)
    case TopicModelAccessorFlag.B  => Await.result(accessorB, 5 minutes)
  }

  def getInactiveAccessor = accessorFlag match {
    case TopicModelAccessorFlag.A  => Await.result(accessorB, 5 minutes)
    case TopicModelAccessorFlag.B  => Await.result(accessorA, 5 minutes)
  }

  def switchAccessor() = {
    accessorFlag match {
      case TopicModelAccessorFlag.A  => accessorFlag = TopicModelAccessorFlag.B
      case TopicModelAccessorFlag.B  => accessorFlag = TopicModelAccessorFlag.A
    }
  }
}
