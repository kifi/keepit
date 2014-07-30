package com.keepit.controllers.admin

import com.google.inject.Inject
import com.keepit.commanders.LibraryCommander
import com.keepit.common.controller.{ ActionAuthenticator, AdminController }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.model._
import views.html

case class LibraryStatistic(
  library: Library,
  owner: User,
  numKeeps: Int,
  numMembers: Int,
  numInvites: Int)

case class LibraryPageInfo(
  libraryStats: Seq[LibraryStatistic],
  libraryCount: Int,
  page: Int,
  pageSize: Int)

class AdminLibraryController @Inject() (
    actionAuthenticator: ActionAuthenticator,
    keepRepo: KeepRepo,
    libraryRepo: LibraryRepo,
    libraryMembershipRepo: LibraryMembershipRepo,
    libraryInviteRepo: LibraryInviteRepo,
    libraryCommander: LibraryCommander,
    userRepo: UserRepo,
    db: Database) extends AdminController(actionAuthenticator) {

  def index(page: Int = 0) = AdminHtmlAction.authenticated { implicit request =>
    val pageSize = 30
    val (stats, totalCount) = db.readOnlyReplica { implicit session =>
      val stats = libraryRepo.page(page, size = pageSize).map { lib =>
        val owner = userRepo.get(lib.ownerId)
        val keeps = keepRepo.getByLibrary(lib.id.get)
        val memberships = libraryMembershipRepo.getWithLibraryId(lib.id.get)
        val invites = libraryInviteRepo.getWithLibraryId(lib.id.get)
        LibraryStatistic(lib, owner, keeps.length, memberships.length, invites.length)
      }
      (stats, libraryRepo.count)
    }
    val info = LibraryPageInfo(libraryStats = stats, libraryCount = totalCount, page = page, pageSize = pageSize)
    Ok(html.admin.libraries(info))
  }

  def internUserSystemLibraries(userId: Id[User]) = AdminHtmlAction.authenticated { implicit request =>
    val res = libraryCommander.internSystemGeneratedLibraries(userId)

    Ok(res.toString)
  }

  def internAllUserSystemLibraries(startingUserId: Id[User], endingUserId: Id[User]) = AdminHtmlAction.authenticated { implicit request =>
    val ids = (startingUserId.id to endingUserId.id).map(Id[User])

    val confirmedIds = db.readOnlyReplica { implicit session =>
      ids.map { idCandidate =>
        scala.util.Try(userRepo.getNoCache(idCandidate)).toOption.map(_.id.get)
      }
    }.flatten

    val result = confirmedIds.map { userId =>
      userId.id + " -> " + libraryCommander.internSystemGeneratedLibraries(userId)
    }

    Ok(s"count: ${result.size}<br>\n<br>\n" + result.mkString("<br>\n"))
  }
}

