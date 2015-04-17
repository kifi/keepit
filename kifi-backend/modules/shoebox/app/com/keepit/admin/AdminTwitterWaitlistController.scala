package com.keepit.controllers.admin

import com.google.inject.Inject
import com.keepit.commanders.TwitterWaitlistCommander
import com.keepit.common.controller.{ AdminUserActions, UserActionsHelper }
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.mail.EmailAddress
import com.keepit.model._
import play.twirl.api.Html
import views.html

class AdminTwitterWaitlistController @Inject() (
    val userActionsHelper: UserActionsHelper,
    twitterWaitlistCommander: TwitterWaitlistCommander,
    db: Database,
    userRepo: UserRepo,
    libraryRepo: LibraryRepo,
    twitterSyncStateRepo: TwitterSyncStateRepo,
    implicit val publicIdConfig: PublicIdConfiguration) extends AdminUserActions {

  def getWaitlist() = AdminUserPage { implicit request =>
    val entriesList = twitterWaitlistCommander.getWaitlist
    Ok(html.admin.twitterWaitlist(entriesList))
  }

  def acceptUser(userId: Id[User], handle: String) = AdminUserPage { implicit request =>
    val result = twitterWaitlistCommander.acceptUser(userId, handle).right.map { syncState =>
      val (lib, owner) = db.readOnlyMaster { implicit s =>
        val lib = libraryRepo.get(syncState.libraryId)
        val owner = userRepo.get(lib.ownerId)
        (lib, owner)
      }
      val libraryPath = Library.formatLibraryPath(owner.username, lib.slug)
      (syncState, libraryPath, owner.primaryEmail)
    }
    Ok(html.admin.twitterWaitlistAccept(result))
  }

}
