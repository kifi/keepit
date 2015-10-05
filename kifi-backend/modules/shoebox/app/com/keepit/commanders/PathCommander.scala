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

  // TODO(ryan): I feel bad for "fixing" this problem like this, but a bunch of existing
  // code directly calls getPathForLibrary, which calls LibraryPathHelper, and that class
  // does The Wrong Thing (it explicitly puts the "/" at the beginning of the link), while
  // a `Path` assumes that there is no leading slash.
  def pathForLibrary(lib: Library): Path = Path(getPathForLibrary(lib).tail)

  def pathForUser(user: User): Path = Path(user.username.value)

  def pathForOrganization(org: Organization): Path = Path(org.handle.value)

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
