package com.keepit.learning.topicmodel

import com.keepit.model.{UserTopicRepo, UserTopicRepoA, UserTopicRepoB}
import com.keepit.model.{UriTopicRepo, UriTopicRepoA, UriTopicRepoB}
import com.keepit.model.TopicSeqNumInfoRepo
import com.google.inject.{Inject, Singleton}

trait TopicModelAccessor {
  val userTopicRepo: UserTopicRepo
  val uriTopicRepo: UriTopicRepo
  val topicSeqInfoRepo: TopicSeqNumInfoRepo
  val documentTopicModel: DocumentTopicModel
}

@Singleton
class TopicModelAccessorA @Inject()(
  val userTopicRepo: UserTopicRepoA,
  val uriTopicRepo: UriTopicRepoA,
  val topicSeqInfoRepo: TopicSeqNumInfoRepo,
  val documentTopicModel: DocumentTopicModel
) extends TopicModelAccessor

@Singleton
class TopicModelAccessorB @Inject()(
  val userTopicRepo: UserTopicRepoB,
  val uriTopicRepo: UriTopicRepoB,
  val topicSeqInfoRepo: TopicSeqNumInfoRepo,
  val documentTopicModel: DocumentTopicModel
) extends TopicModelAccessor