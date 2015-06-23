package com.keepit.commanders

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.db.slick.Database
import com.keepit.model.{ OrganizationRepo, LibraryPathHelper, Library, UserRepo }

@Singleton
class LibraryPathCommander @Inject() (
    db: Database,
    orgRepo: OrganizationRepo,
    userRepo: UserRepo) extends LibraryPathHelper {
  def getPath(lib: Library): String = {
    val (user, org) = db.readOnlyMaster { implicit s =>
      val user = userRepo.get(lib.ownerId)
      val org = lib.organizationId.flatMap(orgRepo.get(_).handle)
      (user, org)
    }
    formatLibraryPath(user.username, org, lib.slug)
  }

  def getPathUrlEncoded(lib: Library): String = {
    val (user, org) = db.readOnlyMaster { implicit s =>
      val user = userRepo.get(lib.ownerId)
      val org = lib.organizationId.flatMap(orgRepo.get(_).handle)
      (user, org)
    }
    formatLibraryPathUrlEncoded(user.username, org, lib.slug)
  }
}
