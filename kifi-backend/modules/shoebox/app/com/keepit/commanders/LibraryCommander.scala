package com.keepit.commanders

import com.google.inject.{ Inject, Provider }
import com.keepit.abook.ABookServiceClient
import com.keepit.abook.model.RichContact
import com.keepit.commanders.emails.{ EmailOptOutCommander, LibraryInviteEmailSender }
import com.keepit.common.akka.SafeFuture
import com.keepit.common.cache._
import com.keepit.common.core._
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick.Database
import com.keepit.common.db.{ SequenceNumber, State, ExternalId, Id }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail.{ BasicContact, ElectronicMail, EmailAddress }
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.store.{ ImageSize, S3ImageStore }
import com.keepit.common.time._
import com.keepit.common.util.Paginator
import com.keepit.eliza.{ LibraryPushNotificationCategory, UserPushNotificationCategory, PushNotificationExperiment, ElizaServiceClient }
import com.keepit.heimdal.{ HeimdalContext, HeimdalContextBuilderFactory, HeimdalServiceClient }
import com.keepit.model._
import com.keepit.search.SearchServiceClient
import com.keepit.social.{ BasicNonUser, BasicUser }
import com.keepit.common.concurrent.FutureHelpers
import com.kifi.macros.json
import org.joda.time.DateTime
import play.api.http.Status._
import play.api.libs.functional.syntax._
import play.api.libs.json._
import views.html.admin.{ libraries, library }

import scala.collection.parallel.ParSeq
import scala.concurrent._
import scala.util.Success

@json case class MarketingSuggestedLibrarySystemValue(
  id: Id[Library],
  caption: Option[String] = None)

object MarketingSuggestedLibrarySystemValue {
  // system value that persists the library IDs and additional library data for the marketing site
  def systemValueName = Name[SystemValue]("marketing_site_libraries")
}

class LibraryCommander @Inject() (
    db: Database,
    libraryRepo: LibraryRepo,
    libraryMembershipRepo: LibraryMembershipRepo,
    libraryAliasRepo: LibraryAliasRepo,
    libraryInviteRepo: LibraryInviteRepo,
    libraryInvitesAbuseMonitor: LibraryInvitesAbuseMonitor,
    libraryImageRepo: LibraryImageRepo,
    userRepo: UserRepo,
    userCommander: Provider[UserCommander],
    basicUserRepo: BasicUserRepo,
    keepRepo: KeepRepo,
    keepToCollectionRepo: KeepToCollectionRepo,
    keepDecorator: KeepDecorator,
    countByLibraryCache: CountByLibraryCache,
    typeaheadCommander: TypeaheadCommander,
    collectionRepo: CollectionRepo,
    s3ImageStore: S3ImageStore,
    emailOptOutCommander: EmailOptOutCommander,
    airbrake: AirbrakeNotifier,
    searchClient: SearchServiceClient,
    elizaClient: ElizaServiceClient,
    abookClient: ABookServiceClient,
    libraryAnalytics: LibraryAnalytics,
    libraryInviteSender: Provider[LibraryInviteEmailSender],
    heimdal: HeimdalServiceClient,
    contextBuilderFactory: HeimdalContextBuilderFactory,
    libraryImageCommander: LibraryImageCommander,
    experimentCommander: LocalUserExperimentCommander,
    userValueRepo: UserValueRepo,
    systemValueRepo: SystemValueRepo,
    twitterSyncRepo: TwitterSyncStateRepo,
    kifiInstallationCommander: KifiInstallationCommander,
    implicit val defaultContext: ExecutionContext,
    implicit val publicIdConfig: PublicIdConfiguration,
    clock: Clock) extends Logging {

  def getKeeps(libraryId: Id[Library], offset: Int, limit: Int): Future[Seq[Keep]] = {
    if (limit > 0) db.readOnlyReplicaAsync { implicit s => keepRepo.getByLibrary(libraryId, offset, limit) }
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
      val librariesById = libraryRepo.getLibraries(libraryIds.toSet) // cached
      val ownersById = basicUserRepo.loadAll(librariesById.values.map(_.ownerId).toSet) // cached
      libraryIds.map { libId =>
        val library = librariesById(libId)
        val owner = ownersById(library.ownerId)
        LibraryInfo.fromLibraryAndOwner(library, None, owner) // library images are not used, so no need to include
      }
    }
  }

  def getLibraryPath(library: Library): String = {
    val owner = db.readOnlyMaster { implicit session => userRepo.get(library.ownerId) }
    Library.formatLibraryPath(owner.username, library.slug)
  }

  // Replaced by getBasicLibraryDetails, please remove dependencies
  def getBasicLibraryStatistics(libraryIds: Set[Id[Library]]): Map[Id[Library], BasicLibraryStatistics] = {
    db.readOnlyReplica { implicit session =>
      val libs = libraryRepo.getLibraries(libraryIds)
      libraryIds.map { libId =>
        val lib = libs(libId)
        libId -> BasicLibraryStatistics(lib.memberCount, lib.keepCount)
      }.toMap
    }
  }

  def getBasicLibraryDetails(libraryIds: Set[Id[Library]]): Map[Id[Library], BasicLibraryDetails] = {
    db.readOnlyReplica { implicit session =>
      val libs = libraryRepo.getLibraries(libraryIds)
      libraryIds.map { libId =>
        val lib = libs(libId)
        val counts = libraryMembershipRepo.countWithLibraryIdByAccess(libId)
        val numFollowers = counts.readOnly
        val numCollaborators = counts.readWrite + counts.readInsert
        val imageOpt = libraryImageCommander.getBestImageForLibrary(libId, ProcessedImageSize.Medium.idealSize).map(libraryImageCommander.getUrl)
        libId -> BasicLibraryDetails(lib.name, lib.slug, lib.color, imageOpt, lib.description, numFollowers, numCollaborators, lib.keepCount)
      }.toMap
    }
  }

  def getLibrarySummaryAndMembership(userIdOpt: Option[Id[User]], libraryId: Id[Library]): (LibraryInfo, Option[LibraryMembershipInfo]) = {
    val Seq(libInfo) = getLibrarySummaries(Seq(libraryId))
    val imageOpt = libraryImageCommander.getBestImageForLibrary(libraryId, ProcessedImageSize.Medium.idealSize).map(LibraryImageInfo.createInfo)
    val memOpt = getViewerMembershipInfo(userIdOpt, libraryId)
    (libInfo.copy(image = imageOpt), memOpt)
  }

  def getLibraryWithOwnerAndCounts(libraryId: Id[Library], viewerUserId: Id[User]): Either[LibraryFail, (Library, BasicUser, Int, Option[Boolean], Boolean)] = {
    db.readOnlyReplica { implicit s =>
      val library = libraryRepo.get(libraryId)
      val mine = library.ownerId == viewerUserId
      val memOpt = libraryMembershipRepo.getWithLibraryIdAndUserId(libraryId, viewerUserId)
      val following = if (mine) None else Some(memOpt.isDefined)
      val subscribedToUpdates = memOpt.map(_.subscribedToUpdates).getOrElse(false)
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
      case LibraryKind.USER_CREATED | LibraryKind.SYSTEM_PERSONA =>
        val (collaborators, followers, _, counts) = getLibraryMembers(library.id.get, 0, maxMembersShown, fillInWithInvites = false)
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
    idealKeepImageSize: ImageSize, libraries: Seq[Library], idealLibraryImageSize: ImageSize, withKeepTime: Boolean): Future[Seq[(Id[Library], FullLibraryInfo)]] = {
    libraries.groupBy(l => l.id.get).foreach { case (lib, set) => if (set.size > 1) throw new Exception(s"There are ${set.size} identical libraries of $lib") }
    val futureKeepInfosByLibraryId = libraries.map { library =>
      library.id.get -> {
        if (maxKeepsShown > 0) {
          val keeps = db.readOnlyMaster { implicit session =>
            library.kind match {
              case LibraryKind.USER_CREATED | LibraryKind.SYSTEM_PERSONA => keepRepo.getByLibrary(library.id.get, 0, maxKeepsShown) //not cached
              case LibraryKind.SYSTEM_MAIN =>
                assume(library.ownerId == viewerUserIdOpt.get, s"viewer ${viewerUserIdOpt.get} can't view a system library they do not own: $library")
                if (experimentCommander.userHasExperiment(library.ownerId, ExperimentType.ALL_KEEPS_VIEW)) { //cached
                  keepRepo.getNonPrivate(library.ownerId, 0, maxKeepsShown) //not cached
                } else keepRepo.getByLibrary(library.id.get, 0, maxKeepsShown)
              case LibraryKind.SYSTEM_SECRET =>
                assume(library.ownerId == viewerUserIdOpt.get, s"viewer ${viewerUserIdOpt.get} can't view a system library they do not own: $library")
                if (experimentCommander.userHasExperiment(library.ownerId, ExperimentType.ALL_KEEPS_VIEW)) { //cached
                  keepRepo.getPrivate(library.ownerId, 0, maxKeepsShown) //not cached
                } else keepRepo.getByLibrary(library.id.get, 0, maxKeepsShown) //not cached
            }

          }
          keepDecorator.decorateKeepsIntoKeepInfos(viewerUserIdOpt, showPublishedLibraries, keeps, idealKeepImageSize, withKeepTime)
        } else Future.successful(Seq.empty)
      }
    }.toMap

    val memberInfosByLibraryId = countMemberInfosByLibraryId(libraries, maxMembersShown, viewerUserIdOpt)

    val usersByIdF = {
      val allUsersShown = libraries.flatMap { library => memberInfosByLibraryId(library.id.get).all :+ library.ownerId }.toSet
      db.readOnlyMasterAsync { implicit s => basicUserRepo.loadAll(allUsersShown) } //cached
    }

    val futureCountsByLibraryId = {
      val keepCountsByLibraries: Map[Id[Library], Int] = db.readOnlyMaster { implicit s =>
        val userLibs = libraries.filter { lib => lib.kind == LibraryKind.USER_CREATED || lib.kind == LibraryKind.SYSTEM_PERSONA }.map(_.id.get).toSet
        var userLibCounts: Map[Id[Library], Int] = libraries.map(lib => lib.id.get -> lib.keepCount).toMap
        if (userLibs.size < libraries.size) {
          val privateLibOpt = libraries.find(_.kind == LibraryKind.SYSTEM_SECRET)
          val mainLibOpt = libraries.find(_.kind == LibraryKind.SYSTEM_MAIN)
          val owner = privateLibOpt.map(_.ownerId).orElse(mainLibOpt.map(_.ownerId)).getOrElse(
            throw new Exception(s"no main or secret libs in ${libraries.size} libs while userLibs counts for $userLibs is $userLibCounts. Libs are ${libraries.mkString("\n")}"))
          if (experimentCommander.userHasExperiment(owner, ExperimentType.ALL_KEEPS_VIEW)) {
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
        libImageOpt <- imagesF(libId)
      } yield {
        val (collaboratorCount, followerCount, keepCount) = counts
        val owner = usersById(lib.ownerId)
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
          url = Library.formatLibraryPath(owner.username, lib.slug),
          color = lib.color,
          kind = lib.kind,
          visibility = lib.visibility,
          image = libImageOpt.map(LibraryImageInfo.createInfo),
          followers = followers,
          collaborators = collaborators,
          keeps = keepInfos,
          numKeeps = keepCount,
          numCollaborators = collaboratorCount,
          numFollowers = followerCount,
          lastKept = lib.lastKept,
          attr = attr,
          whoCanInvite = whoCanInvite,
          modifiedAt = lib.updatedAt
        )
      }
    }
    Future.sequence(futureFullLibraryInfos)
  }

  def getViewerMembershipInfo(userIdOpt: Option[Id[User]], libraryId: Id[Library]): Option[LibraryMembershipInfo] = {
    userIdOpt.map { userId =>
      db.readOnlyMaster { implicit s =>
        libraryMembershipRepo.getWithLibraryIdAndUserId(libraryId, userId)
      } map (LibraryMembershipInfo.fromMembership(_))
    }.flatten
  }

  def getViewerInviteInfo(userIdOpt: Option[Id[User]], libraryId: Id[Library]): Option[LibraryInviteInfo] = {
    userIdOpt.flatMap { userId =>
      db.readOnlyMaster { implicit s =>
        val inviteOpt = libraryInviteRepo.getLastSentByLibraryIdAndUserId(libraryId, userId, Set(LibraryInviteStates.ACTIVE))
        val basicUserOpt = inviteOpt map { inv => basicUserRepo.load(inv.inviterId) }
        (inviteOpt, basicUserOpt)
      } match {
        case (Some(invite), Some(inviter)) => Some(LibraryInviteInfo.createInfo(invite, inviter))
        case (_, _) => None
      }
    }
  }

  private def getSourceAttribution(libId: Id[Library]): Option[LibrarySourceAttribution] = {
    db.readOnlyReplica { implicit s =>
      twitterSyncRepo.getFirstHandleByLibraryId(libId).map { TwitterLibrarySourceAttribution(_) }
    }
  }

  def sortUsersByImage(users: Seq[BasicUser]): Seq[BasicUser] =
    users.sortBy(_.pictureName == BasicNonUser.DefaultPictureName)

  def createFullLibraryInfo(viewerUserIdOpt: Option[Id[User]], showPublishedLibraries: Boolean, library: Library, libImageSize: ImageSize, showKeepCreateTime: Boolean = true): Future[FullLibraryInfo] = {
    val maxMembersShown = 10
    createFullLibraryInfos(viewerUserIdOpt, showPublishedLibraries, maxMembersShown = maxMembersShown * 2, maxKeepsShown = 10, ProcessedImageSize.Large.idealSize, Seq(library), libImageSize, showKeepCreateTime).imap {
      case Seq((_, info)) =>
        val followers = info.followers
        val sortedFollowers = sortUsersByImage(followers)
        info.copy(followers = sortedFollowers.take(maxMembersShown))
    }
  }

  def getLibraryMembers(libraryId: Id[Library], offset: Int, limit: Int, fillInWithInvites: Boolean): (Seq[LibraryMembership], Seq[LibraryMembership], Seq[(Either[Id[User], EmailAddress], Set[LibraryInvite])], CountWithLibraryIdByAccess) = {
    val collaboratorsAccess: Set[LibraryAccess] = Set(LibraryAccess.READ_INSERT, LibraryAccess.READ_WRITE)
    val followersAccess: Set[LibraryAccess] = Set(LibraryAccess.READ_ONLY)
    val relevantInviteStates = Set(LibraryInviteStates.ACTIVE)

    val memberCount = db.readOnlyMaster { implicit s =>
      libraryMembershipRepo.countWithLibraryIdByAccess(libraryId)
    }

    if (limit > 0) db.readOnlyMaster { implicit session =>
      // Get Collaborators
      val collaborators = libraryMembershipRepo.pageWithLibraryIdAndAccess(libraryId, offset, limit, collaboratorsAccess) //not cached
      val collaboratorsShown = collaborators.length

      val numCollaborators = memberCount.readInsert + memberCount.readWrite
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

  def buildMaybeLibraryMembers(collaborators: Seq[LibraryMembership], followers: Seq[LibraryMembership], inviteesWithInvites: Seq[(Either[Id[User], EmailAddress], Set[LibraryInvite])]): Seq[MaybeLibraryMember] = {

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
        db.readOnlyReplica { implicit s => libraryRepo.getByNameOrSlug(ownerId, libAddReq.name, validSlug) } match {
          case Some(lib) if lib.name == libAddReq.name =>
            Left(LibraryFail(BAD_REQUEST, "library_name_exists"))
          case Some(lib) if lib.slug == validSlug =>
            Left(LibraryFail(BAD_REQUEST, "library_slug_exists"))
          case None =>
            val newColor = libAddReq.color.orElse(Some(LibraryColor.pickRandomLibraryColor))
            val newListed = libAddReq.listed.getOrElse(true)
            val newKind = libAddReq.kind.getOrElse(LibraryKind.USER_CREATED)
            val newInviteToCollab = libAddReq.whoCanInvite.orElse(Some(LibraryInvitePermissions.COLLABORATOR))
            val library = db.readWrite { implicit s =>
              libraryAliasRepo.reclaim(ownerId, validSlug)
              libraryRepo.getOpt(ownerId, validSlug) match {
                case None =>
                  val lib = libraryRepo.save(Library(ownerId = ownerId, name = libAddReq.name, description = libAddReq.description,
                    visibility = libAddReq.visibility, slug = validSlug, color = newColor, kind = newKind, memberCount = 1, keepCount = 0, whoCanInvite = newInviteToCollab))
                  libraryMembershipRepo.save(LibraryMembership(libraryId = lib.id.get, userId = ownerId, access = LibraryAccess.OWNER, listed = newListed, lastJoinedAt = Some(currentDateTime)))
                  lib
                case Some(lib) =>
                  val newLib = libraryRepo.save(lib.copy(state = LibraryStates.ACTIVE))
                  libraryMembershipRepo.getWithLibraryIdAndUserId(libraryId = lib.id.get, userId = ownerId, None) match {
                    case None => libraryMembershipRepo.save(LibraryMembership(libraryId = lib.id.get, userId = ownerId, access = LibraryAccess.OWNER))
                    case Some(mem) => libraryMembershipRepo.save(mem.copy(state = LibraryMembershipStates.ACTIVE, listed = newListed))
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

  def canModifyLibrary(libraryId: Id[Library], userId: Id[User]): Boolean = {
    db.readOnlyReplica { implicit s => libraryMembershipRepo.getWithLibraryIdAndUserId(libraryId, userId) } exists { membership => //not cached!
      membership.canWrite
    }
  }

  def getLibrariesWithWriteAccess(userId: Id[User]): Set[Id[Library]] = {
    db.readOnlyMaster { implicit session => libraryMembershipRepo.getLibrariesWithWriteAccess(userId) }
  }

  def modifyLibrary(libraryId: Id[Library], userId: Id[User], modifyReq: LibraryModifyRequest)(implicit context: HeimdalContext): Either[LibraryFail, Library] = {
    val (targetLib, targetMembershipOpt) = db.readOnlyMaster { implicit s =>
      val lib = libraryRepo.get(libraryId)
      val mem = libraryMembershipRepo.getWithLibraryIdAndUserId(libraryId, userId)
      (lib, mem)
    }
    if (targetMembershipOpt.isEmpty || !targetMembershipOpt.get.canWrite) {
      Left(LibraryFail(FORBIDDEN, "permission_denied"))
    } else {
      val targetMembership = targetMembershipOpt.get

      def validName(newNameOpt: Option[String]): Either[LibraryFail, String] = {
        newNameOpt match {
          case None => Right(targetLib.name)
          case Some(name) =>
            if (!Library.isValidName(name)) {
              Left(LibraryFail(BAD_REQUEST, "invalid_name"))
            } else {
              db.readOnlyMaster { implicit s =>
                libraryRepo.getByNameAndUserId(userId, name)
              } match {
                case Some(other) if other.id.get != libraryId => Left(LibraryFail(BAD_REQUEST, "library_name_exists"))
                case _ => Right(name)
              }
            }
        }
      }
      def validSlug(newSlugOpt: Option[String]): Either[LibraryFail, LibrarySlug] = {
        newSlugOpt match {
          case None => Right(targetLib.slug)
          case Some(slugStr) =>
            if (!LibrarySlug.isValidSlug(slugStr)) {
              Left(LibraryFail(BAD_REQUEST, "invalid_slug"))
            } else if (LibrarySlug.isReservedSlug(slugStr)) {
              Left(LibraryFail(BAD_REQUEST, "reserved_slug"))
            } else {
              val slug = LibrarySlug(slugStr)
              db.readOnlyMaster { implicit s =>
                libraryRepo.getBySlugAndUserId(userId, slug)
              } match {
                case Some(other) if other.id.get != libraryId => Left(LibraryFail(BAD_REQUEST, "library_slug_exists"))
                case _ => Right(slug)
              }
            }
        }
      }

      val result = for {
        newName <- validName(modifyReq.name).right
        newSlug <- validSlug(modifyReq.slug).right
      } yield {
        val newDescription = modifyReq.description.orElse(targetLib.description)
        val newVisibility = modifyReq.visibility.getOrElse(targetLib.visibility)
        val newColor = modifyReq.color.orElse(targetLib.color)
        val newListed = modifyReq.listed.getOrElse(targetMembership.listed)
        val newInviteToCollab = modifyReq.whoCanInvite.orElse(targetLib.whoCanInvite)
        Future {
          val keeps = db.readOnlyMaster { implicit s =>
            keepRepo.getByLibrary(libraryId, 0, Int.MaxValue, Set.empty)
          }
          if (keeps.nonEmpty) {
            db.readWriteBatch(keeps) { (s, k) =>
              keepRepo.save(k.copy(visibility = newVisibility))(s)
            }
            searchClient.updateKeepIndex()
          }
        }
        val lib = db.readWrite { implicit s =>
          if (targetLib.slug != newSlug) {
            val ownerId = targetLib.ownerId
            libraryAliasRepo.reclaim(ownerId, newSlug)
            libraryAliasRepo.alias(ownerId, targetLib.slug, targetLib.id.get)
          }
          if (targetMembership.listed != newListed) {
            libraryMembershipRepo.save(targetMembership.copy(listed = newListed))
          }
          libraryRepo.save(targetLib.copy(name = newName, slug = newSlug, visibility = newVisibility, description = newDescription, color = newColor, whoCanInvite = newInviteToCollab, state = LibraryStates.ACTIVE))
        }

        val edits = Map(
          "title" -> (newName != targetLib.name),
          "slug" -> (newSlug != targetLib.slug),
          "description" -> (newDescription != targetLib.description),
          "color" -> (newColor != targetLib.color),
          "madePrivate" -> (newVisibility != targetLib.visibility && newVisibility == LibraryVisibility.SECRET),
          "listed" -> (newListed != targetMembership.listed),
          "inviteToCollab" -> (newInviteToCollab != targetLib.whoCanInvite)
        )
        (lib, edits)
      }
      Future {
        if (result.isRight) {
          val editedLibrary = result.right.get._1
          val edits = result.right.get._2
          libraryAnalytics.editLibrary(userId, editedLibrary, context, None, edits)
        }
        searchClient.updateLibraryIndex()
      }
      result match {
        case Right((lib, _)) => Right(lib)
        case Left(error) => Left(error)
      }
    }
  }

  def removeLibrary(libraryId: Id[Library], userId: Id[User])(implicit context: HeimdalContext): Option[LibraryFail] = {
    val oldLibrary = db.readOnlyMaster { implicit s => libraryRepo.get(libraryId) }
    if (oldLibrary.ownerId != userId) {
      Some(LibraryFail(FORBIDDEN, "permission_denied"))
    } else if (oldLibrary.kind == LibraryKind.SYSTEM_MAIN || oldLibrary.kind == LibraryKind.SYSTEM_SECRET) {
      Some(LibraryFail(BAD_REQUEST, "cant_delete_system_generated_library"))
    } else {
      val keepsInLibrary = db.readWrite { implicit s =>
        libraryMembershipRepo.getWithLibraryId(oldLibrary.id.get).map { m =>
          libraryMembershipRepo.save(m.withState(LibraryMembershipStates.INACTIVE))
        }
        libraryInviteRepo.getWithLibraryId(oldLibrary.id.get).map { inv =>
          libraryInviteRepo.save(inv.withState(LibraryInviteStates.INACTIVE))
        }
        keepRepo.getByLibrary(oldLibrary.id.get, 0, Int.MaxValue)
      }
      val savedKeeps = db.readWriteBatch(keepsInLibrary) { (s, keep) =>
        keepRepo.save(keep.sanitizeForDelete())(s)
      }
      libraryAnalytics.deleteLibrary(userId, oldLibrary, context)
      libraryAnalytics.unkeptPages(userId, savedKeeps.keySet.toSeq, oldLibrary, context)
      searchClient.updateKeepIndex()
      //Note that this is at the end, if there was an error while cleaning other library assets
      //we would want to be able to get back to the library and clean it again
      db.readWrite { implicit s =>
        libraryRepo.save(oldLibrary.sanitizeForDelete())
      }
      searchClient.updateLibraryIndex()
      None
    }
  }

  private def getValidLibInvitesFromAuthToken(libraryId: Id[Library], authToken: Option[String])(implicit s: RSession): Seq[LibraryInvite] = {
    if (authToken.nonEmpty) {
      libraryInviteRepo.getByLibraryIdAndAuthToken(libraryId, authToken.get)
    } else {
      Seq.empty[LibraryInvite]
    }
  }

  def canViewLibrary(userId: Option[Id[User]], library: Library, authToken: Option[String] = None): Boolean = {
    library.visibility == LibraryVisibility.PUBLISHED || // published library
      db.readOnlyMaster { implicit s =>
        userId match {
          case Some(id) =>
            libraryMembershipRepo.getWithLibraryIdAndUserId(library.id.get, id).nonEmpty ||
              libraryInviteRepo.getWithLibraryIdAndUserId(userId = id, libraryId = library.id.get).nonEmpty ||
              getValidLibInvitesFromAuthToken(library.id.get, authToken).nonEmpty
          case None =>
            getValidLibInvitesFromAuthToken(library.id.get, authToken).nonEmpty
        }
      }
  }

  def canViewLibrary(userId: Option[Id[User]], libraryId: Id[Library], accessToken: Option[String]): Boolean = {
    val library = db.readOnlyReplica { implicit session =>
      libraryRepo.get(libraryId)
    }
    library.state == LibraryStates.ACTIVE && canViewLibrary(userId, library, accessToken)
  }

  def getLibrariesByUser(userId: Id[User]): (Seq[(LibraryMembership, Library)], Seq[(LibraryInvite, Library)]) = {
    db.readOnlyMaster { implicit s =>
      val myLibraries = libraryRepo.getByUser(userId)
      val myInvites = libraryInviteRepo.getByUser(userId, Set(LibraryInviteStates.ACCEPTED, LibraryInviteStates.INACTIVE, LibraryInviteStates.DECLINED))
      (myLibraries, myInvites)
    }
  }

  def getLibrariesUserCanKeepTo(userId: Id[User]): Seq[(Library, Boolean, Boolean)] = {
    db.readOnlyMaster { implicit s =>
      libraryRepo.getByUserWithCollab(userId, excludeAccess = Some(LibraryAccess.READ_ONLY)).map(r => (r._2, r._3, r._1.subscribedToUpdates))
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

  def processInvites(invites: Seq[LibraryInvite]): Future[Seq[ElectronicMail]] = {
    val emailFutures = {
      invites.groupBy(invite => (invite.inviterId, invite.libraryId, invite.userId, invite.emailAddress))
        .map { key =>
          val (inviterId, libId, recipientId, recipientEmail) = key._1

          val (inviter, lib, libOwner, lastInviteOpt) = db.readOnlyMaster { implicit s =>
            val inviter = userRepo.get(inviterId)
            val lib = libraryRepo.get(libId)
            val libOwner = basicUserRepo.load(lib.ownerId)
            val lastInviteOpt = (recipientId, recipientEmail) match {
              case (Some(userId), _) =>
                libraryInviteRepo.getLastSentByLibraryIdAndInviterIdAndUserId(libId, inviterId, userId, Set(LibraryInviteStates.ACTIVE))
              case (_, Some(email)) =>
                libraryInviteRepo.getLastSentByLibraryIdAndInviterIdAndEmail(libId, inviterId, email, Set(LibraryInviteStates.ACTIVE))
              case _ => None
            }
            (inviter, lib, libOwner, lastInviteOpt)
          }
          val invitesToPersist = key._2.filter { invite =>
            lastInviteOpt.map { lastInvite =>
              lastInvite.access != invite.access || lastInvite.createdAt.plusMinutes(5).isBefore(invite.createdAt)
            }.getOrElse(true)
          }

          db.readWrite { implicit s =>
            invitesToPersist.map { inv =>
              libraryInviteRepo.save(inv)
            }
          }

          val userImage = s3ImageStore.avatarUrlByUser(inviter)
          val libLink = s"""https://www.kifi.com${Library.formatLibraryPath(libOwner.username, lib.slug)}"""
          val libImageOpt = libraryImageCommander.getBestImageForLibrary(libId, ProcessedImageSize.Medium.idealSize)

          val userInviteesMap = invitesToPersist.collect {
            case invite if invite.userId.isDefined =>
              invite.userId.get -> invite
          }.toMap

          // send notifications to kifi users only
          if (userInviteesMap.nonEmpty) {
            notifyInviteeAboutInvitationToJoinLIbrary(inviter, lib, libOwner, userImage, libLink, libImageOpt, userInviteesMap)
            if (inviterId != lib.ownerId) {
              notifyLibOwnerAboutInvitationToTheirLibrary(inviter, lib, libOwner, userImage, libImageOpt, userInviteesMap)
            }
          }
          // send emails to both users & non-users
          invitesToPersist.map { invite =>
            invite.userId match {
              case Some(id) =>
                libraryInvitesAbuseMonitor.inspect(inviterId, Some(id), None, libId, invitesToPersist.length)
              case _ =>
                libraryInvitesAbuseMonitor.inspect(inviterId, None, invite.emailAddress, libId, invitesToPersist.length)
            }
            libraryInviteSender.get.sendInvite(invite)
          }
        }.toSeq.flatten
    }
    val emailsF = Future.sequence(emailFutures)
    emailsF map (_.filter(_.isDefined).map(_.get))
  }

  def notifyLibOwnerAboutInvitationToTheirLibrary(inviter: User, lib: Library, libOwner: BasicUser, userImage: String, libImageOpt: Option[LibraryImage], inviteeMap: Map[Id[User], LibraryInvite]): Unit = {
    val (collabInvitees, followInvitees) = inviteeMap.partition { case (userId, invite) => invite.isCollaborator }
    val collabInviteeSet = collabInvitees.keySet
    val followInviteeSet = followInvitees.keySet
    val collabFriendStr = if (collabInviteeSet.size > 1) "friends" else "a friend"
    val followFriendStr = if (followInviteeSet.size > 1) "friends" else "a friend"

    val collabInvitesF = if (collabInviteeSet.size > 0) {
      elizaClient.sendGlobalNotification( //push sent
        userIds = Set(lib.ownerId),
        title = s"${inviter.firstName} invited someone to contribute to your Library!",
        body = s"${inviter.fullName} invited $collabFriendStr to contribute to your library, ${lib.name}.",
        linkText = s"See ${inviter.firstName}’s profile",
        linkUrl = s"https://www.kifi.com/${inviter.username.value}",
        imageUrl = userImage,
        sticky = false,
        category = NotificationCategory.User.LIBRARY_FOLLOWED,
        extra = Some(Json.obj(
          "inviter" -> inviter,
          "library" -> Json.toJson(LibraryNotificationInfo.fromLibraryAndOwner(lib, libImageOpt, libOwner))
        ))
      )
    } else { Future.successful((): Unit) }

    val followInvitesF = if (followInviteeSet.size > 0) {
      elizaClient.sendGlobalNotification( //push sent
        userIds = Set(lib.ownerId),
        title = s"${inviter.firstName} invited someone to follow your Library!",
        body = s"${inviter.fullName} invited $followFriendStr to follow your library, ${lib.name}.",
        linkText = s"See ${inviter.firstName}’s profile",
        linkUrl = s"https://www.kifi.com/${inviter.username.value}",
        imageUrl = userImage,
        sticky = false,
        category = NotificationCategory.User.LIBRARY_FOLLOWED,
        extra = Some(Json.obj(
          "inviter" -> inviter,
          "library" -> Json.toJson(LibraryNotificationInfo.fromLibraryAndOwner(lib, libImageOpt, libOwner))
        ))
      )
    } else { Future.successful((): Unit) }

    for {
      collabInvites <- collabInvitesF
      followInvites <- followInvitesF
    } yield {
      val canSendPush = kifiInstallationCommander.isMobileVersionEqualOrGreaterThen(lib.ownerId, KifiAndroidVersion("2.2.4"), KifiIPhoneVersion("2.1.0"))
      if (canSendPush) {
        if (collabInviteeSet.size > 0) {
          elizaClient.sendUserPushNotification(
            userId = lib.ownerId,
            message = s"${inviter.firstName} invited $collabFriendStr to contribute to your library ${lib.name}!",
            recipient = inviter,
            pushNotificationExperiment = PushNotificationExperiment.Experiment1,
            category = UserPushNotificationCategory.NewLibraryFollower)
        }
        if (followInviteeSet.size > 0) {
          elizaClient.sendUserPushNotification(
            userId = lib.ownerId,
            message = s"${inviter.firstName} invited $followFriendStr to follow your library ${lib.name}!",
            recipient = inviter,
            pushNotificationExperiment = PushNotificationExperiment.Experiment1,
            category = UserPushNotificationCategory.NewLibraryFollower)
        }
      }
    }
  }

  def notifyInviteeAboutInvitationToJoinLIbrary(inviter: User, lib: Library, libOwner: BasicUser, userImage: String, libLink: String, libImageOpt: Option[LibraryImage], inviteeMap: Map[Id[User], LibraryInvite]): Unit = {
    val (collabInvitees, followInvitees) = inviteeMap.partition { case (userId, invite) => invite.isCollaborator }
    val collabInviteeSet = collabInvitees.keySet
    val followInviteeSet = followInvitees.keySet

    val collabInvitesF = elizaClient.sendGlobalNotification( //push sent
      userIds = collabInviteeSet,
      title = s"${inviter.firstName} ${inviter.lastName} invited you to collaborate on a Library!",
      body = s"Help ${libOwner.firstName} by sharing your knowledge in the library ${lib.name}.",
      linkText = "Let's do it!",
      linkUrl = libLink,
      imageUrl = userImage,
      sticky = false,
      category = NotificationCategory.User.LIBRARY_INVITATION,
      extra = Some(Json.obj(
        "inviter" -> inviter,
        "library" -> Json.toJson(LibraryNotificationInfo.fromLibraryAndOwner(lib, libImageOpt, libOwner))
      ))
    )

    val followInvitesF = elizaClient.sendGlobalNotification( //push sent
      userIds = followInviteeSet,
      title = s"${inviter.firstName} ${inviter.lastName} invited you to follow a Library!",
      body = s"Browse keeps in ${lib.name} to find some interesting gems kept by ${libOwner.firstName}.",
      linkText = "Let's take a look!",
      linkUrl = libLink,
      imageUrl = userImage,
      sticky = false,
      category = NotificationCategory.User.LIBRARY_INVITATION,
      extra = Some(Json.obj(
        "inviter" -> inviter,
        "library" -> Json.toJson(LibraryNotificationInfo.fromLibraryAndOwner(lib, libImageOpt, libOwner))
      ))
    )

    for {
      collabInvites <- collabInvitesF
      followInvites <- followInvitesF
    } yield {
      collabInviteeSet.foreach { userId =>
        val canSendPush = kifiInstallationCommander.isMobileVersionEqualOrGreaterThen(userId, KifiAndroidVersion("2.2.4"), KifiIPhoneVersion("2.1.0"))
        if (canSendPush) {
          elizaClient.sendLibraryPushNotification(
            userId,
            s"""${inviter.firstName} ${inviter.lastName} invited you to contribute to: ${lib.name}""",
            lib.id.get,
            libLink,
            PushNotificationExperiment.Experiment1,
            LibraryPushNotificationCategory.LibraryInvitation)
        }
      }

      followInviteeSet.foreach { userId =>
        val canSendPush = kifiInstallationCommander.isMobileVersionEqualOrGreaterThen(userId, KifiAndroidVersion("2.2.4"), KifiIPhoneVersion("2.1.0"))
        if (canSendPush) {
          elizaClient.sendLibraryPushNotification(
            userId,
            s"""${inviter.firstName} ${inviter.lastName} invited you to follow: ${lib.name}""",
            lib.id.get,
            libLink,
            PushNotificationExperiment.Experiment1,
            LibraryPushNotificationCategory.LibraryInvitation)
        }
      }
    }
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
        .groupBy(_._2.kind)
        .map {
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
            } else { // do not reactivate libraries / memberships for nonactive users
              libs
            }
        }.flatten.toList // force eval

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

  def revokeInvitationToLibrary(libraryId: Id[Library], inviterId: Id[User], invitee: Either[ExternalId[User], EmailAddress]): Either[(String, String), String] = {
    val libraryInvite = invitee match {
      case Left(externalId) => db.readOnlyMaster { implicit s =>
        userRepo.getOpt(externalId) match {
          case Some(userId) => Right(libraryInviteRepo.getLastSentByLibraryIdAndInviterIdAndUserId(libraryId, inviterId, userId.id.get, Set(LibraryInviteStates.ACTIVE)))
          case None => Left(s"external_id_does_not_exist")
        }
      }
      case Right(email) => db.readOnlyMaster { implicit s =>
        Right(libraryInviteRepo.getLastSentByLibraryIdAndInviterIdAndEmail(libraryId, inviterId, email, Set(LibraryInviteStates.ACTIVE)))
      }
    }
    libraryInvite match {
      case Right(Some(toDelete)) => db.readWrite(attempts = 3) { implicit s =>
        libraryInviteRepo.save(toDelete.copy(state = LibraryInviteStates.INACTIVE)) match {
          case invite if invite.state == LibraryInviteStates.INACTIVE => Right("library_delete_succeeded")
          case _ => Left("error" -> "library_invite_delete_failed")
        }
      }
      case Right(None) => Left("error" -> "library_invite_not_found")
      case Left(error) => Left("error" -> error)
    }
  }

  def inviteUsersToLibrary(libraryId: Id[Library], inviterId: Id[User], inviteList: Seq[(Either[Id[User], EmailAddress], LibraryAccess, Option[String])])(implicit eventContext: HeimdalContext): Future[Either[LibraryFail, Seq[(Either[BasicUser, RichContact], LibraryAccess)]]] = {
    val (lib, inviterMembership) = db.readOnlyMaster { implicit s =>
      val lib = libraryRepo.get(libraryId)
      val mem = libraryMembershipRepo.getWithLibraryIdAndUserId(libraryId, inviterId)
      (lib, mem)
    }
    val inviterIsOwner = inviterMembership.map(_.isOwner).getOrElse(false)
    val inviterIsCollab = inviterMembership.map(_.isCollaborator).getOrElse(false)
    val collabCannotInvite = lib.whoCanInvite == Some(LibraryInvitePermissions.OWNER)

    if (lib.kind == LibraryKind.SYSTEM_MAIN || lib.kind == LibraryKind.SYSTEM_SECRET)
      Future.successful(Left(LibraryFail(BAD_REQUEST, "cant_invite_to_system_generated_library")))
    else {
      // get all invitee contacts by email address
      val futureInviteeContactsByEmailAddress = {
        val invitedEmailAddresses = inviteList.collect { case (Right(emailAddress), _, _) => emailAddress }
        abookClient.internKifiContacts(inviterId, invitedEmailAddresses.map(BasicContact(_)): _*).imap { kifiContacts =>
          (invitedEmailAddresses zip kifiContacts).toMap
        }
      }

      // get all invitee users (mapped userId -> basicuser)
      val inviteeUserMap = {
        val invitedUserIds = inviteList.collect { case (Left(userId), _, _) => userId }
        db.readOnlyMaster { implicit s =>
          basicUserRepo.loadAll(invitedUserIds.toSet)
        }
      }

      futureInviteeContactsByEmailAddress.map { inviteeContactsByEmailAddress => // when email contacts are done fetching... process through inviteList
        val invitesForInvitees = db.readOnlyMaster { implicit s =>
          val libMembersMap = libraryMembershipRepo.getWithLibraryId(lib.id.get).map { mem =>
            mem.userId -> mem
          }.toMap

          for ((recipient, inviteAccess, msgOpt) <- inviteList) yield {
            improperInvite(lib, inviterMembership, inviteAccess) match {
              case Some(fail) =>
                log.warn(s"[inviteUsersToLibrary] error: user $inviterId attempting to invite $recipient for $inviteAccess access to library (${lib.id.get}, ${lib.name}, ${lib.visibility}, ${lib.whoCanInvite})")
                None
              case _ =>
                recipient match {
                  case Left(userId) =>
                    libMembersMap.get(userId) match {
                      case Some(mem) if mem.isOwner || mem.isCollaborator => // don't persist invite to an owner or collaborator
                        log.warn(s"[inviteUsersToLibrary] not persisting invite: user $inviterId attempting to invite user $userId when recipient is already owner or collaborator")
                        None
                      case Some(mem) if mem.access == inviteAccess => // don't persist invite to a user with same access
                        log.warn(s"[inviteUsersToLibrary] not persisting invite: user $inviterId attempting to invite user $userId for $inviteAccess access when membership has same access level")
                        None
                      case _ =>
                        val newInvite = LibraryInvite(libraryId = libraryId, inviterId = inviterId, userId = Some(userId), access = inviteAccess, message = msgOpt)
                        val inviteeInfo = (Left(inviteeUserMap(userId)), inviteAccess)
                        Some(newInvite, inviteeInfo)
                    }

                  case Right(email) =>
                    val newInvite = LibraryInvite(libraryId = libraryId, inviterId = inviterId, emailAddress = Some(email), access = inviteAccess, message = msgOpt)
                    val inviteeInfo = (Right(inviteeContactsByEmailAddress(email)), inviteAccess)
                    Some(newInvite, inviteeInfo)
                }
            }
          }
        }
        val (invites, inviteesWithAccess) = invitesForInvitees.flatten.unzip
        processInvites(invites)
        libraryAnalytics.sendLibraryInvite(inviterId, lib, inviteList.map { _._1 }, eventContext)
        Right(inviteesWithAccess)
      }
    }
  }

  def inviteAnonymousToLibrary(libraryId: Id[Library], inviterId: Id[User], access: LibraryAccess, message: Option[String])(implicit context: HeimdalContext): Either[LibraryFail, (LibraryInvite, Library)] = {
    val (library, inviterMembershipOpt) = db.readOnlyMaster { implicit s =>
      val library = libraryRepo.get(libraryId)
      val membership = libraryMembershipRepo.getWithLibraryIdAndUserId(libraryId, inviterId)
      (library, membership)
    }

    val badInvite = improperInvite(library, inviterMembershipOpt, access)
    if (library.kind == LibraryKind.SYSTEM_MAIN || library.kind == LibraryKind.SYSTEM_SECRET) {
      Left(LibraryFail(BAD_REQUEST, "cant_invite_to_system_generated_library"))
    } else if (badInvite.isDefined) {
      log.warn(s"[inviteAnonymousToLibrary] error: user $inviterId attempting to generate link invite for $access access to library (${library.id.get}, ${library.name}, ${library.visibility}, ${library.whoCanInvite})")
      Left(badInvite.get)
    } else {
      val libInvite = db.readWrite { implicit s =>
        libraryInviteRepo.save(LibraryInvite(libraryId = libraryId, inviterId = inviterId, userId = None, emailAddress = None, access = access, message = message))
      }
      Right((libInvite, library))
    }
  }

  private def improperInvite(library: Library, inviterMembership: Option[LibraryMembership], access: LibraryAccess): Option[LibraryFail] = {
    val inviterIsOwner = inviterMembership.map(_.isOwner).getOrElse(false)
    val inviterIsCollab = inviterMembership.map(_.isCollaborator).getOrElse(false)
    val collabCannotInvite = library.whoCanInvite == Some(LibraryInvitePermissions.OWNER)
    if (access == LibraryAccess.READ_WRITE && !inviterIsOwner && !inviterIsCollab) { // invite to RW, but inviter is not owner or collaborator
      Some(LibraryFail(BAD_REQUEST, "cant_invite_rw_nonowner_noncollab"))
    } else if (access == LibraryAccess.READ_WRITE && inviterIsCollab && collabCannotInvite) { // invite to RW, but inviter is collaborator but library does not allow
      Some(LibraryFail(BAD_REQUEST, "cant_invite_rw_noncollablib"))
    } else if (access == LibraryAccess.READ_ONLY && library.isSecret && !inviterIsOwner && !inviterIsCollab) { // invite is RO, but library is secret & inviter is not owner or collaborator
      Some(LibraryFail(BAD_REQUEST, "cant_invite_ro_secretlib__nonowner_noncollab"))
    } else if (access == LibraryAccess.READ_ONLY && library.isSecret && inviterIsCollab && collabCannotInvite) { // invite is RO, but library is secret & inviter is collaborator but library does not allow
      Some(LibraryFail(BAD_REQUEST, "cant_invite_ro_secretlib_noncollablib"))
    } else {
      None
    }
  }

  private def notifyOwnerOfNewFollower(newFollowerId: Id[User], lib: Library): Unit = SafeFuture {
    val (follower, owner, lotsOfFollowers) = db.readOnlyReplica { implicit session =>
      val follower = userRepo.get(newFollowerId)
      val owner = basicUserRepo.load(lib.ownerId)
      val lotsOfFollowers = libraryMembershipRepo.countMembersForLibrarySince(lib.id.get, DateTime.now().minusDays(1)) > 2
      (follower, owner, lotsOfFollowers)
    }
    val message = s"${follower.firstName} ${follower.lastName} is now following your Library ${lib.name}"
    val libImageOpt = libraryImageCommander.getBestImageForLibrary(lib.id.get, ProcessedImageSize.Medium.idealSize)
    elizaClient.sendGlobalNotification( //push sent
      userIds = Set(lib.ownerId),
      title = "New Library Follower",
      body = message,
      linkText = s"See ${follower.firstName}’s profile",
      linkUrl = s"https://www.kifi.com/${follower.username.value}",
      imageUrl = s3ImageStore.avatarUrlByUser(follower),
      sticky = false,
      category = NotificationCategory.User.LIBRARY_FOLLOWED,
      unread = !lotsOfFollowers, // if not a lot of recent followers, notification is marked unread
      extra = Some(Json.obj(
        "follower" -> BasicUser.fromUser(follower),
        "library" -> Json.toJson(LibraryNotificationInfo.fromLibraryAndOwner(lib, libImageOpt, owner))
      ))
    ) map { _ =>
        if (!lotsOfFollowers) {
          val canSendPush = kifiInstallationCommander.isMobileVersionEqualOrGreaterThen(lib.ownerId, KifiAndroidVersion("2.2.4"), KifiIPhoneVersion("2.1.0"))
          if (canSendPush) {
            elizaClient.sendUserPushNotification(
              userId = lib.ownerId,
              message = message,
              recipient = follower,
              pushNotificationExperiment = PushNotificationExperiment.Experiment1,
              category = UserPushNotificationCategory.NewLibraryFollower)
          }
        }
      }
  }

  def notifyFollowersOfNewKeeps(library: Library, newKeeps: Keep*): Unit = {
    newKeeps.foreach { newKeep =>
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
            "library" -> Json.toJson(LibraryNotificationInfo.fromLibraryAndOwner(library, libImageOpt, basicKeeper)),
            "keep" -> Json.obj(
              "id" -> newKeep.externalId,
              "url" -> newKeep.url
            )
          ))
        ).foreach { _ =>
            if (toBeNotified.size > 100) {
              airbrake.notify("Warning: Library with lots of subscribers. Time to make the code better!")
            }
            FutureHelpers.sequentialExec(toBeNotified) { userId =>
              elizaClient.sendLibraryPushNotification(
                userId,
                message = s"New Keep in ${library.name}",
                libraryId = library.id.get,
                libraryUrl = "https://www.kifi.com" + Library.formatLibraryPath(owner.username, library.slug),
                pushNotificationExperiment = PushNotificationExperiment.Experiment1,
                category = LibraryPushNotificationCategory.LibraryChanged
              )
            }
          }
      }
    }
  }

  def joinLibrary(userId: Id[User], libraryId: Id[Library], authToken: Option[String] = None, subscribed: Option[Boolean] = None)(implicit eventContext: HeimdalContext): Either[LibraryFail, (Library, LibraryMembership)] = {
    val (lib, inviteList) = db.readOnlyMaster { implicit s =>
      val lib = libraryRepo.get(libraryId)
      val tokenInvites = if (authToken.isDefined) {
        getValidLibInvitesFromAuthToken(libraryId, authToken)
      } else Seq.empty
      val libInvites = libraryInviteRepo.getWithLibraryIdAndUserId(libraryId, userId)
      val allInvites = tokenInvites ++ libInvites
      (lib, allInvites)
    }

    if (lib.kind == LibraryKind.SYSTEM_MAIN || lib.kind == LibraryKind.SYSTEM_SECRET)
      Left(LibraryFail(FORBIDDEN, "cant_join_system_generated_library"))
    else if (lib.visibility != LibraryVisibility.PUBLISHED && inviteList.isEmpty) // private library & no library invites with matching authtoken
      Left(LibraryFail(FORBIDDEN, "cant_join_nonpublished_library"))
    else {
      val maxAccess = if (inviteList.isEmpty) LibraryAccess.READ_ONLY else inviteList.sorted.last.access
      val (updatedLib, updatedMem) = db.readWrite(attempts = 3) { implicit s =>
        val updatedMem = libraryMembershipRepo.getWithLibraryIdAndUserId(libraryId, userId, None) match {
          case None =>
            val subscribedToUpdates = subscribed.getOrElse(maxAccess == LibraryAccess.READ_WRITE)
            val mem = libraryMembershipRepo.save(LibraryMembership(libraryId = libraryId, userId = userId, access = maxAccess, lastJoinedAt = Some(currentDateTime), subscribedToUpdates = subscribedToUpdates))
            SafeFuture {
              notifyOwnerOfNewFollower(userId, lib)
            }
            mem
          case Some(mem) =>
            val maxWithExisting = (maxAccess :: mem.access :: Nil).sorted.last
            val subscribedToUpdates = subscribed.getOrElse(maxWithExisting == LibraryAccess.READ_WRITE || mem.subscribedToUpdates)
            libraryMembershipRepo.save(mem.copy(access = maxWithExisting, state = LibraryMembershipStates.ACTIVE, lastJoinedAt = Some(currentDateTime), subscribedToUpdates = subscribedToUpdates))
        }
        val updatedLib = libraryRepo.save(lib.copy(memberCount = libraryMembershipRepo.countWithLibraryId(libraryId)))
        inviteList.foreach { inv =>
          libraryInviteRepo.save(inv.copy(state = LibraryInviteStates.ACCEPTED))
        }
        val invitesToAlert = inviteList.filterNot(_.inviterId == lib.ownerId)
        if (invitesToAlert.nonEmpty) {
          val invaitee = userRepo.get(userId)
          val owner = basicUserRepo.load(lib.ownerId)
          notifyInviterOnLibraryInvitationAcceptance(invitesToAlert, invaitee, lib, owner)
        }
        (updatedLib, updatedMem)
      }
      updateLibraryJoin(userId, lib, eventContext)
      Right((updatedLib, updatedMem))
    }
  }

  def notifyInviterOnLibraryInvitationAcceptance(invitesToAlert: Seq[LibraryInvite], invaitee: User, lib: Library, owner: BasicUser): Unit = {
    val invaiteeImage = s3ImageStore.avatarUrlByUser(invaitee)
    val libImageOpt = libraryImageCommander.getBestImageForLibrary(lib.id.get, ProcessedImageSize.Medium.idealSize)
    invitesToAlert foreach { invite =>
      val inviterId = invite.inviterId
      elizaClient.sendGlobalNotification( //push sent
        userIds = Set(inviterId),
        title = s"${invaitee.firstName} is now following ${lib.name}",
        body = s"You invited ${invaitee.fullName} to follow ${lib.name}.",
        linkText = s"See ${invaitee.firstName}’s profile",
        linkUrl = s"https://www.kifi.com/${invaitee.username.value}",
        imageUrl = invaiteeImage,
        sticky = false,
        category = NotificationCategory.User.LIBRARY_FOLLOWED,
        extra = Some(Json.obj(
          "follower" -> BasicUser.fromUser(invaitee),
          "library" -> Json.toJson(LibraryNotificationInfo.fromLibraryAndOwner(lib, libImageOpt, owner))
        ))
      ) map { _ =>
          val message = s"${invaitee.firstName} is now following ${lib.name}"
          val canSendPush = kifiInstallationCommander.isMobileVersionEqualOrGreaterThen(inviterId, KifiAndroidVersion("2.2.4"), KifiIPhoneVersion("2.1.0"))
          if (canSendPush) {
            elizaClient.sendUserPushNotification(
              userId = inviterId,
              message = message,
              recipient = invaitee,
              pushNotificationExperiment = PushNotificationExperiment.Experiment1,
              category = UserPushNotificationCategory.NewLibraryFollower)
          }
        }
    }
  }

  private def updateLibraryJoin(userId: Id[User], library: Library, eventContext: HeimdalContext): Future[Unit] = SafeFuture {
    val libraryId = library.id.get
    libraryAnalytics.acceptLibraryInvite(userId, library, eventContext)
    libraryAnalytics.followLibrary(userId, library, eventContext)
    searchClient.updateLibraryIndex()
  }

  def declineLibrary(userId: Id[User], libraryId: Id[Library]) = {
    db.readWrite { implicit s =>
      val listInvites = libraryInviteRepo.getWithLibraryIdAndUserId(libraryId = libraryId, userId = userId)
      listInvites.map(inv => libraryInviteRepo.save(inv.copy(state = LibraryInviteStates.DECLINED)))
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
        }
        Right((): Unit)
    }
  }

  private def convertKeepOwnershipToLibraryOwner(userId: Id[User], library: Library) = {
    db.readWrite { implicit s =>
      keepRepo.getByUserIdAndLibraryId(userId, library.id.get).map { keep =>
        keepRepo.save(keep.copy(userId = library.ownerId))
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
      groupedKeeps.map {
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
      }.flatten.foreach { keep =>
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

  def fixLibraryKeepCount(libIds: Seq[Id[Library]]) = {
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
        def saveKeep(k: Keep, s: RWSession): Either[LibraryError, Keep] = {
          implicit val session = s

          val currentKeepOpt = if (toLibrary.isDisjoint)
            keepRepo.getPrimaryInDisjointByUriAndUser(k.uriId, userId)
          else
            keepRepo.getPrimaryByUriAndLibrary(k.uriId, toLibraryId)

          currentKeepOpt match {
            case None =>
              val newKeep = keepRepo.save(Keep(title = k.title, uriId = k.uriId, url = k.url, urlId = k.urlId, visibility = toLibrary.visibility,
                userId = userId, note = k.note, source = withSource.getOrElse(k.source), libraryId = Some(toLibraryId), inDisjointLib = toLibrary.isDisjoint, originalKeeperId = k.originalKeeperId.orElse(Some(userId))))
              combineTags(k.id.get, newKeep.id.get)
              Right(newKeep)
            case Some(existingKeep) if existingKeep.state == KeepStates.INACTIVE =>
              val newKeep = keepRepo.save(existingKeep.copy(userId = userId, libraryId = Some(toLibraryId), visibility = toLibrary.visibility,
                inDisjointLib = toLibrary.isDisjoint, source = withSource.getOrElse(k.source), state = KeepStates.ACTIVE))
              combineTags(k.id.get, existingKeep.id.get)
              Right(newKeep)
            case Some(existingKeep) =>
              if (existingKeep.inDisjointLib) {
                val newKeep = keepRepo.save(existingKeep.copy(userId = userId, libraryId = Some(toLibraryId), visibility = toLibrary.visibility,
                  inDisjointLib = toLibrary.isDisjoint, source = withSource.getOrElse(k.source), state = KeepStates.ACTIVE))
                combineTags(k.id.get, existingKeep.id.get)
                Right(newKeep)
              } else {
                combineTags(k.id.get, existingKeep.id.get)
                Left(LibraryError.AlreadyExistsInDest)
              }
          }
        }
        val keepResults = applyToKeeps(userId, toLibraryId, keeps, Set(), saveKeep)
        Future {
          libraryAnalytics.editLibrary(userId, toLibrary, context, Some("copy_keeps"))
        }
        keepResults
    }
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
        def saveKeep(k: Keep, s: RWSession): Either[LibraryError, Keep] = {
          implicit val session = s

          val currentKeepOpt = if (toLibrary.isDisjoint)
            keepRepo.getPrimaryInDisjointByUriAndUser(k.uriId, userId)
          else
            keepRepo.getPrimaryByUriAndLibrary(k.uriId, toLibraryId)

          currentKeepOpt match {
            case None =>
              val movedKeep = keepRepo.save(k.copy(libraryId = Some(toLibraryId), visibility = toLibrary.visibility,
                inDisjointLib = toLibrary.isDisjoint, state = KeepStates.ACTIVE))
              Right(movedKeep)
            case Some(existingKeep) if existingKeep.state == KeepStates.INACTIVE =>
              val movedKeep = keepRepo.save(existingKeep.copy(libraryId = Some(toLibraryId), visibility = toLibrary.visibility,
                inDisjointLib = toLibrary.isDisjoint, state = KeepStates.ACTIVE))
              keepRepo.save(k.copy(state = KeepStates.INACTIVE))
              combineTags(k.id.get, existingKeep.id.get)
              Right(movedKeep)
            case Some(existingKeep) =>
              if (toLibraryId == k.libraryId.get) {
                Left(LibraryError.AlreadyExistsInDest)
              } else if (existingKeep.inDisjointLib) {
                val newKeep = keepRepo.save(existingKeep.copy(libraryId = Some(toLibraryId), visibility = toLibrary.visibility, inDisjointLib = toLibrary.isDisjoint, state = KeepStates.ACTIVE))
                combineTags(k.id.get, existingKeep.id.get)
                Right(newKeep)
              } else {
                keepRepo.save(k.copy(state = KeepStates.INACTIVE))
                combineTags(k.id.get, existingKeep.id.get)
                Left(LibraryError.AlreadyExistsInDest)
              }
          }
        }
        val keepResults = applyToKeeps(userId, toLibraryId, keeps, Set(LibraryAccess.READ_ONLY, LibraryAccess.READ_INSERT), saveKeep)
        Future {
          libraryAnalytics.editLibrary(userId, toLibrary, context, Some("move_keeps"))
        }
        keepResults
    }
  }

  // combine tag info on both keeps & saves difference on the new Keep
  private def combineTags(oldKeepId: Id[Keep], newKeepId: Id[Keep])(implicit s: RWSession) = {
    val oldSet = keepToCollectionRepo.getCollectionsForKeep(oldKeepId).toSet
    val existingSet = keepToCollectionRepo.getCollectionsForKeep(newKeepId).toSet
    val tagsToAdd = oldSet.diff(existingSet)
    tagsToAdd.map { tagId =>
      keepToCollectionRepo.getOpt(newKeepId, tagId) match {
        case None =>
          keepToCollectionRepo.save(KeepToCollection(keepId = newKeepId, collectionId = tagId))
        case Some(ktc) if ktc.state == KeepToCollectionStates.INACTIVE =>
          keepToCollectionRepo.save(ktc.copy(state = KeepToCollectionStates.ACTIVE))
        case _ =>
      }
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
    val (main, secret) = if (mainOpt.isEmpty || secretOpt.isEmpty) {
      // Right now, we don't have any users without libraries. However, I'd prefer to be safe for now
      // and fix it if a user's libraries are not set up.
      log.error(s"Unable to get main or secret libraries for user $userId: $mainOpt $secretOpt")
      internSystemGeneratedLibraries(userId)
    } else (mainOpt.get._2, secretOpt.get._2)
    (main, secret)
  }

  def getLibraryWithUsernameAndSlug(username: String, slug: LibrarySlug, viewerId: Option[Id[User]])(implicit context: HeimdalContext): Either[LibraryFail, Library] = {
    val ownerIdentifier = ExternalId.asOpt[User](username).map(Left(_)) getOrElse Right(Username(username))
    val ownerOpt = ownerIdentifier match {
      case Left(externalId) => db.readOnlyMaster { implicit s => userRepo.getOpt(externalId).map((_, false)) }
      case Right(username) => userCommander.get.getUserByUsernameOrAlias(username)
    }
    ownerOpt match {
      case None => Left(LibraryFail(BAD_REQUEST, "invalid_username"))
      case Some((owner, isUserAlias)) =>
        getLibraryBySlugOrAlias(owner.id.get, slug) match {
          case None => Left(LibraryFail(NOT_FOUND, "no_library_found"))
          case Some((library, isLibraryAlias)) =>
            Right(library)
        }
    }
  }

  def trackLibraryView(viewerId: Option[Id[User]], library: Library)(implicit context: HeimdalContext): Unit = {
    libraryAnalytics.viewedLibrary(viewerId, library, context)
  }

  def getLibraryBySlugOrAlias(ownerId: Id[User], slug: LibrarySlug): Option[(Library, Boolean)] = {
    db.readOnlyMaster { implicit session =>
      libraryRepo.getBySlugAndUserId(ownerId, slug).map((_, false)) orElse
        libraryAliasRepo.getByOwnerIdAndSlug(ownerId, slug).map(alias => (libraryRepo.get(alias.libraryId), true)).filter(_._1.state == LibraryStates.ACTIVE)
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
        (infos.zip(libs).map {
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
              kind = lib.kind)
        }).seq.sortBy(_._1).map(_._2)
      }
    } getOrElse Future.successful(Seq.empty)
  }

  def createLibraryCardInfos(libs: Seq[Library], owners: Map[Id[User], BasicUser], viewerOpt: Option[User], withFollowing: Boolean, idealSize: ImageSize)(implicit session: RSession): ParSeq[LibraryCardInfo] = {
    val libIds = libs.map(_.id.get).toSet
    val membershipsToLibsMap = viewerOpt.map { viewer =>
      libraryMembershipRepo.getWithLibraryIdsAndUserId(libIds, viewer.id.get)
    } getOrElse Map.empty
    libs.par map { lib => // may want to optimize queries below into bulk queries
      val image = ProcessedImageSize.pickBestImage(idealSize, libraryImageRepo.getActiveForLibraryId(lib.id.get), false)
      val (numFollowers, followersSample, numCollaborators, collabsSample) = if (lib.memberCount > 1) {
        val countMap = libraryMembershipRepo.countWithLibraryIdByAccess(lib.id.get)
        val numFollowers = countMap.readOnly
        val numCollaborators = countMap.readWrite

        val collaborators = libraryMembershipRepo.pageWithLibraryIdAndAccess(lib.id.get, 0, 3, Set(LibraryAccess.READ_WRITE, LibraryAccess.READ_INSERT))
        val followers = libraryMembershipRepo.pageWithLibraryIdAndAccess(lib.id.get, 0, 3, Set(LibraryAccess.READ_ONLY))
        val collabIds = collaborators.map(_.userId).toSet
        val followerIds = followers.map(_.userId).toSet
        val userSample = basicUserRepo.loadAll(followerIds ++ collabIds) //we don't care about the order now anyway
        val followersSample = followerIds.map(id => userSample(id)).toSeq
        val collabsSample = collabIds.map(id => userSample(id)).toSeq
        (numFollowers, followersSample, numCollaborators, collabsSample)
      } else {
        (0, Seq.empty, 0, Seq.empty)
      }

      val owner = owners(lib.ownerId)
      val membershipOpt = membershipsToLibsMap.get(lib.id.get).flatten
      val isFollowing = if (withFollowing && membershipOpt.isDefined) {
        Some(membershipOpt.isDefined)
      } else {
        None
      }
      createLibraryCardInfo(lib, image, owner, numFollowers, followersSample, numCollaborators, collabsSample, isFollowing, membershipOpt)
    }
  }

  private def createLibraryCardInfo(lib: Library, image: Option[LibraryImage], owner: BasicUser, numFollowers: Int,
    followers: Seq[BasicUser], numCollaborators: Int, collaborators: Seq[BasicUser], isFollowing: Option[Boolean], membershipOpt: Option[LibraryMembership]): LibraryCardInfo = {
    LibraryCardInfo(
      id = Library.publicId(lib.id.get),
      name = lib.name,
      description = lib.description,
      color = lib.color,
      image = image.map(LibraryImageInfo.createInfo),
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
      caption = None,
      modifiedAt = lib.updatedAt,
      kind = lib.kind)
  }

  def convertPendingInvites(emailAddress: EmailAddress, userId: Id[User]) = {
    db.readWrite { implicit s =>
      libraryInviteRepo.getByEmailAddress(emailAddress, Set.empty) foreach { libInv =>
        libraryInviteRepo.save(libInv.copy(userId = Some(userId)))
      }
    }
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

        case (Some(mem), Some(targetMem), library) =>

          if ((mem.isOwner && !targetMem.isOwner) || // owners can edit anyone except themselves
            (mem.isCollaborator && !targetMem.isOwner) || // a collaborator can edit anyone (but the owner). Collaborator cannot invite others to collaborate if the library does not allow collaborators to invite
            (mem.isFollower && mem.userId == targetMem.userId)) { // a follower can only edit herself
            db.readWrite { implicit s =>
              newAccess match {
                case None =>
                  SafeFuture { convertKeepOwnershipToLibraryOwner(targetMem.userId, library) }
                  Right(libraryMembershipRepo.save(targetMem.copy(state = LibraryMembershipStates.INACTIVE)))
                case Some(newAccess) if mem.isCollaborator && newAccess == LibraryAccess.READ_WRITE && library.whoCanInvite == Some(LibraryInvitePermissions.OWNER) =>
                  log.warn(s"[updateLibraryMembership] invalid permission ${mem} trying to change membership ${targetMem} to ${newAccess} when library has invite policy ${library.whoCanInvite}")
                  Left(LibraryFail(FORBIDDEN, "invalid_collaborator_permission"))
                case Some(newAccess) =>
                  SafeFuture { convertKeepOwnershipToLibraryOwner(targetMem.userId, library) }
                  val newSubscription = if (newAccess == LibraryAccess.READ_WRITE) true else targetMem.subscribedToUpdates // auto subscribe to updates if a collaborator
                  Right(libraryMembershipRepo.save(targetMem.copy(access = newAccess, subscribedToUpdates = newSubscription)))
              }
            }
          } else { // invalid permissions
            log.warn(s"[updateLibraryMembership] invalid permission ${mem} trying to change membership ${targetMem} to ${newAccess}")
            Left(LibraryFail(FORBIDDEN, "invalid_permissions"))
          }
      }
    }
  }

}
