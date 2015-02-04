package com.keepit.commanders.emails.activity

import com.google.inject.Inject
import com.keepit.commanders.LibraryCommander
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.time.Clock
import com.keepit.model.{ FullLibraryInfo, Library, LibraryInvite, LibraryInviteRepo, User }
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future
class LibraryInviteCountComponent @Inject() (db: Database, libraryInviteRepo: LibraryInviteRepo) {

  def apply(recipientId: Id[User]): Future[Int] = {
    db.readOnlyReplicaAsync { implicit session => libraryInviteRepo.countDistinctWithUserId(recipientId) }
  }

}

class LibraryInviteComponent @Inject() (db: Database,
    libraryInviteRepo: LibraryInviteRepo,
    val clock: Clock,
    val libraryCommander: LibraryCommander) extends ActivityEmailLibraryHelpers {

  // max number of pending invited-to libraries to display
  val maxInvitedLibraries = 5

  def apply(userId: Id[User]): Future[Seq[(Id[Library], FullLibraryInfo, Seq[LibraryInvite])]] = {
    val invitedLibraries = db.readOnlyReplica { implicit session =>
      import com.keepit.model.LibraryInviteStates._
      libraryInviteRepo.getByUser(userId, Set(ACCEPTED, DECLINED, INACTIVE))
    }

    val libraries = invitedLibraries map (_._2) groupBy (_.id) map (_._2.head) toSeq
    // groupBy().map().toSeq above is a hack around duplicate library invites from the same user (which is/was possible and should be cleaned up)
    val invitesByLibraryId = invitedLibraries map (_._1) groupBy (_.libraryId)
    val fullLibraryInfosF = createFullLibraryInfos(userId, libraries)

    fullLibraryInfosF map { fullLibraryInfos =>
      fullLibraryInfos map {
        case (libraryId, fullLibInfo) => (libraryId, fullLibInfo, invitesByLibraryId(libraryId))
      } take maxInvitedLibraries
    }
  }

}
