package com.keepit.learning.topicmodel

import com.keepit.model.{UserTopicRepo, UserTopicRepoA, UserTopicRepoB}
import com.keepit.model.{UriTopicRepo, UriTopicRepoA, UriTopicRepoB}
import com.keepit.model.{TopicSeqNumInfoRepo, TopicSeqNumInfoRepoA, TopicSeqNumInfoRepoB}
import com.keepit.model.{TopicNameRepo, TopicNameRepoA, TopicNameRepoB}
import com.google.inject.{Inject, Singleton}

trait TopicModelAccessor {
  val userTopicRepo: UserTopicRepo
  val uriTopicRepo: UriTopicRepo
  val topicSeqInfoRepo: TopicSeqNumInfoRepo
  val topicNameRepo: TopicNameRepo
  val documentTopicModel: DocumentTopicModel
}

@Singleton
class TopicModelAccessorA @Inject()(
  val userTopicRepo: UserTopicRepoA,
  val uriTopicRepo: UriTopicRepoA,
  val topicSeqInfoRepo: TopicSeqNumInfoRepoA,
  val topicNameRepo: TopicNameRepoA,
  val documentTopicModel: DocumentTopicModel
) extends TopicModelAccessor

@Singleton
class TopicModelAccessorB @Inject()(
  val userTopicRepo: UserTopicRepoB,
  val uriTopicRepo: UriTopicRepoB,
  val topicSeqInfoRepo: TopicSeqNumInfoRepoB,
  val topicNameRepo: TopicNameRepoB,
  val documentTopicModel: DocumentTopicModel
) extends TopicModelAccessor

object TopicModelAccessorFlag {
  val A = "a"
  val B = "b"
}

@Singleton
class SwitchableTopicModelAccessor @Inject()(
  val accessorA: TopicModelAccessorA,
  val accessorB: TopicModelAccessorB
) {
  var currentAccessor = TopicModelAccessorFlag.A       // will read this from configuration

  // this is the one in use
  def getActiveAccessor = currentAccessor match {
    case TopicModelAccessorFlag.A  => accessorA
    case TopicModelAccessorFlag.B  => accessorB
  }

  // this one will only be used during re-model procedure, when a new topic model is available
  def getInactiveAccessor = currentAccessor match {
    case TopicModelAccessorFlag.A  => accessorB
    case TopicModelAccessorFlag.B  => accessorA
  }

  def switchAccessor() = {
    currentAccessor match {
      case TopicModelAccessorFlag.A  => currentAccessor = TopicModelAccessorFlag.B
      case TopicModelAccessorFlag.B  => currentAccessor = TopicModelAccessorFlag.A
    }
  }
}