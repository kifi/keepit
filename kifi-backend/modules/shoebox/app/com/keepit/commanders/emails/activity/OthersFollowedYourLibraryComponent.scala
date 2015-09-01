package com.keepit.commanders.emails.activity

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders.emails.LibraryInfoFollowersView
import com.keepit.commanders.{ LibraryInfoCommander, RecommendationsCommander }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.time.Clock
import com.keepit.model.{ ActivityEmail, Library, LibraryMembershipRepo, LibraryRepo, User }
import org.joda.time.DateTime

import scala.concurrent.Future

@Singleton
class OthersFollowedYourLibraryComponent @Inject() (db: Database,
    libraryRepo: LibraryRepo,
    val membershipRepo: LibraryMembershipRepo,
    val libraryInfoCommander: LibraryInfoCommander,
    val clock: Clock,
    private val airbrake: AirbrakeNotifier) extends ActivityEmailLibraryMembershipHelpers {

  def apply(toUserId: Id[User], previouslySent: Seq[ActivityEmail], friends: Set[Id[User]]): Future[Seq[LibraryInfoFollowersView]] = {
    val since: DateTime = lastEmailSentAt(previouslySent)
    val ownedLibraries = db.readOnlyReplica { implicit session =>
      libraryRepo.getAllByOwner(toUserId) map { l => l.id.get -> l }
    }.toMap

    val librariesToMembers = libraryInfoCommander.sortAndSelectLibrariesWithTopGrowthSince(ownedLibraries.keySet,
      since, (id: Id[Library]) => ownedLibraries(id).memberCount)
    val librariesToMembersMap = librariesToMembers.toMap
    createLibraryInfoFollowersViews(toUserId, ownedLibraries, librariesToMembersMap, friends)
  }

}
