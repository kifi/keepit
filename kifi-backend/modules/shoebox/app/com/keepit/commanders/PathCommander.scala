package com.keepit.commanders

import java.net.URLEncoder

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db.slick.Database
import com.keepit.common.mail.EmailAddress
import com.keepit.common.net.{ Query, Param }
import com.keepit.common.path.Path
import com.keepit.common.social.BasicUserRepo
import com.keepit.discussion.Message
import com.keepit.model.LibrarySpace.{ OrganizationSpace, UserSpace }
import com.keepit.model._
import com.keepit.shoebox.path.{ ShortenedPath, ShortenedPathRepo }
import com.keepit.slack.models.{ SlackTeamMembership, SlackUserId, SlackTeamId }
import com.keepit.social.BasicUser

@Singleton
class PathCommander @Inject() (
    db: Database,
    libRepo: LibraryRepo,
    orgRepo: OrganizationRepo,
    basicUserRepo: BasicUserRepo,
    shortenedPathRepo: ShortenedPathRepo,
    implicit val config: PublicIdConfiguration) {

  /**
   * USER
   */
  private def profilePageByHandle(handle: Username) = Path(handle.value)

  def profilePage(user: User): Path = profilePageByHandle(user.primaryUsername.get.normalized)
  def profilePage(user: BasicUser): Path = profilePageByHandle(user.username)

  /**
   * ORGANIZATION
   */
  private def orgPageByHandle(handle: OrganizationHandle): Path = Path(handle.value)
  private def orgMembersPageByHandle(handle: OrganizationHandle): Path = orgPageByHandle(handle) + "/members"
  private def orgLibrariesPageByHandle(handle: OrganizationHandle): Path = orgPageByHandle(handle)
  private def orgPlanPageByHandle(handle: OrganizationHandle): Path = orgPageByHandle(handle) + "/settings/plan"
  private def orgIntegrationsPageByHandle(handle: OrganizationHandle): Path = orgPageByHandle(handle) + "/settings/integrations"

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

  def orgIntegrationsPage(org: Organization): Path = orgIntegrationsPageByHandle(org.primaryHandle.get.normalized)

  private def orgPageViaSlackByPublicId(orgId: PublicId[Organization], slackTeamId: SlackTeamId): Path = Path(s"s/${slackTeamId.value}/o/${orgId.id}")

  def orgPageViaSlack(org: Organization, slackTeamId: SlackTeamId): Path = orgPageViaSlackByPublicId(Organization.publicId(org.id.get), slackTeamId)
  def orgPageViaSlack(org: BasicOrganization, slackTeamId: SlackTeamId): Path = orgPageViaSlackByPublicId(org.orgId, slackTeamId)

  private def orgIntegrationsPageViaSlackByPublicId(orgId: PublicId[Organization], slackTeamId: SlackTeamId): Path = Path(s"s/${slackTeamId.value}/oi/${orgId.id}")
  def orgIntegrationsPageViaSlack(org: BasicOrganization, slackTeamId: SlackTeamId): Path = orgIntegrationsPageViaSlackByPublicId(org.orgId, slackTeamId)
  def orgIntegrationsPageViaSlack(org: Organization, slackTeamId: SlackTeamId): Path = orgIntegrationsPageViaSlackByPublicId(Organization.publicId(org.id.get), slackTeamId)

  /**
   * LIBRARY
   */
  def libPageByHandleAndSlug(handle: Handle, slug: LibrarySlug): Path = Path(s"${handle.value}/${slug.value}")

  def libraryPage(lib: Library)(implicit session: RSession): Path = {
    val handle: Handle = lib.space match {
      case UserSpace(userId) => basicUserRepo.load(userId).username
      case OrganizationSpace(orgId) => orgRepo.get(orgId).primaryHandle.get.normalized
    }
    libPageByHandleAndSlug(handle, lib.slug)
  }
  def libraryPageById(libId: Id[Library])(implicit session: RSession): Path = libraryPage(libRepo.get(libId))
  def libraryPageViaSlack(lib: Library, slackTeamId: SlackTeamId): Path = Path(s"s/${slackTeamId.value}/l/${Library.publicId(lib.id.get).id}")

  /**
   * KEEP
   */
  private def keepPageViaSlack(keep: Keep, slackTeamId: SlackTeamId): Path = {
    Path(s"s/${slackTeamId.value}/k/${Keep.publicId(keep.id.get).id}/${UrlHash.hashUrl(keep.url).urlEncoded}")
  }
  def keepPageOnKifiViaSlack(keep: Keep, slackTeamId: SlackTeamId): Path = keepPageViaSlack(keep, slackTeamId) + "/kifi"
  def keepPageOnUrlViaSlack(keep: Keep, slackTeamId: SlackTeamId): Path = keepPageViaSlack(keep, slackTeamId) + "/web"
  def userPageViaSlack(basicUser: BasicUser, slackTeamId: SlackTeamId): Path = Path(s"s/${slackTeamId.value}/u/${basicUser.externalId.id}")

  def keepPageOnMessageViaSlack(keep: Keep, slackTeamId: SlackTeamId, messageId: Id[Message]) = {
    Path(s"s/${slackTeamId.value}/k/${Keep.publicId(keep.id.get).id}/${UrlHash.hashUrl(keep.url).urlEncoded}/m/${Message.publicId(messageId).id}")
  }

  /**
   * MISCELLANEOUS
   */
  def browserExtensionViaSlack(slackTeamId: SlackTeamId): Path = Path(s"s/${slackTeamId.value}/i")
  def tagSearchPath(tag: String) = PathCommander.tagSearchPath(tag)
  def slackPersonalDigestToggle(slackTeamId: SlackTeamId, slackUserId: SlackUserId, turnOn: Boolean) = Path(s"s/pd/${slackTeamId.value}/${slackUserId.value}/${SlackTeamMembership.encodeTeamAndUser(slackTeamId, slackUserId)}").withQuery(Query("turnOn" -> turnOn.toString))
  def welcomePageViaSlack(basicUser: BasicUser, slackTeamId: SlackTeamId): Path = Path(s"s/${slackTeamId.value}/welcome/${basicUser.externalId.id}")
  def ownKeepsFeedPage: Path = Path(s"/?filter=own")
  def ownKeepsFeedPageViaSlack(slackTeamId: SlackTeamId): Path = Path(s"s/${slackTeamId.value}/feed/own")
  def startWithSlackPath(slackTeamId: Option[SlackTeamId], extraScopes: Option[String]): Path = Path(com.keepit.controllers.core.routes.AuthController.startWithSlack(slackTeamId, extraScopes).url)

  def shortened(sp: ShortenedPath): Path = Path(s"/sp/${ShortenedPath.publicId(sp.id.get).id}")
  def shorten(path: Path)(implicit session: RWSession): ShortenedPath = shortenedPathRepo.intern(path)

  /**
   * INTERSECTION
   */

  def intersectionPage(uriId: PublicId[NormalizedURI]): Path = Path(s"/int?uri=${uriId.id}")
  def intersectionPageForUser(uriId: PublicId[NormalizedURI], userId: ExternalId[User]): Path = Path(s"/int?uri=${uriId.id}&user=${userId.id}")
  def intersectionPageForLibrary(uriId: PublicId[NormalizedURI], libId: PublicId[Library]): Path = Path(s"/int?uri=${uriId.id}&library=${libId.id}")
  def intersectionPageForEmail(uriId: PublicId[NormalizedURI], email: EmailAddress): Path = Path(s"/int?uri=${uriId.id}&email=${email.address}")

  /**
   * I'd prefer if you just didn't use these routes
   */
  def pathForLibrary(lib: Library): Path = Path(getPathForLibrary(lib))
  def pathForUser(user: User): Path = Path(user.username.value)
  def pathForKeep(keep: Keep): Path = keep.path
  def pathForOrganization(org: Organization): Path = Path(org.handle.value)

  // todo: remove these and replace with Path-returning versions
  def getPathForLibrary(lib: Library): String = {
    val (user, org) = db.readOnlyMaster { implicit s =>
      val user = basicUserRepo.load(lib.ownerId)
      val org = lib.organizationId.map(orgRepo.get)
      (user, org)
    }
    LibraryPathHelper.formatLibraryPath(user.username, org.map(_.handle), lib.slug)
  }

  // todo: remove this as well
  def getPathForLibraryUrlEncoded(lib: Library): String = {
    val (user, org) = db.readOnlyMaster { implicit s =>
      val user = basicUserRepo.load(lib.ownerId)
      val org = lib.organizationId.map(orgRepo.get(_))
      (user, org)
    }

    LibraryPathHelper.formatLibraryPathUrlEncoded(user.username, org.map(_.handle), lib.slug)
  }
}

object PathCommander {
  val home = Path("")
  def tagSearchPath(tag: String) = Path("find?q=" + URLEncoder.encode(s"""tag:"$tag"""", "ascii"))
  val browserExtension = Path("install")
  val settingsPage = Path("settings")
  val iOS = "https://itunes.apple.com/us/app/kifi-knowledge-management/id740232575?mt=8"
  val android = "https://play.google.com/store/apps/details?id=com.kifi&hl=en"
}
