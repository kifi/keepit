package com.keepit.commanders

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.db.slick.Database
import com.keepit.common.path.Path
import com.keepit.common.social.BasicUserRepo
import com.keepit.model._

@Singleton
class PathCommander @Inject() (
    db: Database,
    orgRepo: OrganizationRepo,
    basicUserRepo: BasicUserRepo,
    userRepo: UserRepo) {

  def pathForLibrary(lib: Library): Path = Path(getPathForLibrary(lib))

  def pathForUser(user: User): Path = Path(user.username.value)

  def pathFororganization(org: Organization): Path = Path(org.handle.value)

  // todo: remove these and replace with Path-returning versions
  def getPathForLibrary(lib: Library): String = {
    val (user, org) = db.readOnlyMaster { implicit s =>
      val user = basicUserRepo.load(lib.ownerId)
      val org = lib.organizationId.map(orgRepo.get(_))
      (user, org)
    }
    LibraryPathHelper.formatLibraryPath(user, org.map(_.handle), lib.slug)
  }

  // todo: remove this as well
  def getPathForLibraryUrlEncoded(lib: Library): String = {
    val (user, org) = db.readOnlyMaster { implicit s =>
      val user = basicUserRepo.load(lib.ownerId)
      val org = lib.organizationId.map(orgRepo.get(_))
      (user, org)
    }

    LibraryPathHelper.formatLibraryPathUrlEncoded(user, org.map(_.handle), lib.slug)
  }
}
