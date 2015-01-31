package com.keepit.commanders.emails.activity

import com.google.inject.Inject
import com.keepit.commanders.LibraryCommander
import com.keepit.commanders.emails.{ BaseLibraryInfoView, LibraryInfoView }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.time.Clock
import com.keepit.model.{ Library, ActivityEmail, LibraryMembershipRepo, LibraryRepo, User }
import org.joda.time.DateTime
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future

class UserLibraryFollowersComponent @Inject() (val libraryCommander: LibraryCommander,
    val clock: Clock,
    val membershipRepo: LibraryMembershipRepo,
    db: Database,
    libraryRepo: LibraryRepo) extends ActivityEmailLibraryHelpers {

  def apply(toUserId: Id[User], previouslySent: Seq[ActivityEmail]): Future[Seq[(LibraryInfoView, Seq[Id[User]])]] = {
    val since = previouslySent.headOption.map(_.createdAt).getOrElse(minRecordAge)

    val (librariesToMembers, libraries) = this.librariesToMembers(toUserId, since)
    val libInfosF = createFullLibraryInfos(toUserId, libraries.map(_._2).toSeq)
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

  private def librariesToMembers(toUserId: Id[User], since: DateTime) = db.readOnlyReplica { implicit session =>
    val mostMembersLibraries = membershipRepo.mostMembersSinceForUser(10, since, toUserId).toMap
    val libraries = libraryRepo.getLibraries(mostMembersLibraries.map(_._1).toSet)
    val libToMembers = libraryCommander.sortAndSelectLibrariesWithTopGrowthSince(mostMembersLibraries, since, (id: Id[Library]) => libraries(id).memberCount)
    (libToMembers, libraries)
  }
}
