package com.keepit.curator.model

import com.keepit.model.{ Keep, User }
import com.keepit.common.db.Id

import com.kifi.macros.json

@json case class UserAttribution(friends: Seq[Id[User]], others: Int)
@json case class KeepAttribution(keeps: Seq[Id[Keep]])
@json case class TopicAttribution(topicName: String)
@json case class SeedAttribution(user: Option[UserAttribution] = None, keep: Option[KeepAttribution] = None, topic: Option[TopicAttribution] = None)

object SeedAttribution {
  val EMPTY = SeedAttribution()
}
