package com.keepit.commanders.emails.activity

import com.google.inject.Inject
import com.keepit.commanders.LibraryCommander
import com.keepit.commanders.emails.{ BaseLibraryInfoView, LibraryInfoView }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.time.Clock
import com.keepit.model.{ LibraryRepo, LibraryMembershipRepo, ActivityEmail, LibraryMembershipStates, User }
import org.joda.time.DateTime
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future

class UserLibraryFollowersComponent @Inject() (val libraryCommander: LibraryCommander,
    val clock: Clock,
    db: Database,
    membershipRepo: LibraryMembershipRepo,
    libraryRepo: LibraryRepo) extends ActivityEmailLibraryHelpers {

  def apply(toUserId: Id[User], previouslySent: Seq[ActivityEmail]): Future[Seq[(LibraryInfoView, Seq[Id[User]])]] = {
    val since = previouslySent.headOption.map(_.createdAt).getOrElse(minRecordAge)

    val librariesToMembers = this.librariesToMembers(toUserId, since)
    val libraries = librariesToMembers.map(_._1)
    val libInfosF = createFullLibraryInfos(toUserId, libraries)
    libInfosF map { libInfos =>
      libInfos map {
        case (libId, libInfo) =>

          val librariesToMembersMap = librariesToMembers.map {
            case (lib, members) => (libId, members)
          }.toMap

          val members = librariesToMembersMap(libId) map (_.userId)
          val libInfoView = BaseLibraryInfoView(libId, libInfo)
          (libInfoView, members)
      }
    }
  }

  def librariesToMembers(toUserId: Id[User], since: DateTime) = db.readOnlyReplica { implicit session =>
    val mostMembersLibraries = membershipRepo.mostMembersSinceForUser(10, since, toUserId)
    val libraries = libraryRepo.getLibraries(mostMembersLibraries.map(_._1).toSet)

    mostMembersLibraries sortBy {
      case (libraryId, membersSince) =>
        val library = libraries(libraryId)

        // sorts by the highest growth of members in libraries since the last email (descending)
        // squaring membersSince gives libraries with a higher growth count the advantage
        Math.pow(membersSince, 2) / -library.memberCount.toFloat
    } map {
      case (libraryId, membersSince) =>
        val library = libraries(libraryId)

        // gets followers of library and orders them by when they last joined desc
        val members = membershipRepo.getWithLibraryId(library.id.get) filter { membership =>
          membership.state == LibraryMembershipStates.ACTIVE && !membership.isOwner
        } sortBy (-_.lastJoinedAt.map(_.getMillis).getOrElse(0L))

        (library, members)
    }
  }
}
