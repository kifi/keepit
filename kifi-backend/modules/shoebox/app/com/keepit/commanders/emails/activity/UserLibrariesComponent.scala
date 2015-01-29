package com.keepit.commanders.emails.activity

import com.keepit.commanders.emails.{ BaseLibraryInfoView, LibraryInfoView }
import com.keepit.common.db.Id
import com.keepit.model.{ ActivityEmail, LibraryMembershipStates, User }
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future

class UserLibrariesComponent(val toUserId: Id[User], val previouslySent: Seq[ActivityEmail]) extends ActivityEmailLibraryHelpers { this: ActivityEmailDependencies =>
  def apply(): Future[Seq[(LibraryInfoView, Seq[Id[User]])]] = {
    val libraries = librariesToMembers.map(_._1)
    val libInfosF = createFullLibraryInfos(libraries)
    libInfosF map { libInfos =>
      libInfos map {
        case (libId, libInfo) =>
          val members = librariesToMembersMap(libId) map (_.userId)
          val libInfoView = BaseLibraryInfoView(libId, libInfo)
          (libInfoView, members)
      }
    }
  }

  lazy val librariesToMembers = db.readOnlyReplica { implicit session =>
    val mostMembersLibraries = membershipRepo.mostMembersSinceForUser(10, lastEmailSentAt, toUserId)
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

  lazy val librariesToMembersMap = librariesToMembers.map {
    case (lib, members) => (lib.id.get, members)
  }.toMap

}
