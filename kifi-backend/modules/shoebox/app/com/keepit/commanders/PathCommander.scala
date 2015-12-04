package com.keepit.commanders

import java.net.URLEncoder

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.path.Path
import com.keepit.common.social.BasicUserRepo
import com.keepit.model._

@Singleton
class PathCommander @Inject() (
    db: Database,
    orgRepo: OrganizationRepo,
    basicUserRepo: BasicUserRepo,
    implicit val config: PublicIdConfiguration) {

  // TODO(ryan): I feel bad for "fixing" this problem like this, but a bunch of existing
  // code directly calls getPathForLibrary, which calls LibraryPathHelper, and that class
  // does The Wrong Thing (it explicitly puts the "/" at the beginning of the link), while
  // a `Path` assumes that there is no leading slash.
  def pathForLibrary(lib: Library): Path = Path(getPathForLibrary(lib).tail)

  def pathForUser(user: User): Path = Path(user.username.value)

  def pathForKeep(keep: Keep): Path = keep.path

  def pathForOrganization(org: Organization): Path = Path(org.handle.value)

  private def orgPageByHandle(handle: OrganizationHandle): Path = Path(handle.value)
  private def orgMembersPageByHandle(handle: OrganizationHandle): Path = orgPageByHandle(handle) + "/members"
  private def orgLibrariesPageByHandle(handle: OrganizationHandle): Path = orgPageByHandle(handle)
  private def orgPlanPageByHandle(handle: OrganizationHandle): Path = orgPageByHandle(handle) + "/settings/plan"

  def orgPage(org: Organization): Path = orgPageByHandle(org.primaryHandle.get.normalized)
  def orgPage(org: BasicOrganization): Path = orgPageByHandle(org.handle)
  def orgPageById(orgId: Id[Organization])(implicit session: RSession): Path = orgPage(orgRepo.get(orgId))

  def orgMembersPage(org: Organization): Path = orgMembersPageByHandle(org.primaryHandle.get.normalized)
  def orgMembersPage(org: BasicOrganization): Path = orgMembersPageByHandle(org.handle)
  def orgMembersPageById(orgId: Id[Organization])(implicit session: RSession): Path = orgMembersPage(orgRepo.get(orgId))

  def orgLibrariesPage(org: Organization): Path = orgLibrariesPageByHandle(org.primaryHandle.get.normalized)
  def orgLibrariesPage(org: BasicOrganization): Path = orgLibrariesPageByHandle(org.handle)
  def orgLibrariesPageById(orgId: Id[Organization])(implicit session: RSession): Path = orgLibrariesPage(orgRepo.get(orgId))

  def orgPlanPage(org: Organization): Path = orgPlanPageByHandle(org.primaryHandle.get.normalized)
  def orgPlanPage(org: BasicOrganization): Path = orgPlanPageByHandle(org.handle)
  def orgPlanPageById(orgId: Id[Organization])(implicit session: RSession): Path = orgPlanPage(orgRepo.get(orgId))

  def tagSearchPath(tag: String) = PathCommander.tagSearchPath(tag)

  // todo: remove these and replace with Path-returning versions
  def getPathForLibrary(lib: Library): String = {
    val (user, org) = db.readOnlyMaster { implicit s =>
      val user = basicUserRepo.load(lib.ownerId)
      val org = lib.organizationId.map(orgRepo.get)
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

object PathCommander {
  def tagSearchPath(tag: String) = Path("find?q=" + URLEncoder.encode(s"""tag:"$tag"""", "ascii"))
}
