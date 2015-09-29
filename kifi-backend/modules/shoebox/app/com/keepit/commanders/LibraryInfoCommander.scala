package com.keepit.commanders

import com.keepit.common.core._
import com.google.inject.{ ImplementedBy, Inject }
import com.keepit.common.akka.SafeFuture
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail.{ BasicContact, EmailAddress }
import com.keepit.common.performance.{ StatsdTiming, AlertingTimer }
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.store.ImageSize
import com.keepit.heimdal.HeimdalContext
import com.keepit.model._
import com.keepit.search.SearchServiceClient
import com.keepit.social.{ BasicNonUser, BasicUser }
import org.joda.time.DateTime
import play.api.http.Status._
import play.api.libs.json.Json
import com.keepit.common.time._

import scala.collection.parallel.ParSeq
import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration._

@ImplementedBy(classOf[LibraryInfoCommanderImpl])
trait LibraryInfoCommander {
  def sortUsersByImage(users: Seq[BasicUser]): Seq[BasicUser]
  def getKeeps(libraryId: Id[Library], offset: Int, limit: Int, useMultilibLogic: Boolean = false): Future[Seq[Keep]]
  def getKeepsCount(libraryId: Id[Library]): Future[Int]
  def getLibraryById(userIdOpt: Option[Id[User]], showPublishedLibraries: Boolean, id: Id[Library], imageSize: ImageSize, viewerId: Option[Id[User]])(implicit context: HeimdalContext): Future[FullLibraryInfo]
  def getLibraryMembersAndInvitees(libraryId: Id[Library], offset: Int, limit: Int, fillInWithInvites: Boolean): Seq[MaybeLibraryMember]
  def getLibrariesWithWriteAccess(userId: Id[User]): Set[Id[Library]]
  def getBasicLibraryDetails(libraryIds: Set[Id[Library]], idealImageSize: ImageSize, viewerId: Option[Id[User]]): Map[Id[Library], BasicLibraryDetails]
  def getLibraryWithOwnerAndCounts(libraryId: Id[Library], viewerUserId: Id[User]): Either[LibraryFail, (Library, BasicUser, Int, Option[Boolean], Boolean)]
  def getViewerMembershipInfo(userIdOpt: Option[Id[User]], libraryId: Id[Library]): Option[LibraryMembershipInfo]
  def createFullLibraryInfos(viewerUserIdOpt: Option[Id[User]], showPublishedLibraries: Boolean, maxMembersShown: Int, maxKeepsShown: Int, idealKeepImageSize: ImageSize, libraries: Seq[Library], idealLibraryImageSize: ImageSize, withKeepTime: Boolean, useMultilibLogic: Boolean = false, authTokens: Map[Id[Library], String] = Map.empty): Future[Seq[(Id[Library], FullLibraryInfo)]]
  def createFullLibraryInfo(viewerUserIdOpt: Option[Id[User]], showPublishedLibraries: Boolean, library: Library, libImageSize: ImageSize, authToken: Option[String], showKeepCreateTime: Boolean = true, useMultilibLogic: Boolean = false): Future[FullLibraryInfo]
  def getLibrariesByUser(userId: Id[User]): (Seq[(LibraryMembership, Library)], Seq[(LibraryInvite, Library)])
  def getLibrariesUserCanKeepTo(userId: Id[User], includeOrgLibraries: Boolean): Seq[(Library, Option[LibraryMembership], Set[Id[User]])]
  def sortAndSelectLibrariesWithTopGrowthSince(libraryIds: Set[Id[Library]], since: DateTime, totalMemberCount: Id[Library] => Int): Seq[(Id[Library], Seq[LibraryMembership])]
  def sortAndSelectLibrariesWithTopGrowthSince(libraryMemberCountsSince: Map[Id[Library], Int], since: DateTime, totalMemberCount: Id[Library] => Int): Seq[(Id[Library], Seq[LibraryMembership])]
  def getMainAndSecretLibrariesForUser(userId: Id[User])(implicit session: RWSession): (Library, Library)
  def getLibraryWithHandleAndSlug(handle: Handle, slug: LibrarySlug, viewerId: Option[Id[User]])(implicit context: HeimdalContext): Either[LibraryFail, Library]
  def getLibraryBySlugOrAlias(space: LibrarySpace, slug: LibrarySlug): Option[(Library, Boolean)]
  def getMarketingSiteSuggestedLibraries: Future[Seq[LibraryCardInfo]]
  def createLibraryCardInfo(lib: Library, owner: BasicUser, viewerOpt: Option[Id[User]], withFollowing: Boolean, idealSize: ImageSize): LibraryCardInfo
  def createLibraryCardInfos(libs: Seq[Library], owners: Map[Id[User], BasicUser], viewerOpt: Option[Id[User]], withFollowing: Boolean, idealSize: ImageSize)(implicit session: RSession): ParSeq[LibraryCardInfo]
  def createLiteLibraryCardInfos(libs: Seq[Library], viewerId: Id[User])(implicit session: RSession): ParSeq[(LibraryCardInfo, MiniLibraryMembership, Seq[LibrarySubscriptionKey])]
  def createMembershipInfo(mem: LibraryMembership)(implicit session: RSession): LibraryMembershipInfo
}

class LibraryInfoCommanderImpl @Inject() (
    db: Database,
    libraryImageRepo: LibraryImageRepo,
    librarySubscriptionRepo: LibrarySubscriptionRepo,
    systemValueRepo: SystemValueRepo,
    libraryAliasRepo: LibraryAliasRepo,
    handleCommander: HandleCommander,
    permissionCommander: PermissionCommander,
    searchClient: SearchServiceClient,
    userRepo: UserRepo,
    organizationMembershipRepo: OrganizationMembershipRepo,
    twitterSyncRepo: TwitterSyncStateRepo,
    keepRepo: KeepRepo,
    libraryMembershipRepo: LibraryMembershipRepo,
    libraryImageCommander: LibraryImageCommander,
    ktlRepo: KeepToLibraryRepo,
    libPathCommander: PathCommander,
    airbrake: AirbrakeNotifier,
    keepCommander: KeepCommander,
    libraryAnalytics: LibraryAnalytics,
    keepDecorator: KeepDecorator,
    organizationCommander: OrganizationCommander,
    experimentCommander: LocalUserExperimentCommander,
    libraryInviteRepo: LibraryInviteRepo,
    basicUserRepo: BasicUserRepo,
    orgRepo: OrganizationRepo,
    libraryRepo: LibraryRepo,
    implicit val defaultContext: ExecutionContext,
    implicit val publicIdConfig: PublicIdConfiguration) extends LibraryInfoCommander with Logging {

  def getKeeps(libraryId: Id[Library], offset: Int, limit: Int, useMultilibLogic: Boolean = false): Future[Seq[Keep]] = {
    if (limit > 0) db.readOnlyReplicaAsync { implicit s =>
      val oldWay = keepRepo.getByLibrary(libraryId, offset, limit)
      val newWay = ktlRepo.getByLibraryIdSorted(libraryId, Offset(offset), Limit(limit)) |> keepCommander.idsToKeeps
      if (newWay.map(_.id.get) != oldWay.map(_.id.get)) {
        log.info(s"[KTL-MATCH] getKeeps($libraryId, $offset, $limit): ${newWay.map(_.id.get)} != ${oldWay.map(_.id.get)}")
      }
      if (useMultilibLogic) newWay else oldWay
    }
    else Future.successful(Seq.empty)
  }

  def getKeepsCount(libraryId: Id[Library]): Future[Int] = {
    db.readOnlyMasterAsync { implicit s => libraryRepo.get(libraryId).keepCount }
  }

  def getLibraryById(userIdOpt: Option[Id[User]], showPublishedLibraries: Boolean, id: Id[Library], imageSize: ImageSize, viewerId: Option[Id[User]])(implicit context: HeimdalContext): Future[FullLibraryInfo] = {
    val lib = db.readOnlyMaster { implicit s => libraryRepo.get(id) }
    libraryAnalytics.viewedLibrary(viewerId, lib, context)
    createFullLibraryInfo(userIdOpt, showPublishedLibraries, lib, imageSize, None)
  }

  def getLibraryPath(library: Library): String = {
    libPathCommander.getPathForLibrary(library)
  }

  def createMembershipInfo(mem: LibraryMembership)(implicit session: RSession): LibraryMembershipInfo = {
    LibraryMembershipInfo(mem.access, mem.listed, mem.subscribedToUpdates, permissionCommander.getLibraryPermissions(mem.libraryId, Some(mem.userId)))
  }

  private def createInviteInfo(libraryId: Id[Library], userId: Option[Id[User]], authToken: Option[String])(implicit session: RSession): Option[LibraryInviteInfo] = {
    val invites: Seq[LibraryInvite] = userId.toSeq.flatMap(libraryInviteRepo.getWithLibraryIdAndUserId(libraryId, _)) ++ authToken.toSeq.flatMap(libraryInviteRepo.getByLibraryIdAndAuthToken(libraryId, _))
    for {
      access <- invites.map(_.access).maxOpt
      (lastInvitedAt, inviter) <- invites.maxByOpt(_.createdAt).map { invite =>
        val basicInviter = basicUserRepo.load(invite.inviterId)
        (invite.createdAt, basicInviter)
      }
    } yield LibraryInviteInfo(access, lastInvitedAt, inviter)
  }

  def getBasicLibraryDetails(libraryIds: Set[Id[Library]], idealImageSize: ImageSize, viewerId: Option[Id[User]]): Map[Id[Library], BasicLibraryDetails] = {
    db.readOnlyReplica { implicit session =>

      val membershipsByLibraryId = viewerId.map { id =>
        libraryMembershipRepo.getWithLibraryIdsAndUserId(libraryIds, id)
      } getOrElse Map.empty

      val libs = libraryRepo.getLibraries(libraryIds)

      libraryIds.map { libId =>
        val lib = libs(libId)
        val counts = libraryMembershipRepo.countWithLibraryIdByAccess(libId)
        val numFollowers = if (LibraryMembershipCommander.defaultLibraries.contains(libId)) 0 else counts.readOnly
        val numCollaborators = counts.readWrite
        val imageOpt = libraryImageCommander.getBestImageForLibrary(libId, idealImageSize).map(libraryImageCommander.getUrl)
        val membershipOpt = membershipsByLibraryId.get(libId).flatten
        val path = libPathCommander.pathForLibrary(lib)
        libId -> BasicLibraryDetails(lib.name, lib.slug, lib.color, imageOpt, lib.description, numFollowers, numCollaborators, lib.keepCount, membershipOpt.map(createMembershipInfo), lib.ownerId, path)
      }.toMap
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
      case LibraryKind.USER_CREATED | LibraryKind.SYSTEM_PERSONA | LibraryKind.SYSTEM_READ_IT_LATER | LibraryKind.SYSTEM_GUIDE =>
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
      case _ =>
        LibMembersAndCounts(CountWithLibraryIdByAccess.empty, Seq.empty, Seq.empty, Seq.empty)
    }
    library.id.get -> info
  }.toMap

  def createFullLibraryInfos(viewerUserIdOpt: Option[Id[User]], showPublishedLibraries: Boolean, maxMembersShown: Int, maxKeepsShown: Int,
    idealKeepImageSize: ImageSize, libraries: Seq[Library], idealLibraryImageSize: ImageSize, withKeepTime: Boolean, useMultilibLogic: Boolean = false, authTokens: Map[Id[Library], String] = Map.empty): Future[Seq[(Id[Library], FullLibraryInfo)]] = {
    libraries.groupBy(l => l.id.get).foreach { case (lib, set) => if (set.size > 1) throw new Exception(s"There are ${set.size} identical libraries of $lib") }
    val futureKeepInfosByLibraryId = libraries.map { library =>
      library.id.get -> {
        if (maxKeepsShown > 0) {
          val keeps = db.readOnlyMaster { implicit session =>
            library.kind match {
              case LibraryKind.SYSTEM_MAIN =>
                assume(library.ownerId == viewerUserIdOpt.get, s"viewer ${viewerUserIdOpt.get} can't view a system library they do not own: $library")
                keepRepo.getByLibrary(library.id.get, 0, maxKeepsShown)
              case LibraryKind.SYSTEM_SECRET =>
                assume(library.ownerId == viewerUserIdOpt.get, s"viewer ${viewerUserIdOpt.get} can't view a system library they do not own: $library")
                keepRepo.getByLibrary(library.id.get, 0, maxKeepsShown) //not cached
              case _ =>
                val oldWay = keepRepo.getByLibrary(library.id.get, 0, maxKeepsShown) //not cached
                val newWay = ktlRepo.getByLibraryIdSorted(library.id.get, Offset(0), Limit(maxKeepsShown)) |> keepCommander.idsToKeeps
                if (newWay.map(_.id.get) != oldWay.map(_.id.get)) log.info(s"[KTL-MATCH] createFullLibraryInfos(${library.id.get}): ${newWay.map(_.id.get)} != ${oldWay.map(_.id.get)}")
                if (useMultilibLogic) newWay else oldWay
            }
          }
          keepDecorator.decorateKeepsIntoKeepInfos(viewerUserIdOpt, showPublishedLibraries, keeps, idealKeepImageSize, withKeepTime)
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
        val orgMap = orgRepo.getByIds(allOrgsShown)
        organizationCommander.getBasicOrganizationViews(orgMap.keys.toSet, viewerUserIdOpt, authTokenOpt = None)
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
            libraryId -> createMembershipInfo(membership)
          }
        }
      } toMap
    }

    val inviteByLibraryId = viewerUserIdOpt.map { viewerUserId =>
      db.readOnlyMaster { implicit session =>
        libraries.flatMap { library =>
          val libraryId = library.id.get
          createInviteInfo(libraryId, viewerUserIdOpt, authTokens.get(libraryId)).map(libraryId -> _)
        }
      } toMap
    }

    val permissionsByLibraryId = db.readOnlyMaster { implicit s =>
      libraries.map { lib => lib.id.get -> permissionCommander.getLibraryPermissions(lib.id.get, viewerUserIdOpt) }
    }.toMap

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
          image = libImageOpt.map(LibraryImageInfo.fromImage),
          followers = followers,
          collaborators = collaborators,
          keeps = keepInfos,
          numKeeps = keepCount,
          numCollaborators = collaboratorCount,
          numFollowers = followerCount,
          lastKept = lib.lastKept,
          attr = attr,
          whoCanInvite = whoCanInvite,
          modifiedAt = lib.updatedAt,
          path = LibraryPathHelper.formatLibraryPath(owner = owner, orgHandleOpt = orgViewOpt.map(_.basicOrganization.handle), slug = lib.slug),
          org = orgViewOpt,
          orgMemberAccess = if (lib.organizationId.isDefined) Some(lib.organizationMemberAccess.getOrElse(LibraryAccess.READ_WRITE)) else None,
          membership = membershipByLibraryId.flatMap(_.get(libId)),
          invite = inviteByLibraryId.flatMap(_.get(libId)),
          permissions = permissionsByLibraryId(libId)
        )
      }
    }
    Future.sequence(futureFullLibraryInfos)
  }

  def getViewerMembershipInfo(userIdOpt: Option[Id[User]], libraryId: Id[Library]): Option[LibraryMembershipInfo] = {
    userIdOpt.flatMap { userId =>
      db.readOnlyReplica { implicit s =>
        val membershipOpt = libraryMembershipRepo.getWithLibraryIdAndUserId(libraryId, userId)
        membershipOpt.map(createMembershipInfo)
      }
    }
  }

  def getLibraryPermissionsFromOrgPermissions(orgIdOpt: Option[Id[Organization]], userIdOpt: Option[Id[User]])(implicit session: RSession): Set[LibraryPermission] = {
    (orgIdOpt, userIdOpt) match {
      case (Some(orgId), Some(userId)) => organizationMembershipRepo.getByOrgIdAndUserId(orgId, userId).map { orgMem =>
        val libraryPermissions = orgMem.permissions.flatMap(OrganizationPermission.toLibraryPermissionsOpt).flatten
        libraryPermissions
      }.getOrElse(Set.empty)
      case _ => Set.empty
    }
  }

  private def getSourceAttribution(libId: Id[Library]): Option[LibrarySourceAttribution] = {
    db.readOnlyReplica { implicit s =>
      twitterSyncRepo.getFirstHandleByLibraryId(libId).map {
        TwitterLibrarySourceAttribution(_)
      }
    }
  }

  def sortUsersByImage(users: Seq[BasicUser]): Seq[BasicUser] =
    users.sortBy(_.pictureName == BasicNonUser.DefaultPictureName)

  def createFullLibraryInfo(viewerUserIdOpt: Option[Id[User]], showPublishedLibraries: Boolean, library: Library, libImageSize: ImageSize, authToken: Option[String], showKeepCreateTime: Boolean = true, useMultilibLogic: Boolean = false): Future[FullLibraryInfo] = {
    val maxMembersShown = 10
    createFullLibraryInfos(viewerUserIdOpt, showPublishedLibraries, maxMembersShown = maxMembersShown * 2, maxKeepsShown = 10, ProcessedImageSize.Large.idealSize, Seq(library), libImageSize, showKeepCreateTime, useMultilibLogic, authToken.map(library.id.get -> _).toMap).imap {
      case Seq((_, info)) =>
        val followers = info.followers
        val sortedFollowers = sortUsersByImage(followers)
        info.copy(followers = sortedFollowers.take(maxMembersShown))
    }
  }

  def getLibrariesWithWriteAccess(userId: Id[User]): Set[Id[Library]] = {
    db.readOnlyMaster { implicit session => libraryMembershipRepo.getLibrariesWithWriteAccess(userId) }
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
          case ((invite, lib)) if !memberOrOpenOrgLibIds.contains(lib.id.get) && lib.organizationId.isDefined =>
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
              invite = None,
              permissions = Set.empty,
              caption = extraInfo.caption,
              modifiedAt = lib.updatedAt,
              path = info.path,
              kind = lib.kind,
              org = info.org,
              orgMemberAccess = lib.organizationMemberAccess
            )
        }.seq.sortBy(_._1).map(_._2)
      }
    } getOrElse Future.successful(Seq.empty)
  }

  def createLibraryCardInfo(lib: Library, owner: BasicUser, viewerOpt: Option[Id[User]], withFollowing: Boolean, idealSize: ImageSize): LibraryCardInfo = {
    db.readOnlyMaster { implicit session =>
      createLibraryCardInfos(Seq(lib), Map(lib.ownerId -> owner), viewerOpt, withFollowing, idealSize).head
    }
  }

  @AlertingTimer(2 seconds)
  @StatsdTiming("libraryInfoCommander.createLibraryCardInfos")
  def createLibraryCardInfos(libs: Seq[Library], owners: Map[Id[User], BasicUser], viewerIdOpt: Option[Id[User]], withFollowing: Boolean, idealSize: ImageSize)(implicit session: RSession): ParSeq[LibraryCardInfo] = {
    val libIds = libs.map(_.id.get).toSet
    val membershipsToLibsMap = viewerIdOpt.map { viewerId =>
      libraryMembershipRepo.getWithLibraryIdsAndUserId(libIds, viewerId)
    } getOrElse Map.empty
    val orgViews = organizationCommander.getBasicOrganizationViews(libs.flatMap(_.organizationId).toSet, viewerIdOpt = viewerIdOpt, authTokenOpt = None)
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

      val membershipOpt = membershipsToLibsMap.get(lib.id.get).flatten
      val membershipInfoOpt = membershipOpt.map(createMembershipInfo)
      val inviteInfoOpt = createInviteInfo(lib.id.get, viewerIdOpt, None)

      val isFollowing = if (withFollowing && membershipOpt.isDefined) {
        Some(membershipOpt.isDefined)
      } else {
        None
      }

      val permissions = permissionCommander.getLibraryPermissions(lib.id.get, viewerIdOpt)

      createLibraryCardInfo(lib, image, owner, numFollowers, followersSample, numCollaborators, collabsSample, isFollowing, membershipInfoOpt, inviteInfoOpt, permissions, path, orgViewOpt)
    }
  }

  def createLiteLibraryCardInfos(libs: Seq[Library], viewerId: Id[User])(implicit session: RSession): ParSeq[(LibraryCardInfo, MiniLibraryMembership, Seq[LibrarySubscriptionKey])] = {
    val memberships = libraryMembershipRepo.getMinisByLibraryIdsAndAccess(
      libs.map(_.id.get).toSet, Set(LibraryAccess.OWNER, LibraryAccess.READ_WRITE))
    val userIds = memberships.values.map(_.map(_.userId)).flatten.toSet
    val allBasicUsers = basicUserRepo.loadAll(userIds)

    val basicOrgViewById = organizationCommander.getBasicOrganizationViews(libs.flatMap(_.organizationId).toSet, Some(viewerId), authTokenOpt = None)

    libs.par map { lib =>
      val libMems = memberships(lib.id.get)
      val viewerMem = libMems.find(_.userId == viewerId).get
      val subscriptions = librarySubscriptionRepo.getByLibraryId(lib.id.get).map { sub => LibrarySubscription.toSubKey(sub) }
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

      val permissions = permissionCommander.getLibraryPermissions(lib.id.get, Some(viewerId))

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
        invite = None, // not needed
        permissions = permissions,
        path = path,
        modifiedAt = lib.updatedAt,
        org = basicOrgViewOpt,
        orgMemberAccess = lib.organizationMemberAccess)
      (info, viewerMem, subscriptions)
    }
  }

  @StatsdTiming("libraryInfoCommander.createLibraryCardInfo")
  private def createLibraryCardInfo(lib: Library, image: Option[LibraryImage], owner: BasicUser, numFollowers: Int,
    followers: Seq[BasicUser], numCollaborators: Int, collaborators: Seq[BasicUser], isFollowing: Option[Boolean], membershipInfoOpt: Option[LibraryMembershipInfo], inviteInfoOpt: Option[LibraryInviteInfo], permissions: Set[LibraryPermission], path: String, orgView: Option[BasicOrganizationView]): LibraryCardInfo = {
    LibraryCardInfo(
      id = Library.publicId(lib.id.get),
      name = lib.name,
      description = lib.description,
      color = lib.color,
      image = image.map(LibraryImageInfo.fromImage),
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
      orgMemberAccess = if (lib.organizationId.isDefined) Some(lib.organizationMemberAccess.getOrElse(LibraryAccess.READ_WRITE)) else None
    )
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

}
