package com.keepit.commanders

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.db.slick.Database
import com.keepit.common.social.BasicUserRepo
import com.keepit.model._

@Singleton
class LibraryPathCommander @Inject() (
    db: Database,
    orgRepo: OrganizationRepo,
    basicUserRepo: BasicUserRepo,
    userRepo: UserRepo) {

  def getPath(lib: Library): String = {
    val (user, org) = db.readOnlyMaster { implicit s =>
      val user = basicUserRepo.load(lib.ownerId)
      val org = lib.organizationId.map(orgRepo.get(_))
      (user, org)
    }
    LibraryPathHelper.formatLibraryPath(user, org.map(_.handle), lib.slug)
  }

  def getPathUrlEncoded(lib: Library): String = {
    val (user, org) = db.readOnlyMaster { implicit s =>
      val user = basicUserRepo.load(lib.ownerId)
      val org = lib.organizationId.map(orgRepo.get(_))
      (user, org)
    }

    LibraryPathHelper.formatLibraryPathUrlEncoded(user, org.map(_.handle), lib.slug)
  }
}
