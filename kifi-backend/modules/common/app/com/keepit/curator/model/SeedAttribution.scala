package com.keepit.curator.model

import com.keepit.model.{ Keep, User, Library }
import com.keepit.common.db.Id

import com.kifi.macros.json

@json case class UserAttribution(friends: Seq[Id[User]], others: Int, friendsLib: Option[Map[Id[User], Id[Library]]] = None)
@json case class KeepAttribution(keeps: Seq[Id[Keep]])
@json case class TopicAttribution(topicName: String)
@json case class LibraryAttribution(libraries: Seq[Id[Library]])
@json case class SeedAttribution(user: Option[UserAttribution] = None, keep: Option[KeepAttribution] = None, topic: Option[TopicAttribution] = None, library: Option[LibraryAttribution] = None)

object SeedAttribution {
  val EMPTY = SeedAttribution()
}
