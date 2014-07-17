package com.keepit.controllers.admin

import com.google.inject.Inject
import com.keepit.commanders.LibraryCommander
import com.keepit.common.controller.{ ActionAuthenticator, AdminController }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.model.{ KeepRepo, User }

class AdminLibraryController @Inject() (
    actionAuthenticator: ActionAuthenticator,
    keepRepo: KeepRepo,
    libraryCommander: LibraryCommander,
    db: Database) extends AdminController(actionAuthenticator) {

  def index() = AdminHtmlAction.authenticated { implicit request =>
    Ok
  }

  def internUserSystemLibraries(userId: Id[User]) = AdminHtmlAction.authenticated { implicit request =>
    val res = libraryCommander.internSystemGeneratedLibraries(userId)

    Ok(res.toString)
  }
}

