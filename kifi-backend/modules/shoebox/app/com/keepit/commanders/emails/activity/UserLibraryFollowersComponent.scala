package com.keepit.commanders.emails.activity

import com.google.inject.Inject
import com.keepit.commanders.LibraryCommander
import com.keepit.commanders.emails.LibraryInfoFollowersView
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.time.Clock
import com.keepit.model.{ ActivityEmail, Library, LibraryMembershipRepo, LibraryRepo, User }
import org.joda.time.DateTime

import scala.concurrent.Future

class UserLibraryFollowersComponent @Inject() (val libraryCommander: LibraryCommander,
    val clock: Clock,
    val membershipRepo: LibraryMembershipRepo,
    db: Database,
    libraryRepo: LibraryRepo) extends ActivityEmailLibraryMembershipHelpers {

  def apply(toUserId: Id[User], previouslySent: Seq[ActivityEmail], friends: Set[Id[User]]): Future[Seq[LibraryInfoFollowersView]] = {
    val since = lastEmailSentAt(previouslySent)
    val (librariesToMembers, libraries) = this.librariesToMembers(toUserId, since)
    val librariesToMembersMap = librariesToMembers.toMap
    createLibraryInfoFollowersViews(toUserId, libraries, librariesToMembersMap, friends)
  }

  private def librariesToMembers(toUserId: Id[User], since: DateTime) = db.readOnlyReplica { implicit session =>
    val mostMembersLibraries = membershipRepo.mostMembersSinceForUser(10, since, toUserId).toMap
    val libraries = libraryRepo.getLibraries(mostMembersLibraries.map(_._1).toSet)
    val libToMembers = libraryCommander.sortAndSelectLibrariesWithTopGrowthSince(mostMembersLibraries, since, (id: Id[Library]) => libraries(id).memberCount)
    (libToMembers, libraries)
  }
}
