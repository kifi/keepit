package com.keepit.commanders

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.commanders.LibraryQuery.{ ForOrg, Arrangement }
import com.keepit.common.akka.SafeFuture
import com.keepit.common.core._
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail.{ BasicContact, EmailAddress }
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.store.ImageSize
import com.keepit.common.time._
import com.keepit.heimdal.HeimdalContext
import com.keepit.model._
import com.keepit.slack.{ LibrarySlackInfo, SlackInfoCommander }
import com.keepit.social.{ BasicNonUser, BasicUser }
import org.joda.time.DateTime
import play.api.http.Status._

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.Try

@ImplementedBy(classOf[LibraryInfoCommanderImpl])
trait LibraryInfoCommander {
  def sortUsersByImage(users: Seq[BasicUser]): Seq[BasicUser]
  def getKeeps(libraryId: Id[Library], offset: Int, limit: Int): Seq[Keep]
  def getKeepsCount(libraryId: Id[Library]): Future[Int]
  def getLibraryById(userIdOpt: Option[Id[User]], showPublishedLibraries: Boolean, id: Id[Library], imageSize: ImageSize, viewerId: Option[Id[User]], sanitizeUrls: Boolean)(implicit context: HeimdalContext): Future[FullLibraryInfo]
  def getLibraryMembersAndInvitees(libraryId: Id[Library], offset: Int, limit: Int, fillInWithInvites: Boolean): Seq[MaybeLibraryMember]
  def getBasicLibraryDetails(libraryIds: Set[Id[Library]], idealImageSize: ImageSize, viewerId: Option[Id[User]]): Map[Id[Library], BasicLibraryDetails]
  def getLibraryWithOwnerAndCounts(libraryId: Id[Library], viewerUserId: Id[User]): Either[LibraryFail, (Library, BasicUser, Int, Option[Boolean], Boolean)]
  def createFullLibraryInfos(viewerUserIdOpt: Option[Id[User]], showPublishedLibraries: Boolean, maxMembersShown: Int, maxKeepsShown: Int, maxMessagesShown: Int, idealKeepImageSize: ImageSize, libraries: Seq[Library], idealLibraryImageSize: ImageSize, sanitizeUrls: Boolean, authTokens: Map[Id[Library], String] = Map.empty): Future[Seq[(Id[Library], FullLibraryInfo)]]
  def createFullLibraryInfo(viewerUserIdOpt: Option[Id[User]], showPublishedLibraries: Boolean, library: Library, libImageSize: ImageSize, authToken: Option[String], sanitizeUrls: Boolean): Future[FullLibraryInfo]
  def getLibrariesByUser(userId: Id[User]): (Seq[(LibraryMembership, Library)], Seq[(LibraryInvite, Library)])
  def getLibrariesUserCanKeepTo(userId: Id[User], includeOrgLibraries: Boolean): Seq[(Library, Option[LibraryMembership], Set[Id[User]])]
  def sortAndSelectLibrariesWithTopGrowthSince(libraryIds: Set[Id[Library]], since: DateTime, totalMemberCount: Id[Library] => Int): Seq[(Id[Library], Seq[LibraryMembership])]
  def sortAndSelectLibrariesWithTopGrowthSince(libraryMemberCountsSince: Map[Id[Library], Int], since: DateTime, totalMemberCount: Id[Library] => Int): Seq[(Id[Library], Seq[LibraryMembership])]
  def getMainAndSecretLibrariesForUser(userId: Id[User])(implicit session: RWSession): (Library, Library)
  def getLibraryWithHandleAndSlug(handle: Handle, slug: LibrarySlug, viewerId: Option[Id[User]])(implicit context: HeimdalContext): Either[LibraryFail, Library]
  def getLibraryBySlugOrAlias(space: LibrarySpace, slug: LibrarySlug): Option[(Library, Boolean)]
  def getOrganizationLibrariesVisibleToUser(orgId: Id[Organization], userIdOpt: Option[Id[User]], offset: Offset, limit: Limit): Seq[LibraryCardInfo]
  def getLibrariesVisibleToUserHelper(orgId: Id[Organization], userIdOpt: Option[Id[User]], offset: Offset, limit: Limit)(implicit session: RSession): Seq[Library]

  def rpbGetUserLibraries(viewer: Option[Id[User]], userId: Id[User], fromIdOpt: Option[Id[Library]], offset: Int, limit: Int): Seq[LibraryCardInfo]
  def rpbGetOrgLibraries(viewer: Option[Id[User]], orgId: Id[Organization], fromIdOpt: Option[Id[Library]], offset: Int, limit: Int): Seq[LibraryCardInfo]
}

@Singleton
class LibraryInfoCommanderImpl @Inject() (
    db: Database,
    libraryQueryCommander: LibraryQueryCommander,
    libraryCardCommander: LibraryCardCommander,
    libraryAliasRepo: LibraryAliasRepo,
    handleCommander: HandleCommander,
    permissionCommander: PermissionCommander,
    organizationMembershipRepo: OrganizationMembershipRepo,
    twitterSyncRepo: TwitterSyncStateRepo,
    keepRepo: KeepRepo,
    libraryMembershipRepo: LibraryMembershipRepo,
    libraryMembershipCommander: LibraryMembershipCommander,
    libraryImageCommander: LibraryImageCommander,
    libraryInviteCommander: LibraryInviteCommander,
    ktlRepo: KeepToLibraryRepo,
    libPathCommander: PathCommander,
    airbrake: AirbrakeNotifier,
    keepCommander: KeepCommander,
    libraryAnalytics: LibraryAnalytics,
    keepDecorator: KeepDecorator,
    orgRepo: OrganizationRepo,
    organizationInfoCommander: OrganizationInfoCommander,
    libraryInviteRepo: LibraryInviteRepo,
    basicUserRepo: BasicUserRepo,
    libraryRepo: LibraryRepo,
    slackInfoCommander: SlackInfoCommander,
    implicit val defaultContext: ExecutionContext,
    implicit val publicIdConfig: PublicIdConfiguration) extends LibraryInfoCommander with Logging {

  def getKeeps(libraryId: Id[Library], offset: Int, limit: Int): Seq[Keep] = {
    if (limit > 0) db.readOnlyReplica(implicit s => ktlRepo.getByLibraryIdSorted(libraryId, Offset(offset), Limit(limit)) |> keepCommander.idsToKeeps)
    else Seq.empty
  }

  def getKeepsCount(libraryId: Id[Library]): Future[Int] = {
    db.readOnlyMasterAsync { implicit s => libraryRepo.get(libraryId).keepCount }
  }

  def getLibraryById(userIdOpt: Option[Id[User]], showPublishedLibraries: Boolean, id: Id[Library], imageSize: ImageSize, viewerId: Option[Id[User]], sanitizeUrls: Boolean)(implicit context: HeimdalContext): Future[FullLibraryInfo] = {
    val lib = db.readOnlyMaster { implicit s => libraryRepo.get(id) }
    libraryAnalytics.viewedLibrary(viewerId, lib, context)
    createFullLibraryInfo(userIdOpt, showPublishedLibraries, lib, imageSize, None, sanitizeUrls = sanitizeUrls)
  }

  def getLibraryPath(library: Library): String = {
    libPathCommander.getPathForLibrary(library)
  }

  def getBasicLibraryDetails(libraryIds: Set[Id[Library]], idealImageSize: ImageSize, viewerId: Option[Id[User]]): Map[Id[Library], BasicLibraryDetails] = {
    db.readOnlyReplica { implicit session =>
      val membershipsByLibraryId = viewerId.fold(Map.empty[Id[Library], LibraryMembership]) { id =>
        libraryMembershipRepo.getWithLibraryIdsAndUserId(libraryIds, id)
      }

      val permissionsById = permissionCommander.getLibrariesPermissions(libraryIds, viewerId)
      libraryRepo.getActiveByIds(libraryIds).map {
        case (libId, lib) =>
          val counts = libraryMembershipRepo.countWithLibraryIdByAccess(libId)
          val numFollowers = if (LibraryMembershipCommander.defaultLibraries.contains(libId)) 0 else counts.readOnly
          val numCollaborators = counts.readWrite
          val imageOpt = libraryImageCommander.getBestImageForLibrary(libId, idealImageSize).map(libraryImageCommander.getUrl)
          val membershipOpt = membershipsByLibraryId.get(libId)
          val path = libPathCommander.pathForLibrary(lib)
          libId -> BasicLibraryDetails(
            lib.name,
            lib.slug,
            lib.color,
            imageOpt,
            lib.description,
            numFollowers,
            numCollaborators,
            lib.keepCount,
            membershipOpt.map(libraryMembershipCommander.createMembershipInfo),
            lib.ownerId,
            path,
            permissionsById(libId)
          )
      }
    }
  }

  def getLibraryWithOwnerAndCounts(libraryId: Id[Library], viewerUserId: Id[User]): Either[LibraryFail, (Library, BasicUser, Int, Option[Boolean], Boolean)] = {
    db.readOnlyReplica { implicit s =>
      val library = libraryRepo.get(libraryId)
      val mine = library.ownerId == viewerUserId
      val memOpt = libraryMembershipRepo.getWithLibraryIdAndUserId(libraryId, viewerUserId)
      val following = if (mine) None else Some(memOpt.isDefined)
      val subscribedToUpdates = memOpt.exists(_.subscribedToUpdates)
      if (library.visibility == LibraryVisibility.PUBLISHED || mine || following.get) {
        val owner = basicUserRepo.load(library.ownerId)
        val followerCount = if (LibraryMembershipCommander.defaultLibraries.contains(library.id.get)) 0 else libraryMembershipRepo.countWithLibraryIdByAccess(library.id.get).readOnly
        Right((library, owner, followerCount, following, subscribedToUpdates))
      } else {
        Left(LibraryFail(FORBIDDEN, "library_access_denied"))
      }
    }
  }

  private case class LibMembersAndCounts(counts: CountWithLibraryIdByAccess, inviters: Seq[Id[User]], collaborators: Seq[Id[User]], followers: Seq[Id[User]]) {
    // Users that should be shown as members. Doesn't include collaborators because they're highighted elsewhere.
    def shown: Seq[Id[User]] = inviters ++ followers.filter(!inviters.contains(_))

    def all = inviters ++ collaborators ++ followers
  }

  private def countMemberInfosByLibraryId(libraries: Seq[Library], maxMembersShown: Int, viewerUserIdOpt: Option[Id[User]]): Map[Id[Library], LibMembersAndCounts] = libraries.map { library =>
    val info: LibMembersAndCounts = library.kind match {
      case LibraryKind.SYSTEM_MAIN | LibraryKind.SYSTEM_SECRET => LibMembersAndCounts(CountWithLibraryIdByAccess.empty, Seq.empty, Seq.empty, Seq.empty)
      case _ =>
        val (collaborators, followers, _, counts) = getLibraryMembersAndCount(library.id.get, 0, maxMembersShown, fillInWithInvites = false)
        val inviters: Seq[LibraryMembership] = viewerUserIdOpt.map { userId =>
          db.readOnlyReplica { implicit session =>
            libraryInviteRepo.getWithLibraryIdAndUserId(library.id.get, userId).filter { invite => //not cached
              invite.inviterId != library.ownerId
            }.map { invite =>
              libraryMembershipRepo.getWithLibraryIdAndUserId(library.id.get, invite.inviterId) //not cached
            }
          }.flatten
        }.getOrElse(Seq.empty)
        LibMembersAndCounts(counts, inviters.map(_.userId), collaborators.map(_.userId), followers.map(_.userId))
    }
    library.id.get -> info
  }.toMap

  def createFullLibraryInfos(viewerUserIdOpt: Option[Id[User]], showPublishedLibraries: Boolean, maxMembersShown: Int, maxKeepsShown: Int, maxMessagesShown: Int,
    idealKeepImageSize: ImageSize, libraries: Seq[Library], idealLibraryImageSize: ImageSize, sanitizeUrls: Boolean, authTokens: Map[Id[Library], String] = Map.empty): Future[Seq[(Id[Library], FullLibraryInfo)]] = {
    libraries.groupBy(l => l.id.get).foreach { case (lib, set) => if (set.size > 1) throw new Exception(s"There are ${set.size} identical libraries of $lib") }
    val libIds = libraries.map(_.id.get).toSet
    val futureKeepInfosByLibraryId = libraries.map { library =>
      library.id.get -> {
        if (maxKeepsShown > 0) {
          val keeps = db.readOnlyMaster { implicit session =>
            library.kind match {
              case LibraryKind.SYSTEM_MAIN => assume(library.ownerId == viewerUserIdOpt.get, s"viewer ${viewerUserIdOpt.get} can't view a system library they do not own: $library")
              case LibraryKind.SYSTEM_SECRET => assume(library.ownerId == viewerUserIdOpt.get, s"viewer ${viewerUserIdOpt.get} can't view a system library they do not own: $library")
              case _ =>
            }
            ktlRepo.getByLibraryIdSorted(library.id.get, Offset(0), Limit(maxKeepsShown)) |> keepCommander.idsToKeeps
          }
          keepDecorator.decorateKeepsIntoKeepInfos(viewerUserIdOpt, showPublishedLibraries, keeps, idealKeepImageSize, maxMessagesShown, sanitizeUrls)
        } else Future.successful(Seq.empty)
      }
    }.toMap

    val memberInfosByLibraryId = countMemberInfosByLibraryId(libraries, maxMembersShown, viewerUserIdOpt)

    val usersByIdF = {
      val allUsersShown = libraries.flatMap { library => memberInfosByLibraryId(library.id.get).all :+ library.ownerId }.toSet
      db.readOnlyReplicaAsync { implicit s => basicUserRepo.loadAll(allUsersShown) } //cached
    }

    val basicOrgViewByIdF = {
      val allOrgsShown = libraries.flatMap { library => library.organizationId }.toSet
      db.readOnlyReplicaAsync { implicit s =>
        organizationInfoCommander.getBasicOrganizationViewsHelper(allOrgsShown, viewerUserIdOpt, authTokenOpt = None)
      }
    }

    val countsByLibraryId = libraries.map { library =>
      library.id.get -> {
        val counts = memberInfosByLibraryId(library.id.get).counts
        val collaboratorCount = counts.readWrite
        // The following line of code hides the number of followers for default libraries
        // This is to prevent real users from seeing how many new users we have
        val followerCount = if (LibraryMembershipCommander.defaultLibraries.contains(library.id.get)) 0 else counts.readOnly
        val keepCount = library.keepCount
        (collaboratorCount, followerCount, keepCount)
      }
    }.toMap

    val imagesF = libraries.map { library =>
      library.id.get -> SafeFuture {
        libraryImageCommander.getBestImageForLibrary(library.id.get, idealLibraryImageSize)
      } //not cached
    }.toMap

    val membershipByLibraryId = viewerUserIdOpt.map { viewerUserId =>
      db.readOnlyMaster { implicit session =>
        libraries.flatMap { library =>
          val libraryId = library.id.get
          libraryMembershipRepo.getWithLibraryIdAndUserId(libraryId, viewerUserId).map { membership =>
            libraryId -> libraryMembershipCommander.createMembershipInfo(membership)
          }
        }
      } toMap
    }

    val inviteByLibraryId = viewerUserIdOpt.map { viewerUserId =>
      db.readOnlyMaster { implicit session =>
        libraries.flatMap { library =>
          val libraryId = library.id.get
          libraryInviteCommander.createInviteInfo(libraryId, viewerUserIdOpt, authTokens.get(libraryId)).map(libraryId -> _)
        }
      } toMap
    }

    val permissionsByLibraryId = db.readOnlyMaster { implicit s => permissionCommander.getLibrariesPermissions(libIds, viewerUserIdOpt) }

    // I refuse to allow something small, like Slack integrations, take down the important stuff like Libraries
    val slackInfoByLibraryId: Map[Id[Library], LibrarySlackInfo] = viewerUserIdOpt.map { viewerId =>
      Try(slackInfoCommander.getSlackIntegrationsForLibraries(viewerId, libIds)).recover {
        case fail =>
          airbrake.notify(s"Exploded while getting Slack integrations for user $viewerId and libraries $libIds", fail)
          Map.empty[Id[Library], LibrarySlackInfo]
      }.get
    }.getOrElse(Map.empty)

    val futureFullLibraryInfos = libraries.map { lib =>
      val libId = lib.id.get
      for {
        keepInfos <- futureKeepInfosByLibraryId(libId)
        usersById <- usersByIdF
        basicOrgViewById <- basicOrgViewByIdF
        libImageOpt <- imagesF(libId)
      } yield {
        val (collaboratorCount, followerCount, keepCount) = countsByLibraryId(libId)
        val owner = usersById(lib.ownerId)
        val orgViewOpt = lib.organizationId.map(basicOrgViewById.apply)
        // The following line of code hides the details of who exactly follows the default libraries
        // This is to prevent real users from seeing all the new users (including the fake users we sometimes create)
        val followers = if (LibraryMembershipCommander.defaultLibraries.contains(libId)) Seq.empty else memberInfosByLibraryId(lib.id.get).shown.map(usersById(_))
        val collaborators = memberInfosByLibraryId(lib.id.get).collaborators.map(usersById(_))
        val whoCanInvite = lib.whoCanInvite.getOrElse(LibraryInvitePermissions.COLLABORATOR) // todo: remove Option
        val attr = getSourceAttribution(libId)

        if (keepInfos.size > keepCount) {
          airbrake.notify(s"keep count $keepCount for library is lower then num of keeps ${keepInfos.size} for $lib")
        }
        libId -> FullLibraryInfo(
          id = Library.publicId(libId),
          name = lib.name,
          owner = owner,
          description = lib.description,
          slug = lib.slug,
          url = libPathCommander.getPathForLibrary(lib),
          color = lib.color,
          kind = lib.kind,
          visibility = lib.visibility,
          image = libImageOpt.map(_.asInfo),
          followers = followers,
          collaborators = collaborators,
          keeps = keepInfos,
          numKeeps = keepCount,
          numCollaborators = collaboratorCount,
          numFollowers = followerCount,
          lastKept = lib.lastKept,
          attr = attr,
          whoCanInvite = whoCanInvite,
          whoCanComment = lib.whoCanComment,
          modifiedAt = lib.updatedAt,
          path = LibraryPathHelper.formatLibraryPath(owner = owner, orgHandleOpt = orgViewOpt.map(_.basicOrganization.handle), slug = lib.slug),
          org = orgViewOpt,
          orgMemberAccess = if (lib.organizationId.isDefined) Some(lib.organizationMemberAccess.getOrElse(LibraryAccess.READ_WRITE)) else None,
          membership = membershipByLibraryId.flatMap(_.get(libId)),
          invite = inviteByLibraryId.flatMap(_.get(libId)),
          permissions = permissionsByLibraryId(libId),
          slack = slackInfoByLibraryId.get(libId)
        )
      }
    }
    Future.sequence(futureFullLibraryInfos)
  }

  private def getSourceAttribution(libId: Id[Library]): Option[LibrarySourceAttribution] = {
    db.readOnlyReplica { implicit s =>
      twitterSyncRepo.getFirstHandleByLibraryId(libId).map(TwitterLibrarySourceAttribution.apply)
    }
  }

  def sortUsersByImage(users: Seq[BasicUser]): Seq[BasicUser] =
    users.sortBy(_.pictureName == BasicNonUser.DefaultPictureName)

  def createFullLibraryInfo(viewerUserIdOpt: Option[Id[User]], showPublishedLibraries: Boolean, library: Library, libImageSize: ImageSize, authToken: Option[String], sanitizeUrls: Boolean): Future[FullLibraryInfo] = {
    val maxMembersShown = 10
    createFullLibraryInfos(viewerUserIdOpt, showPublishedLibraries, maxMembersShown = maxMembersShown * 2, maxKeepsShown = 10, maxMessagesShown = 8, ProcessedImageSize.Large.idealSize, Seq(library), libImageSize, sanitizeUrls, authToken.map(library.id.get -> _).toMap).imap {
      case Seq((_, info)) =>
        val followers = info.followers
        val sortedFollowers = sortUsersByImage(followers)
        info.copy(followers = sortedFollowers.take(maxMembersShown))
    }
  }

  def getLibrariesByUser(userId: Id[User]): (Seq[(LibraryMembership, Library)], Seq[(LibraryInvite, Library)]) = {
    db.readOnlyMaster { implicit s =>
      val myLibraries = libraryRepo.getByUser(userId)
      val myInvites = libraryInviteRepo.getByUser(userId, LibraryInviteStates.notActive)
      (myLibraries, myInvites)
    }
  }

  def getLibrariesUserCanKeepTo(userId: Id[User], includeOrgLibraries: Boolean): Seq[(Library, Option[LibraryMembership], Set[Id[User]])] = {
    db.readOnlyReplica { implicit s =>
      val libsWithMembership: Seq[(Library, LibraryMembership)] = libraryRepo.getLibrariesWithWriteAccess(userId).sortBy(lwm => lwm._1.lastKept).reverse
      val libsWithMembershipIds = libsWithMembership.map(_._1.id.get).toSet

      val libsFromOrganizations: Seq[Library] = if (includeOrgLibraries) {
        for {
          organizationId <- organizationMembershipRepo.getAllByUserId(userId).map(_.organizationId)
          library <- libraryRepo.getLibrariesWithOpenWriteAccess(organizationId) if !libsWithMembershipIds.contains(library.id.get)
        } yield library
      } else Seq.empty

      val memberOrOpenOrgLibIds = libsWithMembershipIds ++ libsFromOrganizations.map(_.id.get).toSet

      // Include org libs that user is invited to
      val libsInvitedToInOrgs = if (includeOrgLibraries) {
        libraryInviteRepo.getByUser(userId, Set(LibraryInviteStates.DECLINED, LibraryInviteStates.ACCEPTED, LibraryInviteStates.INACTIVE)).collect {
          case ((invite, lib)) if invite.isCollaborator && !memberOrOpenOrgLibIds.contains(lib.id.get) && lib.organizationId.isDefined =>
            lib
        }
      } else Seq.empty

      val libIds = memberOrOpenOrgLibIds ++ libsInvitedToInOrgs.map(_.id.get).toSet

      val nonMemberLibraries = (libsInvitedToInOrgs ++ libsFromOrganizations).sortBy(l => l.lastKept).reverse

      val collaborators: Map[Id[Library], Set[Id[User]]] = libraryMembershipRepo.getCollaboratorsByLibrary(libIds)

      libsWithMembership.map {
        case (lib, membership) =>
          (lib, Some(membership), collaborators(lib.id.get))
      } ++ nonMemberLibraries.map { lib => (lib, None, collaborators(lib.id.get)) }
    }
  }

  // sorts by the highest growth of members in libraries since the last email (descending)
  //
  def sortAndSelectLibrariesWithTopGrowthSince(libraryIds: Set[Id[Library]], since: DateTime, totalMemberCount: Id[Library] => Int): Seq[(Id[Library], Seq[LibraryMembership])] = {
    val libraryMemberCountsSince = db.readOnlyReplica { implicit session =>
      libraryIds.map { id => id -> libraryMembershipRepo.countMembersForLibrarySince(id, since) }.toMap
    }

    sortAndSelectLibrariesWithTopGrowthSince(libraryMemberCountsSince, since, totalMemberCount)
  }

  def sortAndSelectLibrariesWithTopGrowthSince(libraryMemberCountsSince: Map[Id[Library], Int], since: DateTime, totalMemberCount: Id[Library] => Int): Seq[(Id[Library], Seq[LibraryMembership])] = {
    libraryMemberCountsSince.toSeq sortBy {
      case (libraryId, membersSince) =>
        val totalMembers = totalMemberCount(libraryId)
        if (totalMembers > 0) {
          // negative number so higher growth rates are sorted at the front of the list
          val growthRate = membersSince.toFloat / -totalMembers

          // multiplier gives more weight to libraries with more members (cap at 30 total before it doesn't grow)
          val multiplier = Math.exp(-10f / totalMembers.min(30))
          growthRate * multiplier
        } else 0
    } map {
      case (libraryId, membersSince) =>

        // gets followers of library and orders them by when they last joined desc
        val members = db.readOnlyReplica { implicit s =>
          libraryMembershipRepo.getWithLibraryId(libraryId) filter { membership =>
            membership.state == LibraryMembershipStates.ACTIVE && !membership.isOwner
          } sortBy (-_.lastJoinedAt.map(_.getMillis).getOrElse(0L))
        }

        (libraryId, members)
    }
  }

  def getMainAndSecretLibrariesForUser(userId: Id[User])(implicit session: RWSession) = {
    val libs = libraryRepo.getByUser(userId)
    val mainOpt = libs.find {
      case (membership, lib) =>
        membership.access == LibraryAccess.OWNER && lib.kind == LibraryKind.SYSTEM_MAIN
    }
    val secretOpt = libs.find {
      case (membership, lib) =>
        membership.access == LibraryAccess.OWNER && lib.kind == LibraryKind.SYSTEM_SECRET
    }
    val (main, secret) = (mainOpt.get._2, secretOpt.get._2)
    (main, secret)
  }

  def getLibraryWithHandleAndSlug(handle: Handle, slug: LibrarySlug, viewerId: Option[Id[User]])(implicit context: HeimdalContext): Either[LibraryFail, Library] = {
    val ownerOpt = db.readOnlyReplica { implicit session => handleCommander.getByHandle(handle) }

    ownerOpt.map {
      case (Left(org), _) => LibrarySpace.fromOrganizationId(org.id.get)
      case (Right(user), _) => LibrarySpace.fromUserId(user.id.get)
    }.map { librarySpace =>
      getLibraryBySlugOrAlias(librarySpace, slug) match {
        case None => Left(LibraryFail(NOT_FOUND, "no_library_found"))
        case Some((library, isLibraryAlias)) =>
          Right(library)
      }
    }.getOrElse(Left(LibraryFail(BAD_REQUEST, "invalid_handle")))
  }

  def getLibraryBySlugOrAlias(space: LibrarySpace, slug: LibrarySlug): Option[(Library, Boolean)] = {
    db.readOnlyMaster { implicit session =>
      libraryRepo.getBySpaceAndSlug(space, slug).map((_, false)) orElse
        libraryAliasRepo.getBySpaceAndSlug(space, slug).map(alias => (libraryRepo.get(alias.libraryId), true)).filter(_._1.state == LibraryStates.ACTIVE)
    }
  }

  def getLibraryMembersAndInvitees(libraryId: Id[Library], offset: Int, limit: Int, fillInWithInvites: Boolean): Seq[MaybeLibraryMember] = {
    val (collaborators, followers, inviteesWithInvites, count) = getLibraryMembersAndCount(libraryId, offset, limit, fillInWithInvites = fillInWithInvites)
    buildMaybeLibraryMembers(collaborators, followers, inviteesWithInvites)
  }

  private def buildMaybeLibraryMembers(collaborators: Seq[LibraryMembership], followers: Seq[LibraryMembership], inviteesWithInvites: Seq[(Either[Id[User], EmailAddress], Set[LibraryInvite])]): Seq[MaybeLibraryMember] = {

    val usersById = {
      val usersShown = collaborators.map(_.userId).toSet ++ followers.map(_.userId) ++ inviteesWithInvites.flatMap(_._1.left.toOption)
      db.readOnlyMaster { implicit session => basicUserRepo.loadAll(usersShown) }
    }

    val actualMembers = (collaborators ++ followers).map { membership =>
      val member = Left(usersById(membership.userId))
      MaybeLibraryMember(member, Some(membership.access), None)
    }

    val invitedMembers = inviteesWithInvites.map {
      case (invitee, invites) =>
        val member = invitee.left.map(usersById(_)).right.map(BasicContact(_)) // todo(ray): fetch contacts from abook or cache
        val lastInvitedAt = invites.map(_.createdAt).maxBy(_.getMillis)
        val access = invites.map(_.access).maxBy(_.priority)
        MaybeLibraryMember(member, Some(access), Some(lastInvitedAt))
    }

    actualMembers ++ invitedMembers
  }

  private def getLibraryMembersAndCount(libraryId: Id[Library], offset: Int, limit: Int, fillInWithInvites: Boolean): (Seq[LibraryMembership], Seq[LibraryMembership], Seq[(Either[Id[User], EmailAddress], Set[LibraryInvite])], CountWithLibraryIdByAccess) = {
    val collaboratorsAccess: Set[LibraryAccess] = Set(LibraryAccess.READ_WRITE)
    val followersAccess: Set[LibraryAccess] = Set(LibraryAccess.READ_ONLY)
    val relevantInviteStates = Set(LibraryInviteStates.ACTIVE)

    val memberCount = db.readOnlyMaster { implicit s =>
      libraryMembershipRepo.countWithLibraryIdByAccess(libraryId)
    }

    if (limit > 0) db.readOnlyMaster { implicit session =>
      // Get Collaborators
      val collaborators = libraryMembershipRepo.pageWithLibraryIdAndAccess(libraryId, offset, limit, collaboratorsAccess) //not cached
      val collaboratorsShown = collaborators.length

      val numCollaborators = memberCount.readWrite
      val numMembers = numCollaborators + (if (LibraryMembershipCommander.defaultLibraries.contains(libraryId)) 0 else memberCount.readOnly)

      // Get Followers
      val followersLimit = if (LibraryMembershipCommander.defaultLibraries.contains(libraryId)) 0 else limit - collaboratorsShown
      val followers = if (followersLimit == 0) Seq.empty[LibraryMembership]
      else {
        val followersOffset = if (collaboratorsShown > 0) 0
        else {
          val collaboratorsTotal = numCollaborators
          offset - collaboratorsTotal
        }
        libraryMembershipRepo.pageWithLibraryIdAndAccess(libraryId, followersOffset, followersLimit, followersAccess) //not cached
      }

      // Get Invitees with Invites
      val membersShown = collaborators.length + followers.length
      val inviteesLimit = limit - membersShown
      val inviteesWithInvites = if (inviteesLimit == 0 || !fillInWithInvites) Seq.empty[(Either[Id[User], EmailAddress], Set[LibraryInvite])]
      else {
        val inviteesOffset = if (membersShown > 0) 0
        else {
          val membersTotal = numMembers
          offset - membersTotal
        }
        libraryInviteRepo.pageInviteesByLibraryId(libraryId, inviteesOffset, inviteesLimit, relevantInviteStates) //not cached
      }
      (collaborators, followers, inviteesWithInvites, memberCount)
    }
    else (Seq.empty, Seq.empty, Seq.empty, CountWithLibraryIdByAccess.empty)
  }

  def getOrganizationLibrariesVisibleToUser(orgId: Id[Organization], userIdOpt: Option[Id[User]], offset: Offset, limit: Limit): Seq[LibraryCardInfo] = {
    db.readOnlyReplica { implicit session =>
      val visibleLibraries = getLibrariesVisibleToUserHelper(orgId, userIdOpt, offset, limit)
      val basicOwnersByOwnerId = basicUserRepo.loadAll(visibleLibraries.map(_.ownerId).toSet)
      libraryCardCommander.createLibraryCardInfos(visibleLibraries, basicOwnersByOwnerId, userIdOpt, withFollowing = false, ProcessedImageSize.Medium.idealSize).seq
    }
  }

  def getLibrariesVisibleToUserHelper(orgId: Id[Organization], userIdOpt: Option[Id[User]], offset: Offset, limit: Limit)(implicit session: RSession): Seq[Library] = {
    val viewerLibraryMemberships = userIdOpt.map(libraryMembershipRepo.getWithUserId(_).map(_.libraryId).toSet).getOrElse(Set.empty[Id[Library]])
    val includeOrgVisibleLibs = userIdOpt.exists(organizationMembershipRepo.getByOrgIdAndUserId(orgId, _).isDefined)
    libraryRepo.getVisibleOrganizationLibraries(orgId, includeOrgVisibleLibs, viewerLibraryMemberships, offset, limit)
  }

  def rpbGetUserLibraries(viewer: Option[Id[User]], userId: Id[User], fromIdOpt: Option[Id[Library]], offset: Int, limit: Int): Seq[LibraryCardInfo] = db.readOnlyReplica { implicit s =>
    import LibraryQuery._
    val libIds = libraryQueryCommander.getLibraries(viewer, LibraryQuery(target = ForUser(userId, LibraryAccess.collaborativePermissions), fromId = fromIdOpt, offset = Offset(offset), limit = Limit(limit)))
    val libsById = libraryRepo.getActiveByIds(libIds.toSet)
    val libs = libIds.flatMap(libsById.get)
    val basicOwnersById = basicUserRepo.loadAll(libs.map(_.ownerId).toSet) // god I hate that createLibraryCardInfos makes you precompute the basic users
    libraryCardCommander.createLibraryCardInfos(libs, basicOwnersById, viewer, withFollowing = false, idealSize = ProcessedImageSize.Medium.idealSize).seq
  }
  def rpbGetOrgLibraries(viewer: Option[Id[User]], orgId: Id[Organization], fromIdOpt: Option[Id[Library]], offset: Int, limit: Int): Seq[LibraryCardInfo] = db.readOnlyReplica { implicit s =>
    import LibraryQuery._
    val libIds = libraryQueryCommander.getLibraries(viewer, LibraryQuery(target = ForOrg(orgId), fromId = fromIdOpt, offset = Offset(offset), limit = Limit(limit)))
    val libsById = libraryRepo.getActiveByIds(libIds.toSet)
    val libs = libIds.flatMap(libsById.get)
    val basicOwnersById = basicUserRepo.loadAll(libs.map(_.ownerId).toSet) // god I hate that createLibraryCardInfos makes you precompute the basic users
    libraryCardCommander.createLibraryCardInfos(libs, basicOwnersById, viewer, withFollowing = false, idealSize = ProcessedImageSize.Medium.idealSize).seq
  }
}
