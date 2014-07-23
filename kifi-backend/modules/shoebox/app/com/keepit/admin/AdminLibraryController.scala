package com.keepit.controllers.admin

import com.google.inject.Inject
import com.keepit.commanders.LibraryCommander
import com.keepit.common.controller.{ ActionAuthenticator, AdminController }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.model.{ LibraryMembershipRepo, LibraryRepo, KeepRepo, User }

class AdminLibraryController @Inject() (
    actionAuthenticator: ActionAuthenticator,
    keepRepo: KeepRepo,
    libraryRepo: LibraryRepo,
    libraryMembershipRepo: LibraryMembershipRepo,
    libraryCommander: LibraryCommander,
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
}

