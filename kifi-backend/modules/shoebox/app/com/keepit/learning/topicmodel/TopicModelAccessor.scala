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
  val topicNameRepoA: TopicNameRepoA,
  val documentTopicModel: DocumentTopicModel
) extends TopicModelAccessor

@Singleton
class TopicModelAccessorB @Inject()(
  val userTopicRepo: UserTopicRepoB,
  val uriTopicRepo: UriTopicRepoB,
  val topicSeqInfoRepo: TopicSeqNumInfoRepoB,
  val topicNameRepoB: TopicNameRepoB,
  val documentTopicModel: DocumentTopicModel
) extends TopicModelAccessor