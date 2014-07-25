package com.keepit.controllers.admin

import com.google.inject.Inject
import com.keepit.commanders.LibraryCommander
import com.keepit.common.controller.{ ActionAuthenticator, AdminController }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.model._

class AdminLibraryController @Inject() (
    actionAuthenticator: ActionAuthenticator,
    keepRepo: KeepRepo,
    libraryRepo: LibraryRepo,
    libraryMembershipRepo: LibraryMembershipRepo,
    libraryCommander: LibraryCommander,
    userRepo: UserRepo,
    db: Database) extends AdminController(actionAuthenticator) {

  def index(page: Int = 0) = AdminHtmlAction.authenticated { implicit request =>
    val libs = db.readOnlyReplica { implicit session =>
      libraryRepo.page(page, size = 30)
    }
    Ok(libs.mkString("\n")) // todo(andrew): make this better ;)
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

