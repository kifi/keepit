package com.keepit.commanders

import com.google.inject.{ Inject, ImplementedBy }
import com.keepit.common.akka.SafeFuture
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.performance.{ StatsdTiming, AlertingTimer }
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.store.ImageSize
import com.keepit.model._
import com.keepit.social.BasicUser
import play.api.libs.json.Json
import scala.concurrent.duration._

import scala.collection.parallel.ParSeq
import scala.concurrent.{ Future, ExecutionContext }

@ImplementedBy(classOf[LibraryCardCommanderImpl])
trait LibraryCardCommander {
  def createLibraryCardInfo(lib: Library, owner: BasicUser, viewerOpt: Option[Id[User]], withFollowing: Boolean, idealSize: ImageSize): LibraryCardInfo
  def createLibraryCardInfos(libs: Seq[Library], owners: Map[Id[User], BasicUser], viewerOpt: Option[Id[User]], withFollowing: Boolean, idealSize: ImageSize)(implicit session: RSession): ParSeq[LibraryCardInfo]
  def createLiteLibraryCardInfos(libs: Seq[Library], viewerId: Id[User])(implicit session: RSession): ParSeq[(LibraryCardInfo, Option[MiniLibraryMembership])]
  def getMarketingSiteSuggestedLibraries: Future[Seq[LibraryCardInfo]]
}

class LibraryCardCommanderImpl @Inject() (
    db: Database,
    libraryRepo: LibraryRepo,
    libraryMembershipRepo: LibraryMembershipRepo,
    libraryImageRepo: LibraryImageRepo,
    basicUserRepo: BasicUserRepo,
    systemValueRepo: SystemValueRepo,
    organizationInfoCommander: OrganizationInfoCommander,
    libraryInviteCommander: LibraryInviteCommander,
    libraryMembershipCommander: LibraryMembershipCommander,
    permissionCommander: PermissionCommander,
    airbrake: AirbrakeNotifier,
    implicit val defaultContext: ExecutionContext,
    implicit val publicIdConfig: PublicIdConfiguration) extends LibraryCardCommander with Logging {

  def createLibraryCardInfo(lib: Library, owner: BasicUser, viewerOpt: Option[Id[User]], withFollowing: Boolean, idealSize: ImageSize): LibraryCardInfo = {
    db.readOnlyMaster { implicit session =>
      createLibraryCardInfos(Seq(lib), Map(lib.ownerId -> owner), viewerOpt, withFollowing, idealSize).head
    }
  }

  @AlertingTimer(2 seconds)
  @StatsdTiming("libraryInfoCommander.createLibraryCardInfos")
  def createLibraryCardInfos(libs: Seq[Library], owners: Map[Id[User], BasicUser], viewerIdOpt: Option[Id[User]], withFollowing: Boolean, idealSize: ImageSize)(implicit session: RSession): ParSeq[LibraryCardInfo] = {
    val libIds = libs.map(_.id.get).toSet
    val membershipsToLibsMap = viewerIdOpt.fold(Map.empty[Id[Library], LibraryMembership]) { viewerId =>
      libraryMembershipRepo.getWithLibraryIdsAndUserId(libIds, viewerId)
    }
    val orgViews = organizationInfoCommander.getBasicOrganizationViewsHelper(libs.flatMap(_.organizationId).toSet, viewerIdOpt = viewerIdOpt, authTokenOpt = None)
    val libPermissionsById = permissionCommander.getLibrariesPermissions(libIds, viewerIdOpt)
    libs.par map { lib => // may want to optimize queries below into bulk queries
      val image = ProcessedImageSize.pickBestImage(idealSize, libraryImageRepo.getActiveForLibraryId(lib.id.get), strictAspectRatio = false)
      val (numFollowers, followersSample, numCollaborators, collabsSample) = {
        val countMap = libraryMembershipRepo.countWithLibraryIdByAccess(lib.id.get)
        val numFollowers = if (LibraryMembershipCommander.defaultLibraries.contains(lib.id.get)) 0 else countMap.readOnly
        val numCollaborators = countMap.readWrite

        val collaborators = libraryMembershipRepo.someWithLibraryIdAndAccess(lib.id.get, 3, LibraryAccess.READ_WRITE)
        val followers = libraryMembershipRepo.someWithLibraryIdAndAccess(lib.id.get, 3, LibraryAccess.READ_ONLY)
        val collabIds = collaborators.map(_.userId).toSet
        val followerIds = followers.map(_.userId).toSet
        val userSample = basicUserRepo.loadAll(followerIds ++ collabIds) //we don't care about the order now anyway
        val followersSample = followerIds.map(id => userSample(id)).toSeq
        val collabsSample = collabIds.map(id => userSample(id)).toSeq
        (numFollowers, followersSample, numCollaborators, collabsSample)
      }

      val owner = owners(lib.ownerId)
      val orgViewOpt = lib.organizationId.map(orgViews.apply)
      val path = LibraryPathHelper.formatLibraryPath(owner, orgViewOpt.map(_.basicOrganization.handle), lib.slug)

      val membershipOpt = membershipsToLibsMap.get(lib.id.get)
      val membershipInfoOpt = membershipOpt.map(libraryMembershipCommander.createMembershipInfo)
      val inviteInfoOpt = libraryInviteCommander.createInviteInfo(lib.id.get, viewerIdOpt, None)

      val isFollowing = if (withFollowing && membershipOpt.isDefined) {
        Some(membershipOpt.isDefined)
      } else {
        None
      }

      createLibraryCardInfo(lib, image, owner, numFollowers, followersSample, numCollaborators, collabsSample, isFollowing, membershipInfoOpt, inviteInfoOpt, libPermissionsById(lib.id.get), path, orgViewOpt)
    }
  }

  def createLiteLibraryCardInfos(libs: Seq[Library], viewerId: Id[User])(implicit session: RSession): ParSeq[(LibraryCardInfo, Option[MiniLibraryMembership])] = {
    val memberships = libraryMembershipRepo.getMinisByLibraryIdsAndAccess(
      libs.map(_.id.get).toSet, Set(LibraryAccess.OWNER, LibraryAccess.READ_WRITE))
    val userIds = memberships.values.flatMap(_.map(_.userId)).toSet
    val allBasicUsers = basicUserRepo.loadAll(userIds)

    val basicOrgViewById = organizationInfoCommander.getBasicOrganizationViewsHelper(libs.flatMap(_.organizationId).toSet, Some(viewerId), authTokenOpt = None)
    val permissionsById = permissionCommander.getLibrariesPermissions(libs.map(_.id.get).toSet, Some(viewerId))

    libs.par map { lib =>
      val libMems = memberships(lib.id.get)
      val viewerMemOpt = libMems.find(_.userId == viewerId)
      val (numFollowers, numCollaborators, collabsSample) = if (libMems.length > 1) {
        val numFollowers = if (LibraryMembershipCommander.defaultLibraries.contains(lib.id.get)) 0 else libraryMembershipRepo.countWithLibraryIdAndAccess(lib.id.get, LibraryAccess.READ_ONLY)
        val numCollaborators = libMems.length - 1
        val collabsSample = libMems.filter(_.access != LibraryAccess.OWNER)
          .sortBy(_.userId != viewerId)
          .take(4).map(m => allBasicUsers(m.userId))
        (numFollowers, numCollaborators, collabsSample)
      } else {
        (0, 0, Seq.empty)
      }
      val basicOrgViewOpt = lib.organizationId.map(basicOrgViewById.apply)
      val owner = basicUserRepo.load(lib.ownerId)
      val path = LibraryPathHelper.formatLibraryPath(owner = owner, basicOrgViewOpt.map(_.basicOrganization.handle), slug = lib.slug)

      if (!userIds.contains(lib.ownerId)) {
        airbrake.notify(s"owner of lib $lib is not part of the membership list: $userIds - data integrity issue? does the owner has a library membership object?")
      }

      val viewerMemInfoOpt = viewerMemOpt.map(miniMem => LibraryMembershipInfo(miniMem.access, miniMem.listed, miniMem.subscribed, permissionsById(lib.id.get)))

      val info = LibraryCardInfo(
        id = Library.publicId(lib.id.get),
        name = lib.name,
        description = None, // not needed
        color = lib.color,
        image = None, // not needed
        slug = lib.slug,
        kind = lib.kind,
        visibility = lib.visibility,
        owner = allBasicUsers(lib.ownerId),
        numKeeps = lib.keepCount,
        numFollowers = numFollowers,
        followers = Seq.empty, // not needed
        numCollaborators = numCollaborators,
        collaborators = collabsSample,
        lastKept = lib.lastKept.getOrElse(lib.createdAt),
        following = None, // not needed
        membership = viewerMemInfoOpt,
        invite = None, // not needed
        permissions = permissionsById(lib.id.get),
        path = path,
        modifiedAt = lib.updatedAt,
        org = basicOrgViewOpt,
        orgMemberAccess = lib.organizationMemberAccess,
        whoCanComment = lib.whoCanComment)
      (info, viewerMemOpt)
    }
  }

  @StatsdTiming("libraryInfoCommander.createLibraryCardInfo")
  private def createLibraryCardInfo(lib: Library, image: Option[LibraryImage], owner: BasicUser, numFollowers: Int,
    followers: Seq[BasicUser], numCollaborators: Int, collaborators: Seq[BasicUser], isFollowing: Option[Boolean], membershipInfoOpt: Option[LibraryMembershipInfo], inviteInfoOpt: Option[LibraryInviteInfo], permissions: Set[LibraryPermission], path: String, orgView: Option[BasicOrganizationView]): LibraryCardInfo = {
    LibraryCardInfo(
      id = Library.publicId(lib.id.get),
      name = Library.getDisplayName(lib.name, lib.kind),
      description = lib.description,
      color = lib.color,
      image = image.map(_.asInfo),
      slug = lib.slug,
      visibility = lib.visibility,
      owner = owner,
      numKeeps = lib.keepCount,
      numFollowers = numFollowers,
      followers = LibraryCardInfo.chooseFollowers(followers),
      numCollaborators = numCollaborators,
      collaborators = LibraryCardInfo.chooseCollaborators(collaborators),
      lastKept = lib.lastKept.getOrElse(lib.createdAt),
      following = isFollowing,
      membership = membershipInfoOpt,
      invite = inviteInfoOpt,
      permissions = permissions,
      modifiedAt = lib.updatedAt,
      kind = lib.kind,
      path = path,
      org = orgView,
      orgMemberAccess = if (lib.organizationId.isDefined) Some(lib.organizationMemberAccess.getOrElse(LibraryAccess.READ_WRITE)) else None,
      whoCanComment = lib.whoCanComment
    )
  }

  def getMarketingSiteSuggestedLibraries: Future[Seq[LibraryCardInfo]] = {
    val valueOpt = db.readOnlyReplica { implicit s =>
      systemValueRepo.getValue(MarketingSuggestedLibrarySystemValue.systemValueName)
    }

    valueOpt map { value =>
      val systemValueLibraries = Json.fromJson[Seq[MarketingSuggestedLibrarySystemValue]](Json.parse(value)).fold(
        err => {
          airbrake.notify(s"Invalid JSON format for Seq[MarketingSuggestedLibrarySystemValue]: $err")
          Seq.empty[MarketingSuggestedLibrarySystemValue]
        },
        identity
      ).zipWithIndex.map { case (valuex, idx) => valuex.id -> (valuex, idx) }.toMap

      val libIds = systemValueLibraries.keySet
      val libs = db.readOnlyReplica { implicit s =>
        libraryRepo.getActiveByIds(libIds).values.toSeq.filter(_.visibility == LibraryVisibility.PUBLISHED)
      }
      val infos: ParSeq[LibraryCardInfo] = db.readOnlyMaster { implicit s =>
        val owners = basicUserRepo.loadAll(libs.map(_.ownerId).toSet)
        createLibraryCardInfos(libs, owners, None, false, ProcessedImageSize.Medium.idealSize)
      }

      SafeFuture {
        infos.zip(libs).map {
          case (info, lib) =>
            val (extraInfo, idx) = systemValueLibraries(lib.id.get)
            idx -> LibraryCardInfo(
              id = info.id,
              name = info.name,
              description = None, // not currently used
              color = info.color,
              image = info.image,
              slug = info.slug,
              visibility = lib.visibility,
              owner = info.owner,
              numKeeps = info.numKeeps,
              numFollowers = info.numFollowers,
              followers = Seq.empty,
              numCollaborators = info.numCollaborators,
              collaborators = Seq.empty,
              lastKept = info.lastKept,
              following = None,
              membership = None,
              invite = None,
              permissions = Set.empty,
              caption = extraInfo.caption,
              modifiedAt = lib.updatedAt,
              path = info.path,
              kind = lib.kind,
              org = info.org,
              orgMemberAccess = lib.organizationMemberAccess,
              whoCanComment = lib.whoCanComment
            )
        }.seq.sortBy(_._1).map(_._2)
      }
    } getOrElse Future.successful(Seq.empty)
  }
}
