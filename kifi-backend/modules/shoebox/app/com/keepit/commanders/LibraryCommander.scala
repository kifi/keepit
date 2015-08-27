package com.keepit.commanders

import com.google.inject.{ ImplementedBy, Inject, Provider }
import com.keepit.abook.ABookServiceClient
import com.keepit.commanders.emails.{ EmailOptOutCommander, LibraryInviteEmailSender }
import com.keepit.common.akka.SafeFuture
import com.keepit.common.cache._
import com.keepit.common.concurrent.FutureHelpers
import com.keepit.common.core._
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail.{ BasicContact, EmailAddress }
import com.keepit.common.performance.{ AlertingTimer, StatsdTiming }
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.store.{ ImageSize, S3ImageStore }
import com.keepit.common.time._
import com.keepit.eliza.{ ElizaServiceClient, LibraryPushNotificationCategory, PushNotificationExperiment, UserPushNotificationCategory }
import com.keepit.heimdal.{ HeimdalContext, HeimdalContextBuilderFactory, HeimdalServiceClient }
import com.keepit.model.LibrarySpace.{ OrganizationSpace, UserSpace }
import com.keepit.model._
import com.keepit.notify.model.Recipient
import com.keepit.notify.model.event.{ LibraryNewKeep, OwnedLibraryNewCollaborator, OwnedLibraryNewFollower }
import com.keepit.search.SearchServiceClient
import com.keepit.social.{ BasicNonUser, BasicUser }
import com.keepit.typeahead.KifiUserTypeahead
import com.kifi.macros.json
import org.joda.time.DateTime
import play.api.http.Status._
import play.api.libs.json._

import scala.collection.parallel.ParSeq
import scala.concurrent._
import scala.concurrent.duration._

@json case class MarketingSuggestedLibrarySystemValue(
  id: Id[Library],
  caption: Option[String] = None)

object MarketingSuggestedLibrarySystemValue {
  // system value that persists the library IDs and additional library data for the marketing site
  def systemValueName = Name[SystemValue]("marketing_site_libraries")
}

@ImplementedBy(classOf[LibraryCommanderImpl])
trait LibraryCommander {
  def getKeeps(libraryId: Id[Library], offset: Int, limit: Int, useMultilibLogic: Boolean = false): Future[Seq[Keep]]
  def getKeepsCount(libraryId: Id[Library]): Future[Int]
  def updateLastView(userId: Id[User], libraryId: Id[Library]): Unit
  def getLibraryById(userIdOpt: Option[Id[User]], showPublishedLibraries: Boolean, id: Id[Library], imageSize: ImageSize, viewerId: Option[Id[User]])(implicit context: HeimdalContext): Future[FullLibraryInfo]
  def getLibrarySummaries(libraryIds: Seq[Id[Library]]): Seq[LibraryInfo]
  def getBasicLibraryDetails(libraryIds: Set[Id[Library]], idealImageSize: ImageSize, viewerId: Option[Id[User]]): Map[Id[Library], BasicLibraryDetails]
  def getLibraryWithOwnerAndCounts(libraryId: Id[Library], viewerUserId: Id[User]): Either[LibraryFail, (Library, BasicUser, Int, Option[Boolean], Boolean)]
  def getViewerMembershipInfo(userIdOpt: Option[Id[User]], libraryId: Id[Library]): Option[LibraryMembershipInfo]
  def sortUsersByImage(users: Seq[BasicUser]): Seq[BasicUser]
  def createFullLibraryInfos(viewerUserIdOpt: Option[Id[User]], showPublishedLibraries: Boolean, maxMembersShown: Int, maxKeepsShown: Int, idealKeepImageSize: ImageSize, libraries: Seq[Library], idealLibraryImageSize: ImageSize, withKeepTime: Boolean, useMultilibLogic: Boolean = false): Future[Seq[(Id[Library], FullLibraryInfo)]]
  def createFullLibraryInfo(viewerUserIdOpt: Option[Id[User]], showPublishedLibraries: Boolean, library: Library, libImageSize: ImageSize, showKeepCreateTime: Boolean = true, useMultilibLogic: Boolean = false): Future[FullLibraryInfo]
  def getLibraryMembersAndInvitees(libraryId: Id[Library], offset: Int, limit: Int, fillInWithInvites: Boolean): Seq[MaybeLibraryMember]
  def suggestMembers(userId: Id[User], libraryId: Id[Library], query: Option[String], limit: Option[Int]): Future[Seq[MaybeLibraryMember]]
  def addLibrary(libAddReq: LibraryAddRequest, ownerId: Id[User])(implicit context: HeimdalContext): Either[LibraryFail, Library]
  def getLibrariesWithWriteAccess(userId: Id[User]): Set[Id[Library]]
  def removeLibrary(libraryId: Id[Library], userId: Id[User])(implicit context: HeimdalContext): Option[LibraryFail]
  def canViewLibrary(userId: Option[Id[User]], library: Library, authToken: Option[String] = None): Boolean
  def canViewLibrary(userId: Option[Id[User]], libraryId: Id[Library], accessToken: Option[String]): Boolean
  def getLibrariesByUser(userId: Id[User]): (Seq[(LibraryMembership, Library)], Seq[(LibraryInvite, Library)])
  def getLibrariesUserCanKeepTo(userId: Id[User]): Seq[(Library, LibraryMembership, Set[Id[User]])]
  def userAccess(userId: Id[User], libraryId: Id[Library], universalLinkOpt: Option[String]): Option[LibraryAccess]
  def createReadItLaterLibrary(userId: Id[User]): Library
  def notifyFollowersOfNewKeeps(library: Library, newKeeps: Keep*): Unit
  def joinLibrary(userId: Id[User], libraryId: Id[Library], authToken: Option[String] = None, subscribed: Option[Boolean] = None)(implicit eventContext: HeimdalContext): Either[LibraryFail, (Library, LibraryMembership)]
  def leaveLibrary(libraryId: Id[Library], userId: Id[User])(implicit eventContext: HeimdalContext): Either[LibraryFail, Unit]
  def sortAndSelectLibrariesWithTopGrowthSince(libraryIds: Set[Id[Library]], since: DateTime, totalMemberCount: Id[Library] => Int): Seq[(Id[Library], Seq[LibraryMembership])]
  def sortAndSelectLibrariesWithTopGrowthSince(libraryMemberCountsSince: Map[Id[Library], Int], since: DateTime, totalMemberCount: Id[Library] => Int): Seq[(Id[Library], Seq[LibraryMembership])]
  def copyKeepsFromCollectionToLibrary(userId: Id[User], libraryId: Id[Library], tagName: Hashtag)(implicit context: HeimdalContext): Either[LibraryFail, (Seq[Keep], Seq[(Keep, LibraryError)])]
  def moveKeepsFromCollectionToLibrary(userId: Id[User], libraryId: Id[Library], tagName: Hashtag)(implicit context: HeimdalContext): Either[LibraryFail, (Seq[Keep], Seq[(Keep, LibraryError)])]
  def copyKeeps(userId: Id[User], toLibraryId: Id[Library], keeps: Seq[Keep], withSource: Option[KeepSource])(implicit context: HeimdalContext): (Seq[Keep], Seq[(Keep, LibraryError)])
  def moveKeeps(userId: Id[User], toLibraryId: Id[Library], keeps: Seq[Keep])(implicit context: HeimdalContext): (Seq[Keep], Seq[(Keep, LibraryError)])
  def moveAllKeepsFromLibrary(userId: Id[User], fromLibraryId: Id[Library], toLibraryId: Id[Library])(implicit context: HeimdalContext): (Seq[Keep], Seq[(Keep, LibraryError)])
  def trackLibraryView(viewerId: Option[Id[User]], library: Library)(implicit context: HeimdalContext): Unit
  def updateLastEmailSent(userId: Id[User], keeps: Seq[Keep]): Unit
  def updateSubscribedToLibrary(userId: Id[User], libraryId: Id[Library], subscribedToUpdatesNew: Boolean): Either[LibraryFail, LibraryMembership]
  def updateLibraryMembershipAccess(requestUserId: Id[User], libraryId: Id[Library], targetUserId: Id[User], newAccess: Option[LibraryAccess]): Either[LibraryFail, LibraryMembership]

  def unsafeTransferLibrary(libraryId: Id[Library], newOwner: Id[User]): Library
}

class LibraryCommanderImpl @Inject() (
    db: Database,
    libraryRepo: LibraryRepo,
    libraryMembershipRepo: LibraryMembershipRepo,
    libraryAliasRepo: LibraryAliasRepo,
    libraryInviteRepo: LibraryInviteRepo,
    libraryInviteCommander: LibraryInviteCommander,
    librarySubscriptionCommander: LibrarySubscriptionCommander,
    organizationMembershipRepo: OrganizationMembershipRepo,
    orgRepo: OrganizationRepo,
    organizationCommander: OrganizationCommander,
    organizationMembershipCommander: OrganizationMembershipCommander,
    userRepo: UserRepo,
    basicUserRepo: BasicUserRepo,
    keepRepo: KeepRepo,
    keepCommander: KeepCommander,
    keepToCollectionRepo: KeepToCollectionRepo,
    ktlRepo: KeepToLibraryRepo,
    ktlCommander: KeepToLibraryCommander,
    keepDecorator: KeepDecorator,
    countByLibraryCache: CountByLibraryCache,
    typeaheadCommander: TypeaheadCommander,
    kifiUserTypeahead: KifiUserTypeahead,
    collectionRepo: CollectionRepo,
    s3ImageStore: S3ImageStore,
    airbrake: AirbrakeNotifier,
    searchClient: SearchServiceClient,
    elizaClient: ElizaServiceClient,
    libraryAnalytics: LibraryAnalytics,
    libraryImageCommander: LibraryImageCommander,
    libPathCommander: PathCommander,
    experimentCommander: LocalUserExperimentCommander,
    twitterSyncRepo: TwitterSyncStateRepo,
    kifiInstallationCommander: KifiInstallationCommander,
    implicit val defaultContext: ExecutionContext,
    implicit val publicIdConfig: PublicIdConfiguration,
    clock: Clock) extends LibraryCommander with Logging {

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

  def updateLastView(userId: Id[User], libraryId: Id[Library]): Unit = {
    Future {
      db.readWrite { implicit s =>
        libraryMembershipRepo.getWithLibraryIdAndUserId(libraryId, userId).map { mem =>
          libraryMembershipRepo.updateLastViewed(mem.id.get) // do not update seq num
        }
      }
    }
  }

  def getLibraryById(userIdOpt: Option[Id[User]], showPublishedLibraries: Boolean, id: Id[Library], imageSize: ImageSize, viewerId: Option[Id[User]])(implicit context: HeimdalContext): Future[FullLibraryInfo] = {
    val lib = db.readOnlyMaster { implicit s => libraryRepo.get(id) }
    libraryAnalytics.viewedLibrary(viewerId, lib, context)
    createFullLibraryInfo(userIdOpt, showPublishedLibraries, lib, imageSize)
  }

  def getLibrarySummaries(libraryIds: Seq[Id[Library]]): Seq[LibraryInfo] = {
    db.readOnlyMaster { implicit session =>
      val libraries = libraryRepo.getLibraries(libraryIds.toSet).values.toSeq // cached
      getLibrarySummariesHelper(libraries)
    }
  }
  private def getLibrarySummariesHelper(libraries: Seq[Library])(implicit session: RSession): Seq[LibraryInfo] = {
    val ownersById = basicUserRepo.loadAll(libraries.map(_.ownerId).toSet) // cached
    libraries.map { lib =>
      val owner = ownersById(lib.ownerId)
      val org = lib.organizationId.map(orgRepo.get)
      LibraryInfo.fromLibraryAndOwner(lib, None, owner, org) // library images are not used, so no need to include
    }
  }

  def getLibraryPath(library: Library): String = {
    libPathCommander.getPathForLibrary(library)
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
        val numFollowers = counts.readOnly
        val numCollaborators = counts.readWrite
        val imageOpt = libraryImageCommander.getBestImageForLibrary(libId, idealImageSize).map(libraryImageCommander.getUrl)
        val membership = membershipsByLibraryId.get(libId).flatten
        libId -> BasicLibraryDetails(lib.name, lib.slug, lib.color, imageOpt, lib.description, numFollowers, numCollaborators, lib.keepCount, membership)
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
        val followerCount = libraryMembershipRepo.countWithLibraryIdByAccess(library.id.get).readOnly
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
    idealKeepImageSize: ImageSize, libraries: Seq[Library], idealLibraryImageSize: ImageSize, withKeepTime: Boolean, useMultilibLogic: Boolean = false): Future[Seq[(Id[Library], FullLibraryInfo)]] = {
    libraries.groupBy(l => l.id.get).foreach { case (lib, set) => if (set.size > 1) throw new Exception(s"There are ${set.size} identical libraries of $lib") }
    val futureKeepInfosByLibraryId = libraries.map { library =>
      library.id.get -> {
        if (maxKeepsShown > 0) {
          val ownerHasAllKeepsViewExperiment = experimentCommander.userHasExperiment(library.ownerId, UserExperimentType.ALL_KEEPS_VIEW)
          val keeps = db.readOnlyMaster { implicit session =>
            library.kind match {
              case LibraryKind.SYSTEM_MAIN =>
                assume(library.ownerId == viewerUserIdOpt.get, s"viewer ${viewerUserIdOpt.get} can't view a system library they do not own: $library")
                if (ownerHasAllKeepsViewExperiment) { //cached
                  keepRepo.getNonPrivate(library.ownerId, 0, maxKeepsShown) //not cached
                } else keepRepo.getByLibrary(library.id.get, 0, maxKeepsShown)
              case LibraryKind.SYSTEM_SECRET =>
                assume(library.ownerId == viewerUserIdOpt.get, s"viewer ${viewerUserIdOpt.get} can't view a system library they do not own: $library")
                if (ownerHasAllKeepsViewExperiment) { //cached
                  keepRepo.getPrivate(library.ownerId, 0, maxKeepsShown) //not cached
                } else keepRepo.getByLibrary(library.id.get, 0, maxKeepsShown) //not cached
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

    val orgCardByIdF = {
      val allOrgsShown = libraries.flatMap { library => library.organizationId }.toSet
      db.readOnlyReplicaAsync { implicit s =>
        val orgMap = orgRepo.getByIds(allOrgsShown)
        orgMap.map {
          case (id, _) => id -> organizationCommander.getOrganizationCardHelper(id, viewerUserIdOpt)
        }
      }
    }

    val futureCountsByLibraryId = {
      val keepCountsByLibraries: Map[Id[Library], Int] = db.readOnlyMaster { implicit s =>
        val userLibs = libraries.filter { lib => lib.kind == LibraryKind.USER_CREATED || lib.kind == LibraryKind.SYSTEM_PERSONA || lib.kind == LibraryKind.SYSTEM_READ_IT_LATER || lib.kind == LibraryKind.SYSTEM_GUIDE }.map(_.id.get).toSet
        var userLibCounts: Map[Id[Library], Int] = libraries.map(lib => lib.id.get -> lib.keepCount).toMap
        if (userLibs.size < libraries.size) {
          val privateLibOpt = libraries.find(_.kind == LibraryKind.SYSTEM_SECRET)
          val mainLibOpt = libraries.find(_.kind == LibraryKind.SYSTEM_MAIN)
          val owner = privateLibOpt.map(_.ownerId).orElse(mainLibOpt.map(_.ownerId)).getOrElse(
            throw new Exception(s"no main or secret libs in ${libraries.size} libs while userLibs counts for $userLibs is $userLibCounts. Libs are ${libraries.mkString("\n")}"))
          if (experimentCommander.userHasExperiment(owner, UserExperimentType.ALL_KEEPS_VIEW)) {
            val (privateCount, publicCount) = keepRepo.getPrivatePublicCountByUser(owner)
            privateLibOpt foreach { privateLib =>
              userLibCounts = userLibCounts + (privateLib.id.get -> privateCount)
            }
            mainLibOpt foreach { mainLib =>
              userLibCounts = userLibCounts + (mainLib.id.get -> publicCount)
            }
          } else {
            privateLibOpt foreach { privateLib =>
              userLibCounts = userLibCounts + (privateLib.id.get -> privateLib.keepCount)
            }
            mainLibOpt foreach { mainLib =>
              userLibCounts = userLibCounts + (mainLib.id.get -> mainLib.keepCount)
            }
          }
        }
        userLibCounts
      }
      libraries.map { library =>
        library.id.get -> SafeFuture {
          val counts = memberInfosByLibraryId(library.id.get).counts
          val collaboratorCount = counts.readWrite
          val followerCount = counts.readOnly
          val keepCount = keepCountsByLibraries.getOrElse(library.id.get, 0)
          (collaboratorCount, followerCount, keepCount)
        }
      }.toMap
    }

    val imagesF = libraries.map { library =>
      library.id.get -> SafeFuture { libraryImageCommander.getBestImageForLibrary(library.id.get, idealLibraryImageSize) } //not cached
    }.toMap

    val futureFullLibraryInfos = libraries.map { lib =>
      val libId = lib.id.get
      for {
        keepInfos <- futureKeepInfosByLibraryId(libId)
        counts <- futureCountsByLibraryId(libId)
        usersById <- usersByIdF
        orgCardById <- orgCardByIdF
        libImageOpt <- imagesF(libId)
      } yield {
        val (collaboratorCount, followerCount, keepCount) = counts
        val owner = usersById(lib.ownerId)
        val orgCardOpt = lib.organizationId.map(orgCardById.apply)
        val followers = memberInfosByLibraryId(lib.id.get).shown.map(usersById(_))
        val collaborators = memberInfosByLibraryId(lib.id.get).collaborators.map(usersById(_))
        val whoCanInvite = lib.whoCanInvite.getOrElse(LibraryInvitePermissions.COLLABORATOR) // todo: remove Option
        val attr = getSourceAttribution(libId)

        if (keepInfos.size > keepCount) {
          airbrake.notify(s"keep count $keepCount for library is lower then num of keeps ${keepInfos.size} for $lib")
        }
        lib.id.get -> FullLibraryInfo(
          id = Library.publicId(lib.id.get),
          name = lib.name,
          owner = owner,
          description = lib.description,
          slug = lib.slug,
          url = libPathCommander.getPathForLibrary(lib),
          color = lib.color,
          kind = lib.kind,
          visibility = lib.visibility,
          image = libImageOpt.map(LibraryImageInfoBuilder.createInfo),
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
          path = LibraryPathHelper.formatLibraryPath(owner = owner, orgHandleOpt = orgCardOpt.map(_.handle), slug = lib.slug),
          org = orgCardOpt
        )
      }
    }
    Future.sequence(futureFullLibraryInfos)
  }

  def getViewerMembershipInfo(userIdOpt: Option[Id[User]], libraryId: Id[Library]): Option[LibraryMembershipInfo] = {
    userIdOpt.flatMap { userId =>
      db.readOnlyMaster { implicit s =>
        libraryMembershipRepo.getWithLibraryIdAndUserId(libraryId, userId)
      } map (LibraryMembershipInfo.fromMembership(_))
    }
  }

  private def getSourceAttribution(libId: Id[Library]): Option[LibrarySourceAttribution] = {
    db.readOnlyReplica { implicit s =>
      twitterSyncRepo.getFirstHandleByLibraryId(libId).map { TwitterLibrarySourceAttribution(_) }
    }
  }

  def sortUsersByImage(users: Seq[BasicUser]): Seq[BasicUser] =
    users.sortBy(_.pictureName == BasicNonUser.DefaultPictureName)

  def createFullLibraryInfo(viewerUserIdOpt: Option[Id[User]], showPublishedLibraries: Boolean, library: Library, libImageSize: ImageSize, showKeepCreateTime: Boolean = true, useMultilibLogic: Boolean = false): Future[FullLibraryInfo] = {
    val maxMembersShown = 10
    createFullLibraryInfos(viewerUserIdOpt, showPublishedLibraries, maxMembersShown = maxMembersShown * 2, maxKeepsShown = 10, ProcessedImageSize.Large.idealSize, Seq(library), libImageSize, showKeepCreateTime, useMultilibLogic).imap {
      case Seq((_, info)) =>
        val followers = info.followers
        val sortedFollowers = sortUsersByImage(followers)
        info.copy(followers = sortedFollowers.take(maxMembersShown))
    }
  }

  def getLibraryMembersAndInvitees(libraryId: Id[Library], offset: Int, limit: Int, fillInWithInvites: Boolean): Seq[MaybeLibraryMember] = {
    val (collaborators, followers, inviteesWithInvites, count) = getLibraryMembersAndCount(libraryId, offset, limit, fillInWithInvites = fillInWithInvites)
    buildMaybeLibraryMembers(collaborators, followers, inviteesWithInvites)
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
      val numMembers = numCollaborators + memberCount.readOnly

      // Get Followers
      val followersLimit = limit - collaboratorsShown
      val followers = if (followersLimit == 0) Seq.empty[LibraryMembership] else {
        val followersOffset = if (collaboratorsShown > 0) 0 else {
          val collaboratorsTotal = numCollaborators
          offset - collaboratorsTotal
        }
        libraryMembershipRepo.pageWithLibraryIdAndAccess(libraryId, followersOffset, followersLimit, followersAccess) //not cached
      }

      // Get Invitees with Invites
      val membersShown = collaborators.length + followers.length
      val inviteesLimit = limit - membersShown
      val inviteesWithInvites = if (inviteesLimit == 0 || !fillInWithInvites) Seq.empty[(Either[Id[User], EmailAddress], Set[LibraryInvite])] else {
        val inviteesOffset = if (membersShown > 0) 0 else {
          val membersTotal = numMembers
          offset - membersTotal
        }
        libraryInviteRepo.pageInviteesByLibraryId(libraryId, inviteesOffset, inviteesLimit, relevantInviteStates) //not cached
      }
      (collaborators, followers, inviteesWithInvites, memberCount)
    }
    else (Seq.empty, Seq.empty, Seq.empty, CountWithLibraryIdByAccess.empty)
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

  def suggestMembers(userId: Id[User], libraryId: Id[Library], query: Option[String], limit: Option[Int]): Future[Seq[MaybeLibraryMember]] = {
    val futureFriendsAndContacts = query.map(_.trim).filter(_.nonEmpty) match {
      case Some(validQuery) => typeaheadCommander.searchFriendsAndContacts(userId, validQuery, limit)
      case None => Future.successful(typeaheadCommander.suggestFriendsAndContacts(userId, limit))
    }

    val activeInvites = db.readOnlyMaster { implicit session =>
      libraryInviteRepo.getByLibraryIdAndInviterId(libraryId, userId, Set(LibraryInviteStates.ACTIVE))
    }

    val invitedUsers = activeInvites.groupBy(_.userId).collect {
      case (Some(userId), invites) =>
        val access = invites.map(_.access).maxBy(_.priority)
        val lastInvitedAt = invites.map(_.createdAt).maxBy(_.getMillis)
        userId -> (access, lastInvitedAt)
    }

    val invitedEmailAddresses = activeInvites.groupBy(_.emailAddress).collect {
      case (Some(emailAddress), invites) =>
        val access = invites.map(_.access).maxBy(_.priority)
        val lastInvitedAt = invites.map(_.createdAt).maxBy(_.getMillis)
        emailAddress -> (access, lastInvitedAt)
    }

    futureFriendsAndContacts.map {
      case (users, contacts) =>
        val existingMembers = {
          val userIds = users.map(_._1).toSet
          val memberships = db.readOnlyMaster { implicit session => libraryMembershipRepo.getWithLibraryIdAndUserIds(libraryId, userIds) }
          memberships.mapValues(_.access)
        }
        val suggestedUsers = users.map {
          case (userId, basicUser) =>
            val (access, lastInvitedAt) = existingMembers.get(userId) match {
              case Some(access) => (Some(access), None)
              case None => invitedUsers.get(userId) match {
                case Some((access, lastInvitedAt)) => (Some(access), Some(lastInvitedAt))
                case None => (None, None)
              }
            }
            MaybeLibraryMember(Left(basicUser), access, lastInvitedAt)
        }

        val suggestedEmailAddresses = contacts.map { contact =>
          val (access, lastInvitedAt) = invitedEmailAddresses.get(contact.email) match {
            case Some((access, lastInvitedAt)) => (Some(access), Some(lastInvitedAt))
            case None => (None, None)
          }
          MaybeLibraryMember(Right(contact), access, lastInvitedAt)
        }
        suggestedUsers ++ suggestedEmailAddresses
    }
  }

  def addLibrary(libAddReq: LibraryAddRequest, ownerId: Id[User])(implicit context: HeimdalContext): Either[LibraryFail, Library] = {
    val badMessage: Option[String] = {
      if (libAddReq.name.isEmpty || !Library.isValidName(libAddReq.name)) {
        log.info(s"[addLibrary] Invalid name ${libAddReq.name} for $ownerId")
        Some("invalid_name")
      } else if (libAddReq.slug.isEmpty || !LibrarySlug.isValidSlug(libAddReq.slug)) {
        log.info(s"[addLibrary] Invalid slug ${libAddReq.slug} for $ownerId")
        Some("invalid_slug")
      } else if (LibrarySlug.isReservedSlug(libAddReq.slug)) {
        log.info(s"[addLibrary] Attempted reserved slug ${libAddReq.slug} for $ownerId")
        Some("reserved_slug")
      } else {
        None
      }
    }
    badMessage match {
      case Some(x) => Left(LibraryFail(BAD_REQUEST, x))
      case _ => {
        val validSlug = LibrarySlug(libAddReq.slug)
        val targetSpace = libAddReq.space.getOrElse(LibrarySpace.fromUserId(ownerId))
        val orgIdOpt = targetSpace match {
          case OrganizationSpace(orgId) => Some(orgId)
          case _ => None
        }
        db.readOnlyReplica { implicit s =>
          val userHasPermissionToCreateInSpace = targetSpace match {
            case OrganizationSpace(orgId) =>
              organizationMembershipCommander.getPermissionsHelper(orgId, Some(ownerId)).contains(OrganizationPermission.ADD_LIBRARIES)
            case UserSpace(userId) =>
              userId == ownerId // Right now this is guaranteed to be correct, could replace with true
          }
          val sameNameOpt = libraryRepo.getBySpaceAndName(targetSpace, libAddReq.name)
          val sameSlugOpt = libraryRepo.getBySpaceAndSlug(targetSpace, validSlug)
          (userHasPermissionToCreateInSpace, sameNameOpt, sameSlugOpt)
        } match {
          case (false, _, _) =>
            Left(LibraryFail(FORBIDDEN, "cannot_add_library_to_space"))
          case (_, Some(sameName), _) =>
            Left(LibraryFail(BAD_REQUEST, "library_name_exists"))
          case (_, _, Some(sameSlug)) =>
            Left(LibraryFail(BAD_REQUEST, "library_slug_exists"))
          case (_, None, None) =>
            val newColor = libAddReq.color.orElse(Some(LibraryColor.pickRandomLibraryColor()))
            val newListed = libAddReq.listed.getOrElse(true)
            val newKind = libAddReq.kind.getOrElse(LibraryKind.USER_CREATED)
            val newInviteToCollab = libAddReq.whoCanInvite.orElse(Some(LibraryInvitePermissions.COLLABORATOR))
            val library = db.readWrite { implicit s =>
              libraryAliasRepo.reclaim(targetSpace, validSlug) // there's gonna be a real library there, dump the alias
              libraryRepo.getBySpaceAndSlug(ownerId, validSlug, excludeStates = Set.empty) match {
                case None =>
                  val lib = libraryRepo.save(Library(ownerId = ownerId, name = libAddReq.name, description = libAddReq.description,
                    visibility = libAddReq.visibility, slug = validSlug, color = newColor, kind = newKind,
                    memberCount = 1, keepCount = 0, whoCanInvite = newInviteToCollab, organizationId = orgIdOpt))
                  libraryMembershipRepo.save(LibraryMembership(libraryId = lib.id.get, userId = ownerId, access = LibraryAccess.OWNER, listed = newListed, lastJoinedAt = Some(currentDateTime)))
                  libAddReq.subscriptions match {
                    case Some(subKeys) => librarySubscriptionCommander.updateSubsByLibIdAndKey(lib.id.get, subKeys)
                    case None =>
                  }
                  lib
                case Some(lib) =>
                  val newLib = libraryRepo.save(lib.copy(state = LibraryStates.ACTIVE))
                  libraryMembershipRepo.getWithLibraryIdAndUserId(libraryId = lib.id.get, userId = ownerId, None) match {
                    case None => libraryMembershipRepo.save(LibraryMembership(libraryId = lib.id.get, userId = ownerId, access = LibraryAccess.OWNER))
                    case Some(mem) => libraryMembershipRepo.save(mem.copy(state = LibraryMembershipStates.ACTIVE, listed = newListed))
                  }
                  libAddReq.subscriptions match {
                    case Some(subKeys) => librarySubscriptionCommander.updateSubsByLibIdAndKey(lib.id.get, subKeys)
                    case None =>
                  }
                  newLib
              }
            }
            SafeFuture {
              libraryAnalytics.createLibrary(ownerId, library, context)
              searchClient.updateLibraryIndex()
            }
            Right(library)
        }
      }
    }
  }

  def getLibrariesWithWriteAccess(userId: Id[User]): Set[Id[Library]] = {
    db.readOnlyMaster { implicit session => libraryMembershipRepo.getLibrariesWithWriteAccess(userId) }
  }

  def removeLibrary(libraryId: Id[Library], userId: Id[User])(implicit context: HeimdalContext): Option[LibraryFail] = {
    val oldLibrary = db.readOnlyMaster { implicit s => libraryRepo.get(libraryId) }
    if (oldLibrary.ownerId != userId) {
      Some(LibraryFail(FORBIDDEN, "permission_denied"))
    } else if (oldLibrary.kind == LibraryKind.SYSTEM_MAIN || oldLibrary.kind == LibraryKind.SYSTEM_SECRET) {
      Some(LibraryFail(BAD_REQUEST, "cant_delete_system_generated_library"))
    } else {
      val keepsInLibrary = db.readWrite { implicit s =>
        libraryMembershipRepo.getWithLibraryId(oldLibrary.id.get).foreach { m =>
          libraryMembershipRepo.save(m.withState(LibraryMembershipStates.INACTIVE))
        }
        libraryInviteRepo.getWithLibraryId(oldLibrary.id.get).foreach { inv =>
          libraryInviteRepo.save(inv.withState(LibraryInviteStates.INACTIVE))
        }
        keepRepo.getByLibrary(oldLibrary.id.get, 0, Int.MaxValue)
      }
      val savedKeeps = db.readWriteBatch(keepsInLibrary) { (s, keep) => // TODO(ryan): Can this session be made implicit?
        // ktlCommander.removeKeepFromLibrary(keep.id.get, libraryId)(s)
        keepCommander.deactivateKeep(keep)(s) // TODO(ryan): At some point, remove this code. Keeps should only be detached from libraries
      }
      libraryAnalytics.deleteLibrary(userId, oldLibrary, context)
      libraryAnalytics.unkeptPages(userId, savedKeeps.keySet.toSeq, oldLibrary, context)
      searchClient.updateKeepIndex()
      //Note that this is at the end, if there was an error while cleaning other library assets
      //we would want to be able to get back to the library and clean it again
      log.info(s"[zombieLibrary] Deleting lib: $oldLibrary")
      db.readWrite(attempts = 2) { implicit s =>
        libraryRepo.save(oldLibrary.sanitizeForDelete)
          .tap { l => log.info(s"[zombieLibrary] Should have deleted lib: $l") }
      }
      db.readOnlyMaster { implicit s =>
        libraryRepo.get(oldLibrary.id.get) match {
          case library if library.state == LibraryStates.ACTIVE => log.error(s"[zombieLibrary] Did not delete lib: $library")
          case library => log.info(s"[zombieLibrary] Successfully deleted lib: $library")
        }
      }
      searchClient.updateLibraryIndex()
      None
    }
  }

  def unsafeTransferLibrary(libId: Id[Library], newOwner: Id[User]): Library = {
    db.readWrite { implicit s =>
      val owner = userRepo.get(newOwner)
      assert(owner.state == UserStates.ACTIVE)

      val lib = libraryRepo.getNoCache(libId)

      libraryMembershipRepo.getWithLibraryIdAndUserId(libId, lib.ownerId).foreach { oldOwnerMembership =>
        libraryMembershipRepo.save(oldOwnerMembership.withState(LibraryMembershipStates.INACTIVE))
      }
      val existingMembershipOpt = libraryMembershipRepo.getWithLibraryIdAndUserId(libId, newOwner)
      val newMembershipTemplate = LibraryMembership(libraryId = libId, userId = newOwner, access = LibraryAccess.OWNER)
      libraryMembershipRepo.save(newMembershipTemplate.copy(id = existingMembershipOpt.map(_.id.get)))
      libraryRepo.save(lib.withOwner(newOwner))
    }
  }

  private def getValidLibInvitesFromAuthToken(libraryId: Id[Library], authToken: Option[String])(implicit s: RSession): Seq[LibraryInvite] = {
    if (authToken.nonEmpty) {
      libraryInviteRepo.getByLibraryIdAndAuthToken(libraryId, authToken.get) // todo: only accept 'general invites' for x days
    } else {
      Seq.empty[LibraryInvite]
    }
  }

  private def canViewLibraryHelper(userIdOpt: Option[Id[User]], library: Library, authToken: Option[String] = None)(implicit session: RSession): Boolean = {
    library.visibility == LibraryVisibility.PUBLISHED || // published library
      (userIdOpt match {
        case Some(id) =>
          val userIsInLibrary = libraryMembershipRepo.getWithLibraryIdAndUserId(library.id.get, id).nonEmpty
          def userIsInvitedToLibrary = libraryInviteRepo.getWithLibraryIdAndUserId(userId = id, libraryId = library.id.get).nonEmpty
          def userHasValidAuthToken = getValidLibInvitesFromAuthToken(library.id.get, authToken).nonEmpty
          def userIsInOrg = library.organizationId.flatMap(orgId => organizationMembershipRepo.getByOrgIdAndUserId(orgId, id)).nonEmpty
          val libIsOrgVisible = library.visibility == LibraryVisibility.ORGANIZATION
          userIsInLibrary || userIsInvitedToLibrary || userHasValidAuthToken || (userIsInOrg && libIsOrgVisible)
        case None =>
          getValidLibInvitesFromAuthToken(library.id.get, authToken).nonEmpty
      })
  }
  def canViewLibrary(userIdOpt: Option[Id[User]], library: Library, authToken: Option[String] = None): Boolean = {
    db.readOnlyReplica { implicit session => canViewLibraryHelper(userIdOpt, library, authToken) }
  }

  def canViewLibrary(userId: Option[Id[User]], libraryId: Id[Library], accessToken: Option[String]): Boolean = {
    db.readOnlyReplica { implicit session =>
      val library = libraryRepo.get(libraryId)
      library.state == LibraryStates.ACTIVE && canViewLibraryHelper(userId, library, accessToken)
    }
  }

  def getLibrariesByUser(userId: Id[User]): (Seq[(LibraryMembership, Library)], Seq[(LibraryInvite, Library)]) = {
    db.readOnlyMaster { implicit s =>
      val myLibraries = libraryRepo.getByUser(userId)
      val myInvites = libraryInviteRepo.getByUser(userId, Set(LibraryInviteStates.ACCEPTED, LibraryInviteStates.INACTIVE, LibraryInviteStates.DECLINED))
      (myLibraries, myInvites)
    }
  }

  def getLibrariesUserCanKeepTo(userId: Id[User]): Seq[(Library, LibraryMembership, Set[Id[User]])] = {
    db.readOnlyMaster { implicit s =>
      val libsWithMembership: Seq[(Library, LibraryMembership)] = libraryRepo.getLibrariesWithWriteAccess(userId)
      val libIds: Set[Id[Library]] = libsWithMembership.map(_._1.id.get).toSet
      val collaborators: Map[Id[Library], Set[Id[User]]] = libraryMembershipRepo.getCollaboratorsByLibrary(libIds)
      libsWithMembership.map {
        case (lib, membership) =>
          (lib, membership, collaborators(lib.id.get))
      }
    }
  }

  def userAccess(userId: Id[User], libraryId: Id[Library], universalLinkOpt: Option[String]): Option[LibraryAccess] = {
    db.readOnlyMaster { implicit s =>
      val lib = libraryRepo.get(libraryId)
      lib.state match {
        case LibraryStates.ACTIVE =>
          libraryMembershipRepo.getWithLibraryIdAndUserId(libraryId, userId) match {
            case Some(mem) =>
              Some(mem.access)
            case None =>
              if (lib.visibility == LibraryVisibility.PUBLISHED)
                Some(LibraryAccess.READ_ONLY)
              else if (libraryInviteRepo.getWithLibraryIdAndUserId(libraryId, userId).nonEmpty)
                Some(LibraryAccess.READ_ONLY)
              else if (universalLinkOpt.nonEmpty && lib.universalLink == universalLinkOpt.get)
                Some(LibraryAccess.READ_ONLY)
              else
                None
          }
        case _ => None
      }
    }
  }

  def createReadItLaterLibrary(userId: Id[User]): Library = db.readWrite(attempts = 3) { implicit s =>
    val readItLaterLib = libraryRepo.save(Library(name = "Read It Later", ownerId = userId, visibility = LibraryVisibility.SECRET, slug = LibrarySlug("read_id_later"), kind = LibraryKind.SYSTEM_READ_IT_LATER, memberCount = 1, keepCount = 0))
    libraryMembershipRepo.save(LibraryMembership(libraryId = readItLaterLib.id.get, userId = userId, access = LibraryAccess.OWNER))
    searchClient.updateLibraryIndex()
    readItLaterLib
  }

  private def notifyOwnerOfNewFollowerOrCollaborator(newFollowerId: Id[User], lib: Library, access: LibraryAccess): Unit = SafeFuture {
    val (follower, owner, lotsOfFollowers) = db.readOnlyReplica { implicit session =>
      val follower = userRepo.get(newFollowerId)
      val owner = basicUserRepo.load(lib.ownerId)
      val lotsOfFollowers = libraryMembershipRepo.countMembersForLibrarySince(lib.id.get, DateTime.now().minusDays(1)) > 2
      (follower, owner, lotsOfFollowers)
    }
    val (title, category, message) = if (access == LibraryAccess.READ_WRITE) {
      // This should be changed to library_collaborated but right now iOS skips categories it doesn't know.
      ("New Library Collaborator", NotificationCategory.User.LIBRARY_FOLLOWED, s"${follower.firstName} ${follower.lastName} is now collaborating on your Library ${lib.name}")
    } else {
      ("New Library Follower", NotificationCategory.User.LIBRARY_FOLLOWED, s"${follower.firstName} ${follower.lastName} is now following your Library ${lib.name}")
    }
    val libImageOpt = libraryImageCommander.getBestImageForLibrary(lib.id.get, ProcessedImageSize.Medium.idealSize)
    elizaClient.sendGlobalNotification( //push sent
      userIds = Set(lib.ownerId),
      title = title,
      body = message,
      linkText = s"See ${follower.firstName}’s profile",
      linkUrl = s"https://www.kifi.com/${follower.username.value}",
      imageUrl = s3ImageStore.avatarUrlByUser(follower),
      sticky = false,
      category = category,
      unread = !lotsOfFollowers, // if not a lot of recent followers, notification is marked unread
      extra = Some(Json.obj(
        "follower" -> BasicUser.fromUser(follower),
        "library" -> Json.toJson(LibraryNotificationInfoBuilder.fromLibraryAndOwner(lib, libImageOpt, owner))
      ))
    ) map { _ =>
        if (!lotsOfFollowers) {
          val canSendPush = kifiInstallationCommander.isMobileVersionEqualOrGreaterThen(lib.ownerId, KifiAndroidVersion("2.2.4"), KifiIPhoneVersion("2.1.0"))
          if (canSendPush) {
            val pushCat = category match {
              case NotificationCategory.User.LIBRARY_COLLABORATED => UserPushNotificationCategory.NewLibraryCollaborator
              case _ => UserPushNotificationCategory.NewLibraryFollower
            }
            elizaClient.sendUserPushNotification(
              userId = lib.ownerId,
              message = message,
              recipient = follower,
              pushNotificationExperiment = PushNotificationExperiment.Experiment1,
              category = pushCat)
          }
        }
      }
    if (access == LibraryAccess.READ_WRITE) {
      elizaClient.sendNotificationEvent(OwnedLibraryNewCollaborator(
        Recipient(lib.ownerId),
        currentDateTime,
        newFollowerId,
        lib.id.get
      ))
    } else {
      elizaClient.sendNotificationEvent(OwnedLibraryNewFollower(
        Recipient(lib.ownerId),
        currentDateTime,
        newFollowerId,
        lib.id.get
      ))
    }
  }

  def notifyFollowersOfNewKeeps(library: Library, newKeeps: Keep*): Unit = {
    newKeeps.foreach { newKeep =>
      // old way, should be safe to remove
      if (newKeep.libraryId.get != library.id.get) { throw new IllegalArgumentException(s"Keep ${newKeep.id.get} does not belong to expected library ${library.id.get}") }
    }
    val (relevantFollowers, usersById) = db.readOnlyReplica { implicit session =>
      val relevantFollowers: Set[Id[User]] = libraryMembershipRepo.getWithLibraryId(library.id.get).filter(_.subscribedToUpdates).map(_.userId).toSet
      val usersById = userRepo.getUsers(newKeeps.map(_.userId) :+ library.ownerId)
      (relevantFollowers, usersById)
    }
    val libImageOpt = libraryImageCommander.getBestImageForLibrary(library.id.get, ProcessedImageSize.Medium.idealSize)
    val owner = usersById(library.ownerId)
    newKeeps.foreach { newKeep =>
      val toBeNotified = relevantFollowers - newKeep.userId
      if (toBeNotified.nonEmpty) {
        val keeper = usersById(newKeep.userId)
        val basicKeeper = BasicUser.fromUser(keeper)
        elizaClient.sendGlobalNotification(
          userIds = toBeNotified,
          title = s"New Keep in ${library.name}",
          body = s"${keeper.firstName} has just kept ${newKeep.title.getOrElse("a new item")}",
          linkText = "Go to Page",
          linkUrl = newKeep.url,
          imageUrl = s3ImageStore.avatarUrlByUser(keeper),
          sticky = false,
          category = NotificationCategory.User.NEW_KEEP,
          extra = Some(Json.obj(
            "keeper" -> basicKeeper,
            "library" -> Json.toJson(LibraryNotificationInfoBuilder.fromLibraryAndOwner(library, libImageOpt, basicKeeper)),
            "keep" -> Json.obj(
              "id" -> newKeep.externalId,
              "url" -> newKeep.url
            )
          ))
        ).foreach { _ =>
            if (toBeNotified.size > 100) {
              airbrake.notify("Warning: Library with lots of subscribers. Time to make the code better!")
            }
            val libTrunc = if (library.name.length > 30) { library.name.take(30) + "…" } else { library.name }
            val message = newKeep.title match {
              case Some(title) =>
                val trunc = if (title.length > 30) { title.take(30) + "…" } else { title }
                s"“$trunc” added to $libTrunc"
              case None =>
                s"New keep added to $libTrunc"
            }
            FutureHelpers.sequentialExec(toBeNotified) { userId =>
              elizaClient.sendLibraryPushNotification(
                userId,
                message = message,
                libraryId = library.id.get,
                libraryUrl = "https://www.kifi.com" + libPathCommander.getPathForLibrary(library),
                pushNotificationExperiment = PushNotificationExperiment.Experiment1,
                category = LibraryPushNotificationCategory.LibraryChanged
              )
            }
          }
        toBeNotified foreach { userId =>
          elizaClient.sendNotificationEvent(LibraryNewKeep(
            Recipient(userId),
            currentDateTime,
            newKeep.userId,
            newKeep.id.get,
            library.id.get
          ))
        }
      }
    }
  }

  def joinLibrary(userId: Id[User], libraryId: Id[Library], authToken: Option[String] = None, subscribed: Option[Boolean] = None)(implicit eventContext: HeimdalContext): Either[LibraryFail, (Library, LibraryMembership)] = {
    val (lib, inviteList, existingActiveMembership) = db.readOnlyMaster { implicit s =>
      val lib = libraryRepo.get(libraryId)
      val tokenInvites = if (authToken.isDefined) {
        getValidLibInvitesFromAuthToken(libraryId, authToken)
      } else Seq.empty
      val libInvites = libraryInviteRepo.getWithLibraryIdAndUserId(libraryId, userId)
      val allInvites = tokenInvites ++ libInvites
      val existingActiveMembership = libraryMembershipRepo.getWithLibraryIdAndUserId(libraryId, userId)
      (lib, allInvites, existingActiveMembership)
    }

    val isSystemGeneratedLibrary = lib.kind == LibraryKind.SYSTEM_MAIN || lib.kind == LibraryKind.SYSTEM_SECRET
    val userCanJoinLibraryWithoutInvite = canViewLibrary(Some(userId), lib, authToken) // uses a db session

    if (isSystemGeneratedLibrary) {
      Left(LibraryFail(FORBIDDEN, "cant_join_system_generated_library"))
    } else if (!userCanJoinLibraryWithoutInvite && inviteList.isEmpty && existingActiveMembership.isEmpty) {
      // private library & no library invites with matching authtoken
      Left(LibraryFail(FORBIDDEN, "cant_join_library_without_an_invite"))
    } else {
      val maxAccess = if (inviteList.isEmpty) LibraryAccess.READ_ONLY else inviteList.max.access
      val (updatedLib, updatedMem) = db.readWrite(attempts = 3) { implicit s =>
        val updatedMem = libraryMembershipRepo.getWithLibraryIdAndUserId(libraryId, userId, None) match {
          case None =>
            val subscribedToUpdates = subscribed.getOrElse(maxAccess == LibraryAccess.READ_WRITE)
            log.info(s"[joinLibrary] New membership for $userId. New access: $maxAccess. $inviteList")
            val mem = libraryMembershipRepo.save(LibraryMembership(libraryId = libraryId, userId = userId, access = maxAccess, lastJoinedAt = Some(clock.now), subscribedToUpdates = subscribedToUpdates))
            notifyOwnerOfNewFollowerOrCollaborator(userId, lib, maxAccess) // todo, bad, this is in a db transaction and side effects
            mem
          case Some(mem) =>
            val maxWithExisting = if (mem.state == LibraryMembershipStates.ACTIVE) Seq(maxAccess, mem.access).max else maxAccess
            val subscribedToUpdates = subscribed.getOrElse(maxWithExisting == LibraryAccess.READ_WRITE || mem.subscribedToUpdates)
            log.info(s"[joinLibrary] Modifying membership for ${mem.userId} / $userId. Old access: ${mem.access} (${mem.state}), new: $maxWithExisting. $maxAccess, $inviteList")
            libraryMembershipRepo.save(mem.copy(access = maxWithExisting, state = LibraryMembershipStates.ACTIVE, lastJoinedAt = Some(clock.now), subscribedToUpdates = subscribedToUpdates))
        }

        inviteList.foreach { inv =>
          // Only update invitations to a specific user. If it's to a specific recipient. Otherwise, leave it open for others.
          if (inv.userId.isDefined || inv.emailAddress.isDefined) {
            libraryInviteRepo.save(inv.copy(state = LibraryInviteStates.ACCEPTED))
          }
        }

        val invitesToAlert = inviteList.filterNot(_.inviterId == lib.ownerId)
        if (invitesToAlert.nonEmpty) {
          val invitee = userRepo.get(userId)
          val owner = basicUserRepo.load(lib.ownerId)
          libraryInviteCommander.notifyInviterOnLibraryInvitationAcceptance(invitesToAlert, invitee, lib, owner) // todo, bad, this is in a db transaction and side effects
        }

        val updatedLib = libraryRepo.save(lib.copy(memberCount = libraryMembershipRepo.countWithLibraryId(libraryId)))
        (updatedLib, updatedMem)
      }
      updateLibraryJoin(userId, lib, updatedMem, eventContext)
      Right((updatedLib, updatedMem))
    }
  }

  private def updateLibraryJoin(userId: Id[User], library: Library, membership: LibraryMembership, eventContext: HeimdalContext): Future[Unit] = SafeFuture {
    val libraryId = library.id.get
    libraryAnalytics.acceptLibraryInvite(userId, library, eventContext)
    libraryAnalytics.followLibrary(userId, library, eventContext)
    searchClient.updateLibraryIndex()
    if (LibraryAccess.collaborativePermissions.contains(membership.access)) {
      refreshLibraryCollaboratorsTypeahead(libraryId)
    }
  }

  def leaveLibrary(libraryId: Id[Library], userId: Id[User])(implicit eventContext: HeimdalContext): Either[LibraryFail, Unit] = {
    db.readOnlyMaster { implicit s =>
      libraryMembershipRepo.getWithLibraryIdAndUserId(libraryId, userId, None)
    } match {
      case None => Right((): Unit)
      case Some(mem) if mem.access == LibraryAccess.OWNER => Left(LibraryFail(BAD_REQUEST, "cannot_leave_own_library"))
      case Some(mem) =>
        val lib = db.readWrite { implicit s =>
          libraryMembershipRepo.save(mem.copy(state = LibraryMembershipStates.INACTIVE))
          val lib = libraryRepo.get(libraryId)
          libraryRepo.save(lib.copy(memberCount = libraryMembershipRepo.countWithLibraryId(libraryId)))
          lib
        }
        SafeFuture {
          convertKeepOwnershipToLibraryOwner(userId, lib)
          libraryAnalytics.unfollowLibrary(userId, lib, eventContext)
          searchClient.updateLibraryIndex()
          if (LibraryAccess.collaborativePermissions.contains(mem.access)) {
            refreshLibraryCollaboratorsTypeahead(libraryId)
          }
        }
        Right((): Unit)
    }
  }

  // TODO(ryan): is this actually necessary anymore? Check with Léo to see if Search needs it
  // We may not need to have this concept of "keep ownership" anymore. You can be the author of
  // a keep, which is its own thing. You can have access to edit a keep, which is also its own
  // thing.
  private def convertKeepOwnershipToLibraryOwner(userId: Id[User], library: Library) = {
    db.readWrite { implicit s =>
      keepRepo.getByUserIdAndLibraryId(userId, library.id.get).foreach { keep =>
        keepCommander.changeOwner(keep, library.ownerId)
      }
      ktlRepo.getByUserIdAndLibraryId(userId, library.id.get).foreach {
        ktlCommander.changeOwner(_, library.ownerId)
      }
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

  // Return is Set of Keep -> error message
  private def applyToKeeps(userId: Id[User],
    dstLibraryId: Id[Library],
    keeps: Seq[Keep],
    excludeFromAccess: Set[LibraryAccess], // what membership access does user need?
    saveKeep: (Keep, RWSession) => Either[LibraryError, Keep]): (Seq[Keep], Seq[(Keep, LibraryError)]) = {

    val badKeeps = collection.mutable.Set[(Keep, LibraryError)]()
    val goodKeeps = collection.mutable.Set[Keep]()
    val srcLibs = db.readWrite { implicit s =>
      val groupedKeeps = keeps.groupBy(_.libraryId)
      groupedKeeps.flatMap {
        case (None, keeps) => keeps
        case (Some(fromLibraryId), keeps) =>
          libraryMembershipRepo.getWithLibraryIdAndUserId(fromLibraryId, userId) match {
            case None if excludeFromAccess.nonEmpty =>
              badKeeps ++= keeps.map(_ -> LibraryError.SourcePermissionDenied)
              Seq.empty[Keep]
            case Some(memFrom) if excludeFromAccess.contains(memFrom.access) =>
              badKeeps ++= keeps.map(_ -> LibraryError.SourcePermissionDenied)
              Seq.empty[Keep]
            case _ =>
              keeps
          }
      }.foreach { keep =>
        saveKeep(keep, s) match {
          case Left(error) => badKeeps += keep -> error
          case Right(successKeep) => goodKeeps += successKeep
        }
      }
      if (goodKeeps.nonEmpty) libraryRepo.updateLastKept(dstLibraryId)

      groupedKeeps.keys.flatten
    }
    searchClient.updateKeepIndex()

    implicit val dca = TransactionalCaching.Implicits.directCacheAccess
    srcLibs.map { srcLibId =>
      countByLibraryCache.remove(CountByLibraryKey(srcLibId))
    }
    countByLibraryCache.remove(CountByLibraryKey(dstLibraryId))

    // fix keep count after we cleared cache
    fixLibraryKeepCount(dstLibraryId :: srcLibs.toList)

    (goodKeeps.toSeq, badKeeps.toSeq)
  }

  private def fixLibraryKeepCount(libIds: Seq[Id[Library]]) = {
    db.readWrite { implicit s =>
      val counts = keepRepo.getCountsByLibrary(libIds.toSet)
      libIds.foreach { libId =>
        val lib = libraryRepo.get(libId)
        libraryRepo.save(lib.copy(keepCount = counts(lib.id.get)))
      }
    }
  }

  def copyKeepsFromCollectionToLibrary(userId: Id[User], libraryId: Id[Library], tagName: Hashtag)(implicit context: HeimdalContext): Either[LibraryFail, (Seq[Keep], Seq[(Keep, LibraryError)])] = {
    db.readOnlyMaster { implicit s =>
      collectionRepo.getByUserAndName(userId, tagName)
    } match {
      case None =>
        Left(LibraryFail(NOT_FOUND, "tag_not_found"))
      case Some(tag) =>
        val keeps = db.readOnlyMaster { implicit s =>
          keepToCollectionRepo.getKeepsForTag(tag.id.get).map { kId => keepRepo.get(kId) }
        }
        Right(copyKeeps(userId, libraryId, keeps, withSource = Some(KeepSource.tagImport)))
    }
  }

  def moveKeepsFromCollectionToLibrary(userId: Id[User], libraryId: Id[Library], tagName: Hashtag)(implicit context: HeimdalContext): Either[LibraryFail, (Seq[Keep], Seq[(Keep, LibraryError)])] = {
    db.readOnlyMaster { implicit s =>
      collectionRepo.getByUserAndName(userId, tagName)
    } match {
      case None =>
        Left(LibraryFail(NOT_FOUND, "tag_not_found"))
      case Some(tag) =>
        val keeps = db.readOnlyMaster { implicit s =>
          keepToCollectionRepo.getKeepsForTag(tag.id.get).map { kId => keepRepo.get(kId) }
        }
        Right(moveKeeps(userId, libraryId, keeps))
    }
  }

  def copyKeeps(userId: Id[User], toLibraryId: Id[Library], keeps: Seq[Keep], withSource: Option[KeepSource])(implicit context: HeimdalContext): (Seq[Keep], Seq[(Keep, LibraryError)]) = {
    val (toLibrary, memTo) = db.readOnlyMaster { implicit s =>
      val library = libraryRepo.get(toLibraryId)
      val memTo = libraryMembershipRepo.getWithLibraryIdAndUserId(toLibraryId, userId)
      (library, memTo)
    }
    memTo match {
      case v if v.isEmpty || v.get.access == LibraryAccess.READ_ONLY =>
        (Seq.empty[Keep], keeps.map(_ -> LibraryError.DestPermissionDenied))
      case Some(_) =>
        val sortedKeeps = keeps.sortBy(k => (k.keptAt, k.id.get))
        val keepResults = applyToKeeps(userId, toLibraryId, sortedKeeps, Set(), (k, s) => keepCommander.copyKeep(k, toLibrary, userId, withSource)(s))
        Future {
          libraryAnalytics.editLibrary(userId, toLibrary, context, Some("copy_keeps"))
        }
        keepResults
    }
  }

  def moveAllKeepsFromLibrary(userId: Id[User], fromLibraryId: Id[Library], toLibraryId: Id[Library])(implicit context: HeimdalContext): (Seq[Keep], Seq[(Keep, LibraryError)]) = {
    val keeps = db.readOnlyReplica { implicit session =>
      val keepIds = ktlRepo.getAllByLibraryId(fromLibraryId).map(_.keepId).toSet
      keepRepo.getByIds(keepIds).values.toSeq
    }
    moveKeeps(userId, toLibraryId, keeps)
  }
  def moveKeeps(userId: Id[User], toLibraryId: Id[Library], keeps: Seq[Keep])(implicit context: HeimdalContext): (Seq[Keep], Seq[(Keep, LibraryError)]) = {
    val (toLibrary, memTo) = db.readOnlyMaster { implicit s =>
      val library = libraryRepo.get(toLibraryId)
      val memTo = libraryMembershipRepo.getWithLibraryIdAndUserId(toLibraryId, userId)
      (library, memTo)
    }
    memTo match {
      case v if v.isEmpty || v.get.access == LibraryAccess.READ_ONLY =>
        (Seq.empty[Keep], keeps.map(_ -> LibraryError.DestPermissionDenied))
      case Some(_) =>
        val keepResults = applyToKeeps(userId, toLibraryId, keeps, Set(LibraryAccess.READ_ONLY), (k, s) => keepCommander.moveKeep(k, toLibrary, userId)(s))
        Future {
          libraryAnalytics.editLibrary(userId, toLibrary, context, Some("move_keeps"))
        }
        keepResults
    }
  }

  def trackLibraryView(viewerId: Option[Id[User]], library: Library)(implicit context: HeimdalContext): Unit = {
    libraryAnalytics.viewedLibrary(viewerId, library, context)
  }

  def updateLastEmailSent(userId: Id[User], keeps: Seq[Keep]): Unit = {
    // persist when we last sent an email for each library membership
    db.readWrite { implicit rw =>
      keeps.groupBy(_.libraryId).collect { case (Some(libId), _) => libId } foreach { libId =>
        libraryMembershipRepo.getWithLibraryIdAndUserId(libId, userId) map { libMembership =>
          libraryMembershipRepo.updateLastEmailSent(libMembership.id.get)
        }
      }
    }
  }

  def updateSubscribedToLibrary(userId: Id[User], libraryId: Id[Library], subscribedToUpdatesNew: Boolean): Either[LibraryFail, LibraryMembership] = {
    db.readOnlyMaster { implicit s =>
      libraryMembershipRepo.getWithLibraryIdAndUserId(libraryId, userId)
    } match {
      case None => Left(LibraryFail(NOT_FOUND, "need_to_follow_to_subscribe"))
      case Some(mem) if mem.subscribedToUpdates == subscribedToUpdatesNew => Right(mem)
      case Some(mem) => {
        val updatedMembership = db.readWrite { implicit s =>
          libraryMembershipRepo.save(mem.copy(subscribedToUpdates = subscribedToUpdatesNew))
        }
        Right(updatedMembership)
      }
    }
  }

  ///////////////////
  // Collaborators!
  ///////////////////

  def updateLibraryMembershipAccess(requestUserId: Id[User], libraryId: Id[Library], targetUserId: Id[User], newAccess: Option[LibraryAccess]): Either[LibraryFail, LibraryMembership] = {
    if (newAccess.isDefined && newAccess.get == LibraryAccess.OWNER) {
      Left(LibraryFail(BAD_REQUEST, "cannot_change_access_to_owner"))
    } else {
      db.readOnlyMaster { implicit s =>
        val membershipMap = libraryMembershipRepo.getWithLibraryIdAndUserIds(libraryId, Set(requestUserId, targetUserId))
        val library = libraryRepo.get(libraryId)
        (membershipMap.get(requestUserId), membershipMap.get(targetUserId), library)
      } match {
        case (None, _, _) =>
          Left(LibraryFail(NOT_FOUND, "request_membership_not_found"))
        case (_, None, _) =>
          Left(LibraryFail(NOT_FOUND, "target_membership_not_found"))
        case (Some(mem), Some(targetMem), _) if targetMem.access == LibraryAccess.OWNER =>
          Left(LibraryFail(BAD_REQUEST, "cannot_change_owner_access"))

        case (Some(requesterMem), Some(targetMem), library) =>

          if ((requesterMem.isOwner && !targetMem.isOwner) || // owners can edit anyone except themselves
            (requesterMem.isCollaborator && !targetMem.isOwner) || // a collaborator can edit anyone (but the owner). Collaborator cannot invite others to collaborate if the library does not allow collaborators to invite
            (requesterMem.isFollower && requesterMem.userId == targetMem.userId)) { // a follower can only edit herself
            db.readWrite { implicit s =>
              newAccess match {
                case None =>
                  SafeFuture { convertKeepOwnershipToLibraryOwner(targetMem.userId, library) }
                  Right(libraryMembershipRepo.save(targetMem.copy(state = LibraryMembershipStates.INACTIVE)))
                case Some(newAccess) if requesterMem.isCollaborator && newAccess == LibraryAccess.READ_WRITE && library.whoCanInvite == Some(LibraryInvitePermissions.OWNER) =>
                  log.warn(s"[updateLibraryMembership] invalid permission ${requesterMem} trying to change membership ${targetMem} to ${newAccess} when library has invite policy ${library.whoCanInvite}")
                  Left(LibraryFail(FORBIDDEN, "invalid_collaborator_permission"))
                case Some(newAccess) =>
                  val newSubscription = if (newAccess == LibraryAccess.READ_WRITE) true else targetMem.subscribedToUpdates // auto subscribe to updates if a collaborator
                  val inviter = userRepo.get(requestUserId)
                  val libOwner = basicUserRepo.load(library.ownerId)
                  val updatedTargetMembership = libraryMembershipRepo.save(targetMem.copy(access = newAccess, subscribedToUpdates = newSubscription, state = LibraryMembershipStates.ACTIVE))

                  if (inviter.id.get.id != targetUserId.id) {
                    libraryInviteCommander.notifyInviteeAboutInvitationToJoinLibrary(inviter, library, libOwner, Map(targetUserId -> updatedTargetMembership))
                  }

                  Right(updatedTargetMembership)
              }
            } tap {
              // Unless we're just kicking out a follower, the set of collaborators has changed.
              case Right(updatedMembership) if !(updatedMembership.isFollower && updatedMembership.state == LibraryMembershipStates.INACTIVE) => {
                SafeFuture { refreshLibraryCollaboratorsTypeahead(library.id.get) }
              }
              case _ => //
            }
          } else { // invalid permissions
            log.warn(s"[updateLibraryMembership] invalid permission ${requesterMem} trying to change membership ${targetMem} to ${newAccess}")
            Left(LibraryFail(FORBIDDEN, "invalid_permissions"))
          }
      }
    }
  }

  private def refreshLibraryCollaboratorsTypeahead(libraryId: Id[Library]): Future[Unit] = {
    val collaboratorIds = db.readOnlyMaster { implicit session =>
      libraryMembershipRepo.getCollaboratorsByLibrary(Set(libraryId)).get(libraryId).toSet.flatten
    }
    kifiUserTypeahead.refreshByIds(collaboratorIds.toSeq)
  }
}
