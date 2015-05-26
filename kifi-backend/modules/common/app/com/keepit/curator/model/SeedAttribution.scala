package com.keepit.curator.model

import com.keepit.common.CollectionHelpers
import com.keepit.model.{ Keep, User, Library }
import com.keepit.common.db.Id
import com.keepit.search.augmentation.LimitedAugmentationInfo

import com.kifi.macros.json

@json case class UserAttribution(friends: Seq[Id[User]], others: Int, friendsLib: Option[Map[Id[User], Id[Library]]])
@json case class KeepAttribution(keeps: Seq[Id[Keep]])
@json case class TopicAttribution(topicName: String)
@json case class LibraryAttribution(libraries: Seq[Id[Library]])
@json case class SeedAttribution(user: Option[UserAttribution] = None, keep: Option[KeepAttribution] = None, topic: Option[TopicAttribution] = None, library: Option[LibraryAttribution] = None)

object SeedAttribution {
  val EMPTY = SeedAttribution()
}

object UserAttribution {
  def fromLimitedAugmentationInfo(info: LimitedAugmentationInfo): UserAttribution = {
    val others = info.keepersTotal - info.keepers.size - info.keepersOmitted
    val userToLib = CollectionHelpers.dedupBy(info.libraries)(_._2).map { case (libraryId, userId, _) => (userId, libraryId) }.toMap // a user could have kept this page in several libraries, retain the first (most relevant) one.
    UserAttribution(info.keepers.map(_._1), others, Some(userToLib))
  }
}
