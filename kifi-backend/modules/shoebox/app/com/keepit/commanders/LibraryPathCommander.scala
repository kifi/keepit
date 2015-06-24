package com.keepit.commanders

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.db.slick.Database
import com.keepit.model.{ OrganizationRepo, Library, UserRepo }

@Singleton
class LibraryPathCommander @Inject() (
    db: Database,
    orgRepo: OrganizationRepo,
    userRepo: UserRepo) {
  def getPath(lib: Library): String = {
    val (user, org) = db.readOnlyMaster { implicit s =>
      val user = userRepo.get(lib.ownerId)
      val org = lib.organizationId.flatMap(orgRepo.get(_).handle)
      (user, org)
    }
    Library.formatLibraryPath(user.username, org, lib.slug)
  }

  def getPathUrlEncoded(lib: Library): String = {
    val (user, org) = db.readOnlyMaster { implicit s =>
      val user = userRepo.get(lib.ownerId)
      val org = lib.organizationId.flatMap(orgRepo.get(_).handle)
      (user, org)
    }
    Library.formatLibraryPathUrlEncoded(user.username, org, lib.slug)
  }
}
