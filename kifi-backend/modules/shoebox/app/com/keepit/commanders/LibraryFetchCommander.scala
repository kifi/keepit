package com.keepit.commanders

import com.keepit.common.crypto.PublicIdConfiguration
import com.google.inject.{ Provider, Inject }
import com.keepit.common.akka.SafeFuture
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.performance.{ StatsdTiming, AlertingTimer }
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.store.ImageSize
import com.keepit.heimdal.HeimdalContext
import com.keepit.model._
import com.keepit.search.SearchServiceClient
import com.keepit.social.BasicUser
import play.api.http.Status._
import com.keepit.common.logging.Logging
import play.api.libs.json.Json

import scala.concurrent.duration._
import scala.collection.parallel.ParSeq
import scala.concurrent.{ Future, ExecutionContext }

class LibraryFetchCommander @Inject() (
    db: Database,
    libraryImageRepo: LibraryImageRepo,
    librarySubscriptionRepo: LibrarySubscriptionRepo,
    userRepo: UserRepo,
    systemValueRepo: SystemValueRepo,
    organizationCommanderProvider: Provider[OrganizationCommander], //bad trick, need to get rid of the provider thingy
    libraryMembershipRepo: LibraryMembershipRepo,
    basicUserRepo: BasicUserRepo,
    libraryAliasRepo: LibraryAliasRepo,
    searchClient: SearchServiceClient,
    airbrake: AirbrakeNotifier,
    libraryRepo: LibraryRepo,
    handleCommander: HandleCommander,
    implicit val publicIdConfig: PublicIdConfiguration,
    implicit val defaultContext: ExecutionContext) extends Logging {

  def getLibraryBySlugOrAlias(space: LibrarySpace, slug: LibrarySlug): Option[(Library, Boolean)] = {
    db.readOnlyMaster { implicit session =>
      libraryRepo.getBySpaceAndSlug(space, slug).map((_, false)) orElse
        libraryAliasRepo.getBySpaceAndSlug(space, slug).map(alias => (libraryRepo.get(alias.libraryId), true)).filter(_._1.state == LibraryStates.ACTIVE)
    }
  }

  def getMarketingSiteSuggestedLibraries(): Future[Seq[LibraryCardInfo]] = {
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
      ).zipWithIndex.map { case (value, idx) => value.id -> (value, idx) }.toMap

      val libIds = systemValueLibraries.keySet
      val libs = db.readOnlyReplica { implicit s =>
        libraryRepo.getLibraries(libIds).values.toSeq.filter(_.visibility == LibraryVisibility.PUBLISHED)
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
              caption = extraInfo.caption,
              modifiedAt = lib.updatedAt,
              path = info.path,
              kind = lib.kind,
              org = info.org)
        }.seq.sortBy(_._1).map(_._2)
      }
    } getOrElse Future.successful(Seq.empty)
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
    val (main, secret) = if (mainOpt.isEmpty || secretOpt.isEmpty) {
      // Right now, we don't have any users without libraries. However, I'd prefer to be safe for now
      // and fix it if a user's libraries are not set up.
      log.error(s"Unable to get main or secret libraries for user $userId: $mainOpt $secretOpt")
      internSystemGeneratedLibraries(userId)
    } else (mainOpt.get._2, secretOpt.get._2)
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

  def internSystemGeneratedLibraries(userId: Id[User], generateNew: Boolean = true): (Library, Library) = {
    db.readWrite(attempts = 3) { implicit session =>
      val libMem = libraryMembershipRepo.getWithUserId(userId, None)
      val allLibs = libraryRepo.getByUser(userId, None)
      val user = userRepo.get(userId)

      // Get all current system libraries, for main/secret, make sure only one is active.
      // This corrects any issues with previously created libraries / memberships
      val sysLibs = allLibs.filter(_._2.ownerId == userId)
        .filter(l => l._2.kind == LibraryKind.SYSTEM_MAIN || l._2.kind == LibraryKind.SYSTEM_SECRET)
        .sortBy(_._2.id.get.id)
        .groupBy(_._2.kind).flatMap {
          case (kind, libs) =>
            val (slug, name, visibility) = if (kind == LibraryKind.SYSTEM_MAIN) ("main", "Main Library", LibraryVisibility.DISCOVERABLE) else ("secret", "Secret Library", LibraryVisibility.SECRET)

            if (user.state == UserStates.ACTIVE) {
              val activeLib = libs.head._2.copy(state = LibraryStates.ACTIVE, slug = LibrarySlug(slug), name = name, visibility = visibility, memberCount = 1)
              val membership = libMem.find(m => m.libraryId == activeLib.id.get && m.access == LibraryAccess.OWNER)
              if (membership.isEmpty) airbrake.notify(s"user $userId - non-existing ownership of library kind $kind (id: ${activeLib.id.get})")
              val activeMembership = membership.getOrElse(LibraryMembership(libraryId = activeLib.id.get, userId = userId, access = LibraryAccess.OWNER)).copy(state = LibraryMembershipStates.ACTIVE)
              val active = (activeMembership, activeLib)
              if (libs.tail.length > 0) airbrake.notify(s"user $userId - duplicate active ownership of library kind $kind (ids: ${libs.tail.map(_._2.id.get)})")
              val otherLibs = libs.tail.map {
                case (a, l) =>
                  val inactMem = libMem.find(_.libraryId == l.id.get)
                    .getOrElse(LibraryMembership(libraryId = activeLib.id.get, userId = userId, access = LibraryAccess.OWNER))
                    .copy(state = LibraryMembershipStates.INACTIVE)
                  (inactMem, l.copy(state = LibraryStates.INACTIVE))
              }
              active +: otherLibs
            } else {
              // do not reactivate libraries / memberships for nonactive users
              libs
            }
        }.toList // force eval

      // save changes for active users only
      if (sysLibs.nonEmpty && user.state == UserStates.ACTIVE) {
        sysLibs.map {
          case (mem, lib) =>
            libraryRepo.save(lib)
            libraryMembershipRepo.save(mem)
        }
      }

      // If user is missing a system lib, create it
      val mainOpt = if (sysLibs.find(_._2.kind == LibraryKind.SYSTEM_MAIN).isEmpty) {
        val mainLib = libraryRepo.save(Library(name = "Main Library", ownerId = userId, visibility = LibraryVisibility.DISCOVERABLE, slug = LibrarySlug("main"), kind = LibraryKind.SYSTEM_MAIN, memberCount = 1, keepCount = 0))
        libraryMembershipRepo.save(LibraryMembership(libraryId = mainLib.id.get, userId = userId, access = LibraryAccess.OWNER))
        if (!generateNew) {
          airbrake.notify(s"$userId missing main library")
        }
        searchClient.updateLibraryIndex()
        Some(mainLib)
      } else None

      val secretOpt = if (sysLibs.find(_._2.kind == LibraryKind.SYSTEM_SECRET).isEmpty) {
        val secretLib = libraryRepo.save(Library(name = "Secret Library", ownerId = userId, visibility = LibraryVisibility.SECRET, slug = LibrarySlug("secret"), kind = LibraryKind.SYSTEM_SECRET, memberCount = 1, keepCount = 0))
        libraryMembershipRepo.save(LibraryMembership(libraryId = secretLib.id.get, userId = userId, access = LibraryAccess.OWNER))
        if (!generateNew) {
          airbrake.notify(s"$userId missing secret library")
        }
        searchClient.updateLibraryIndex()
        Some(secretLib)
      } else None

      val mainLib = sysLibs.find(_._2.kind == LibraryKind.SYSTEM_MAIN).map(_._2).orElse(mainOpt).get
      val secretLib = sysLibs.find(_._2.kind == LibraryKind.SYSTEM_SECRET).map(_._2).orElse(secretOpt).get
      (mainLib, secretLib)
    }
  }

  def createLibraryCardInfo(lib: Library, owner: User, viewerOpt: Option[User], withFollowing: Boolean, idealSize: ImageSize): LibraryCardInfo = {
    db.readOnlyMaster { implicit session =>
      createLibraryCardInfos(Seq(lib), Map(owner.id.get -> BasicUser.fromUser(owner)), viewerOpt, withFollowing, idealSize).head
    }
  }

  @AlertingTimer(2 seconds)
  @StatsdTiming("libraryFetchCommander.createLibraryCardInfos")
  def createLibraryCardInfos(libs: Seq[Library], owners: Map[Id[User], BasicUser], viewerOpt: Option[User], withFollowing: Boolean, idealSize: ImageSize)(implicit session: RSession): ParSeq[LibraryCardInfo] = {
    val libIds = libs.map(_.id.get).toSet
    val membershipsToLibsMap = viewerOpt.map { viewer =>
      libraryMembershipRepo.getWithLibraryIdsAndUserId(libIds, viewer.id.get)
    } getOrElse Map.empty
    val orgCards = organizationCommanderProvider.get.getOrganizationCards(libs.flatMap(_.organizationId), viewerOpt.flatMap(_.id))
    libs.par map { lib => // may want to optimize queries below into bulk queries
      val image = ProcessedImageSize.pickBestImage(idealSize, libraryImageRepo.getActiveForLibraryId(lib.id.get), strictAspectRatio = false)
      val (numFollowers, followersSample, numCollaborators, collabsSample) = {
        val countMap = libraryMembershipRepo.countWithLibraryIdByAccess(lib.id.get)
        val numFollowers = countMap.readOnly
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
      val orgCardOpt = lib.organizationId.map(orgCards.apply)
      val path = LibraryPathHelper.formatLibraryPath(owner, orgCardOpt.map(_.handle), lib.slug)

      val membershipOpt = membershipsToLibsMap.get(lib.id.get).flatten
      val isFollowing = if (withFollowing && membershipOpt.isDefined) {
        Some(membershipOpt.isDefined)
      } else {
        None
      }
      createLibraryCardInfo(lib, image, owner, numFollowers, followersSample, numCollaborators, collabsSample, isFollowing, membershipOpt, path, orgCardOpt)
    }
  }

  def createLiteLibraryCardInfos(libs: Seq[Library], viewerId: Id[User])(implicit session: RSession): ParSeq[(LibraryCardInfo, MiniLibraryMembership, Seq[LibrarySubscriptionKey])] = {
    val memberships = libraryMembershipRepo.getMinisByLibraryIdsAndAccess(
      libs.map(_.id.get).toSet, Set(LibraryAccess.OWNER, LibraryAccess.READ_WRITE))
    val userIds = memberships.values.map(_.map(_.userId)).flatten.toSet
    val allBasicUsers = basicUserRepo.loadAll(userIds)

    val orgCardById = organizationCommanderProvider.get.getOrganizationCards(libs.flatMap(_.organizationId), Some(viewerId))

    libs.par map { lib =>
      val libMems = memberships(lib.id.get)
      val viewerMem = libMems.find(_.userId == viewerId).get
      val subscriptions = librarySubscriptionRepo.getByLibraryId(lib.id.get).map { sub => LibrarySubscription.toSubKey(sub) }
      val (numFollowers, numCollaborators, collabsSample) = if (libMems.length > 1) {
        val numFollowers = libraryMembershipRepo.countWithLibraryIdAndAccess(lib.id.get, LibraryAccess.READ_ONLY)
        val numCollaborators = libMems.length - 1
        val collabsSample = libMems.filter(_.access != LibraryAccess.OWNER)
          .sortBy(_.userId != viewerId)
          .take(4).map(m => allBasicUsers(m.userId))
        (numFollowers, numCollaborators, collabsSample)
      } else {
        (0, 0, Seq.empty)
      }
      val orgCardOpt = lib.organizationId.map(orgCardById.apply)
      val owner = basicUserRepo.load(lib.ownerId)
      val path = LibraryPathHelper.formatLibraryPath(owner = owner, orgCardOpt.map(_.handle), slug = lib.slug)

      if (!userIds.contains(lib.ownerId)) {
        airbrake.notify(s"owner of lib $lib is not part of the membership list: $userIds - data integrity issue? does the owner has a library membership object?")
      }

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
        membership = None, // not needed
        path = path,
        modifiedAt = lib.updatedAt,
        org = orgCardOpt)
      (info, viewerMem, subscriptions)
    }
  }

  @StatsdTiming("libraryFetchCommander.createLibraryCardInfo")
  private def createLibraryCardInfo(lib: Library, image: Option[LibraryImage], owner: BasicUser, numFollowers: Int,
    followers: Seq[BasicUser], numCollaborators: Int, collaborators: Seq[BasicUser], isFollowing: Option[Boolean], membershipOpt: Option[LibraryMembership], path: String, orgCard: Option[OrganizationCard]): LibraryCardInfo = {
    LibraryCardInfo(
      id = Library.publicId(lib.id.get),
      name = lib.name,
      description = lib.description,
      color = lib.color,
      image = image.map(LibraryImageInfoBuilder.createInfo),
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
      membership = membershipOpt.map(LibraryMembershipInfo.fromMembership(_)),
      modifiedAt = lib.updatedAt,
      kind = lib.kind,
      path = path,
      org = orgCard
    )
  }

}
