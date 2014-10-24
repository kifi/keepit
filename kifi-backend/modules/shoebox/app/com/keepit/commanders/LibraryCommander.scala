package com.keepit.commanders

import com.google.inject.{ Provider, Inject }
import com.keepit.commanders.emails.{ LibraryInviteEmailSender, EmailOptOutCommander }
import com.keepit.common.cache._
import com.keepit.common.crypto.{ PublicIdConfiguration, PublicId }
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.{ Id, ExternalId }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.{ AccessLog, Logging }
import com.keepit.common.mail.template.tags
import com.keepit.common.mail.{ ElectronicMail, EmailAddress }
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.store.S3ImageStore
import com.keepit.common.time.Clock
import com.keepit.eliza.ElizaServiceClient
import com.keepit.heimdal.{ HeimdalContext, HeimdalServiceClient, HeimdalContextBuilderFactory, UserEvent, UserEventTypes }
import com.keepit.inject.FortyTwoConfig
import com.keepit.model._
import com.keepit.search.SearchServiceClient
import com.keepit.social.{ SocialNetworks, BasicUser }
import com.kifi.macros.json
import org.joda.time.DateTime
import play.api.http.Status._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.functional.syntax._
import play.api.libs.json._
import views.html.admin.images

import scala.concurrent._
import scala.concurrent.duration.Duration
import scala.util.Success
import com.keepit.common.json

class LibraryCommander @Inject() (
    db: Database,
    libraryRepo: LibraryRepo,
    libraryMembershipRepo: LibraryMembershipRepo,
    libraryInviteRepo: LibraryInviteRepo,
    libraryInvitesAbuseMonitor: LibraryInvitesAbuseMonitor,
    userRepo: UserRepo,
    basicUserRepo: BasicUserRepo,
    keepRepo: KeepRepo,
    keepToCollectionRepo: KeepToCollectionRepo,
    keepsCommanderProvider: Provider[KeepsCommander],
    countByLibraryCache: CountByLibraryCache,
    typeaheadCommander: TypeaheadCommander,
    collectionRepo: CollectionRepo,
    s3ImageStore: S3ImageStore,
    emailOptOutCommander: EmailOptOutCommander,
    airbrake: AirbrakeNotifier,
    searchClient: SearchServiceClient,
    elizaClient: ElizaServiceClient,
    keptAnalytics: KeepingAnalytics,
    libraryInviteSender: Provider[LibraryInviteEmailSender],
    heimdal: HeimdalServiceClient,
    contextBuilderFactory: HeimdalContextBuilderFactory,
    keepImageCommander: KeepImageCommander,
    applicationConfig: FortyTwoConfig,
    uriSummaryCommander: URISummaryCommander,
    socialUserInfoRepo: SocialUserInfoRepo,
    implicit val publicIdConfig: PublicIdConfiguration,
    clock: Clock) extends Logging {

  def libraryMetaTags(library: Library): PublicPageMetaTags = {
    db.readOnlyMaster { implicit s =>
      val owner = userRepo.get(library.ownerId)

      val facebookId: Option[String] = socialUserInfoRepo.getByUser(owner.id.get).filter(i => i.networkType == SocialNetworks.FACEBOOK).map(_.socialId.id).headOption

      val keeps = keepRepo.getByLibrary(library.id.get, 0, 50)

      //facebook OG recommends:
      //We suggest that you use an image of at least 1200x630 pixels.
      val imageUrls: Seq[String] = {
        val images: Seq[KeepImage] = keeps map { keep =>
          keepImageCommander.getBestImageForKeep(keep.id.get, KeepImageSize.XLarge.idealSize)
        } flatten
        val sorted: Seq[KeepImage] = images.sortWith {
          case (image1, image2) =>
            (image1.imageSize.width * image1.imageSize.height) > (image2.imageSize.width * image2.imageSize.height)
        }
        val urls: Seq[String] = sorted.take(10) map { image =>
          s"http:${keepImageCommander.getUrl(image)}"
        }
        //last image is the kifi image we want to append to all image lists
        urls :+ "https://djty7jcqog9qu.cloudfront.net/assets/fbc1200X630.png"
      }
      //should also get owr word2vec
      val embedlyKeywords: Seq[String] = keeps map { keep =>
        uriSummaryCommander.getStoredEmbedlyKeywords(keep.uriId).map(_.name)
      } flatten
      val tags: Seq[String] = collectionRepo.getTagsByLibrary(library.id.get).map(_.tag).toSeq
      val allTags: Seq[String] = (embedlyKeywords ++ tags).toSet.toSeq
      val urlPathOnly = Library.formatLibraryPath(owner.username, owner.externalId, library.slug)
      PublicPageMetaTags(
        title = s"${library.name} by ${owner.firstName} ${owner.lastName} \u2022 Kifi",
        url = s"http:${applicationConfig.applicationBaseUrl}$urlPathOnly",
        urlPathOnly = urlPathOnly,
        description = library.description.getOrElse(s"${owner.fullName}'s ${library.name} Kifi Library"),
        images = imageUrls,
        facebookId = facebookId,
        createdAt = library.createdAt,
        updatedAt = library.updatedAt,
        tags = allTags,
        firstName = owner.firstName,
        lastName = owner.lastName)
    }
  }

  def getKeeps(libraryId: Id[Library], offset: Int, limit: Int): Future[Seq[Keep]] = {
    db.readOnlyReplicaAsync { implicit s => keepRepo.getByLibrary(libraryId, offset, limit) }
  }

  def getKeepsCount(libraryId: Id[Library]): Future[Int] = {
    db.readOnlyMasterAsync { implicit s => keepRepo.getCountByLibrary(libraryId) }
  }

  def getAccessStr(userId: Id[User], libraryId: Id[Library]): Option[String] = {
    val membership: Option[LibraryMembership] = db.readOnlyMaster { implicit s =>
      libraryMembershipRepo.getWithLibraryIdAndUserId(libraryId, userId)
    }
    membership.map(_.access.value)
  }

  def updateLastView(userId: Id[User], libraryId: Id[Library]): Unit = {
    future {
      db.readWrite { implicit s =>
        libraryMembershipRepo.getWithLibraryIdAndUserId(libraryId, userId).map { mem =>
          libraryMembershipRepo.updateLastViewed(mem.id.get) // do not update seq num
        }
      }
    }
  }

  def getLibraryById(userIdOpt: Option[Id[User]], id: Id[Library]): Future[(FullLibraryInfo, String)] = {
    val lib = db.readOnlyMaster { implicit s => libraryRepo.get(id) }
    createFullLibraryInfo(userIdOpt, lib).map { libInfo =>
      val accessStr = userIdOpt.flatMap(getAccessStr(_, id)) getOrElse "none"
      (libInfo, accessStr)
    }
  }

  def getLibrarySummaryById(userIdOpt: Option[Id[User]], id: Id[Library]): (LibraryInfo, String) = {
    val libInfo = db.readOnlyMaster { implicit s =>
      val lib = libraryRepo.get(id)
      val owner = basicUserRepo.load(lib.ownerId)
      val numKeeps = keepRepo.getCountByLibrary(id)
      LibraryInfo.fromLibraryAndOwner(lib, owner, numKeeps)
    }
    val accessStr = userIdOpt.flatMap(getAccessStr(_, id)) getOrElse "none"
    (libInfo, accessStr)
  }

  def getLibraryWithOwnerAndCounts(libraryId: Id[Library], viewerUserId: Id[User]): Either[(Int, String), (Library, BasicUser, Int, Int)] = {
    db.readOnlyReplica { implicit s =>
      val library = libraryRepo.get(libraryId)
      if (library.visibility == LibraryVisibility.PUBLISHED ||
        library.ownerId == viewerUserId ||
        libraryMembershipRepo.getWithLibraryIdAndUserId(libraryId, viewerUserId).isDefined) {
        val owner = basicUserRepo.load(library.ownerId)
        val keepCount = keepRepo.getCountByLibrary(library.id.get)
        val followerCount = libraryMembershipRepo.countWithLibraryIdAndAccess(library.id.get, Set(LibraryAccess.READ_ONLY))
        Right(library, owner, keepCount, followerCount)
      } else {
        Left(403, "library_access_denied")
      }
    }
  }

  def createFullLibraryInfo(viewerUserIdOpt: Option[Id[User]], library: Library): Future[FullLibraryInfo] = {

    val memberIds = {
      val (collaborators, followers, _) = getLibraryMembers(library.id.get, 0, 10, fillInWithInvites = false)
      (collaborators ++ followers).map(_.userId)
    }

    val (lib, owner, members, numCollabs, numFollows, keeps, keepCount) = db.readOnlyReplica { implicit s =>
      val usersById = basicUserRepo.loadAll(memberIds.toSet + library.ownerId)
      val owner = usersById(library.ownerId)
      val members = memberIds.map(usersById(_))
      val collabCount = libraryMembershipRepo.countWithLibraryIdAndAccess(library.id.get, Set(LibraryAccess.READ_WRITE, LibraryAccess.READ_INSERT))
      val followCount = libraryMembershipRepo.countWithLibraryIdAndAccess(library.id.get, Set(LibraryAccess.READ_ONLY))
      val keeps = keepRepo.getByLibrary(library.id.get, 0, 10)
      val keepCount = keepRepo.getCountByLibrary(library.id.get)
      (library, owner, members, collabCount, followCount, keeps, keepCount)
    }

    keepsCommanderProvider.get.decorateKeepsIntoKeepInfos(viewerUserIdOpt, keeps).map { keepInfos =>
      FullLibraryInfo(
        id = Library.publicId(lib.id.get),
        name = lib.name,
        owner = owner,
        description = lib.description,
        slug = lib.slug,
        url = Library.formatLibraryPath(owner.username, owner.externalId, lib.slug),
        kind = lib.kind,
        visibility = lib.visibility,
        followers = members,
        keeps = keepInfos,
        numKeeps = keepCount,
        numCollaborators = numCollabs,
        numFollowers = numFollows,
        lastKept = lib.lastKept)
    }
  }

  def getLibraryMembers(libraryId: Id[Library], offset: Int, limit: Int, fillInWithInvites: Boolean): (Seq[LibraryMembership], Seq[LibraryMembership], Seq[(Either[Id[User], EmailAddress], Set[LibraryInvite])]) = {
    val collaboratorsAccess: Set[LibraryAccess] = Set(LibraryAccess.READ_INSERT, LibraryAccess.READ_WRITE)
    val followersAccess: Set[LibraryAccess] = Set(LibraryAccess.READ_ONLY)
    val membersAccess = collaboratorsAccess ++ followersAccess
    val relevantInviteStates = Set(LibraryInviteStates.ACTIVE)

    db.readOnlyMaster { implicit session =>
      // Get Collaborators
      val collaborators = libraryMembershipRepo.pageWithLibraryIdAndAccess(libraryId, offset, limit, collaboratorsAccess)

      // Get Followers
      val collaboratorsShown = collaborators.length
      val followersLimit = limit - collaboratorsShown
      val followers = if (followersLimit == 0) Seq.empty[LibraryMembership] else {
        val followersOffset = if (collaboratorsShown > 0) 0 else {
          val collaboratorsTotal = libraryMembershipRepo.countWithLibraryIdAndAccess(libraryId, collaboratorsAccess)
          offset - collaboratorsTotal
        }
        libraryMembershipRepo.pageWithLibraryIdAndAccess(libraryId, followersOffset, followersLimit, followersAccess)
      }

      // Get Invitees with Invites
      val membersShown = collaborators.length + followers.length
      val inviteesLimit = limit - membersShown
      val inviteesWithInvites = if (inviteesLimit == 0 || !fillInWithInvites) Seq.empty[(Either[Id[User], EmailAddress], Set[LibraryInvite])] else {
        val inviteesOffset = if (membersShown > 0) 0 else {
          val membersTotal = libraryMembershipRepo.countWithLibraryIdAndAccess(libraryId, membersAccess)
          offset - membersTotal
        }
        libraryInviteRepo.pageInviteesByLibraryId(libraryId, inviteesOffset, inviteesLimit, relevantInviteStates)
      }
      (collaborators, followers, inviteesWithInvites)
    }
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
        val member = invitee.left.map(usersById(_))
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
          MaybeLibraryMember(Right(contact.email), access, lastInvitedAt)
        }
        suggestedUsers ++ suggestedEmailAddresses
    }
  }

  def addLibrary(libAddReq: LibraryAddRequest, ownerId: Id[User]): Either[LibraryFail, Library] = {
    val badMessage: Option[String] = {
      if (libAddReq.name.isEmpty || !Library.isValidName(libAddReq.name)) { Some("invalid_name") }
      else if (libAddReq.slug.isEmpty || !LibrarySlug.isValidSlug(libAddReq.slug)) { Some("invalid_slug") }
      else { None }
    }
    badMessage match {
      case Some(x) => Left(LibraryFail(x))
      case _ => {
        val validSlug = LibrarySlug(libAddReq.slug)
        db.readOnlyReplica { implicit s => libraryRepo.getByNameOrSlug(ownerId, libAddReq.name, validSlug) } match {
          case Some(lib) =>
            Left(LibraryFail("library_name_or_slug_exists"))
          case None =>
            val (collaboratorIds, followerIds) = db.readOnlyReplica { implicit s =>
              val collabs = libAddReq.collaborators.getOrElse(Seq()).map { x =>
                val inviteeIdOpt = userRepo.getOpt(x) collect { case user => user.id.get }
                inviteeIdOpt.get
              }
              val follows = libAddReq.followers.getOrElse(Seq()).map { x =>
                val inviteeIdOpt = userRepo.getOpt(x) collect { case user => user.id.get }
                inviteeIdOpt.get
              }
              (collabs, follows)
            }
            val library = db.readWrite { implicit s =>
              libraryRepo.getOpt(ownerId, validSlug) match {
                case None =>
                  val lib = libraryRepo.save(Library(ownerId = ownerId, name = libAddReq.name, description = libAddReq.description,
                    visibility = libAddReq.visibility, slug = validSlug, kind = LibraryKind.USER_CREATED, memberCount = 1))
                  libraryMembershipRepo.save(LibraryMembership(libraryId = lib.id.get, userId = ownerId, access = LibraryAccess.OWNER, showInSearch = true))
                  lib
                case Some(lib) =>
                  val newLib = libraryRepo.save(lib.copy(state = LibraryStates.ACTIVE))
                  libraryMembershipRepo.getWithLibraryIdAndUserId(libraryId = lib.id.get, userId = ownerId) match {
                    case None => libraryMembershipRepo.save(LibraryMembership(libraryId = lib.id.get, userId = ownerId, access = LibraryAccess.OWNER, showInSearch = true))
                    case Some(mem) => libraryMembershipRepo.save(mem.copy(state = LibraryMembershipStates.ACTIVE))
                  }
                  newLib
              }
            }
            val bulkInvites1 = for (c <- collaboratorIds) yield LibraryInvite(libraryId = library.id.get, inviterId = ownerId, userId = Some(c), access = LibraryAccess.READ_WRITE)
            val bulkInvites2 = for (c <- followerIds) yield LibraryInvite(libraryId = library.id.get, inviterId = ownerId, userId = Some(c), access = LibraryAccess.READ_ONLY)

            inviteBulkUsers(bulkInvites1 ++ bulkInvites2)
            searchClient.updateLibraryIndex()
            Right(library)
        }
      }
    }
  }

  def canModifyLibrary(libraryId: Id[Library], userId: Id[User]): Boolean = {
    db.readOnlyReplica { implicit s => libraryMembershipRepo.getOpt(userId, libraryId) } exists { memebership => //not cached!
      memebership.canWrite
    }
  }

  def modifyLibrary(libraryId: Id[Library], userId: Id[User],
    name: Option[String] = None,
    description: Option[String] = None,
    slug: Option[String] = None,
    visibility: Option[LibraryVisibility] = None): Either[LibraryFail, Library] = {

    val targetLib = db.readOnlyMaster { implicit s => libraryRepo.get(libraryId) }
    if (targetLib.ownerId != userId) {
      Left(LibraryFail("permission_denied"))
    } else {
      def validName(name: String): Either[LibraryFail, String] = {
        if (Library.isValidName(name)) Right(name)
        else Left(LibraryFail("invalid_name"))
      }
      def validSlug(slug: String): Either[LibraryFail, String] = {
        if (LibrarySlug.isValidSlug(slug)) Right(slug)
        else Left(LibraryFail("invalid_slug"))
      }

      val result = for {
        newName <- validName(name.getOrElse(targetLib.name)).right
        newSlug <- validSlug(slug.getOrElse(targetLib.slug.value)).right
      } yield {
        val newDescription: Option[String] = description.orElse(targetLib.description)
        val newVisibility: LibraryVisibility = visibility.getOrElse(targetLib.visibility)
        future {
          val keeps = db.readOnlyMaster { implicit s =>
            keepRepo.getByLibrary(libraryId, 0, Int.MaxValue, None)
          }
          if (keeps.nonEmpty) {
            db.readWriteBatch(keeps) { (s, k) =>
              keepRepo.save(k.copy(visibility = newVisibility))(s)
            }
            searchClient.updateKeepIndex()
          }
        }
        db.readWrite { implicit s =>
          libraryRepo.save(targetLib.copy(name = newName, slug = LibrarySlug(newSlug), visibility = newVisibility, description = newDescription))
        }
      }
      searchClient.updateLibraryIndex()
      result
    }
  }

  def removeLibrary(libraryId: Id[Library], userId: Id[User])(implicit context: HeimdalContext): Option[(Int, String)] = {
    val oldLibrary = db.readOnlyMaster { implicit s => libraryRepo.get(libraryId) }
    if (oldLibrary.ownerId != userId) {
      Some((FORBIDDEN, "permission_denied"))
    } else if (oldLibrary.kind == LibraryKind.SYSTEM_MAIN || oldLibrary.kind == LibraryKind.SYSTEM_SECRET) {
      Some((BAD_REQUEST, "cant_delete_system_generated_library"))
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
      keptAnalytics.unkeptPages(userId, savedKeeps.keySet.toSeq, context)
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

  private def checkAuthTokenAndPassPhrase(libraryId: Id[Library], authToken: Option[String], passPhrase: Option[HashedPassPhrase])(implicit s: RSession) = {
    authToken.nonEmpty && passPhrase.nonEmpty && {
      val excludeSet = Set(LibraryInviteStates.INACTIVE, LibraryInviteStates.ACCEPTED, LibraryInviteStates.DECLINED)
      libraryInviteRepo.getByLibraryIdAndAuthToken(libraryId, authToken.get, excludeSet)
        .exists { i =>
          HashedPassPhrase.generateHashedPhrase(i.passPhrase) == passPhrase.get
        }
    }
  }

  def canViewLibrary(userId: Option[Id[User]], library: Library,
    authToken: Option[String] = None,
    passPhrase: Option[HashedPassPhrase] = None): Boolean = {

    library.visibility == LibraryVisibility.PUBLISHED || // published library
      db.readOnlyMaster { implicit s =>
        userId match {
          case Some(id) =>
            libraryMembershipRepo.getOpt(userId = id, libraryId = library.id.get).nonEmpty ||
              libraryInviteRepo.getWithLibraryIdAndUserId(userId = id, libraryId = library.id.get, excludeState = Some(LibraryInviteStates.INACTIVE)).nonEmpty ||
              checkAuthTokenAndPassPhrase(library.id.get, authToken, passPhrase)
          case None =>
            checkAuthTokenAndPassPhrase(library.id.get, authToken, passPhrase)
        }
      }
  }

  def canViewLibrary(userId: Option[Id[User]], libraryId: Id[Library], accessToken: Option[String], passCode: Option[HashedPassPhrase]): Boolean = {
    val library = db.readOnlyReplica { implicit session =>
      libraryRepo.get(libraryId)
    }
    canViewLibrary(userId, library, accessToken, passCode)
  }

  def getLibrariesByUser(userId: Id[User]): (Seq[(LibraryMembership, Library)], Seq[(LibraryInvite, Library)]) = {
    db.readOnlyMaster { implicit s =>
      val myLibraries = libraryRepo.getByUser(userId)
      val myInvites = libraryInviteRepo.getByUser(userId, Set(LibraryInviteStates.ACCEPTED, LibraryInviteStates.INACTIVE, LibraryInviteStates.DECLINED))
      (myLibraries, myInvites)
    }
  }

  def getLibrariesUserCanKeepTo(userId: Id[User]): Seq[Library] = {
    db.readOnlyMaster { implicit s =>
      libraryRepo.getByUser(userId, excludeAccess = Some(LibraryAccess.READ_ONLY)).map(_._2)
    }
  }

  def userAccess(userId: Id[User], libraryId: Id[Library], universalLinkOpt: Option[String]): Option[LibraryAccess] = {
    db.readOnlyMaster { implicit s =>
      libraryMembershipRepo.getWithLibraryIdAndUserId(libraryId, userId) match {
        case Some(mem) =>
          Some(mem.access)
        case None =>
          val lib = libraryRepo.get(libraryId)
          if (lib.visibility == LibraryVisibility.PUBLISHED)
            Some(LibraryAccess.READ_ONLY)
          else if (libraryInviteRepo.getWithLibraryIdAndUserId(libraryId, userId).nonEmpty)
            Some(LibraryAccess.READ_ONLY)
          else if (universalLinkOpt.nonEmpty && lib.universalLink == universalLinkOpt.get)
            Some(LibraryAccess.READ_ONLY)
          else
            None
      }
    }
  }

  def inviteBulkUsers(invites: Seq[LibraryInvite]): Future[Seq[ElectronicMail]] = {
    val emailFutures = {
      // save invites
      db.readWrite { implicit s =>
        invites.map { invite =>
          libraryInviteRepo.save(invite)
        }
      }

      invites.groupBy(invite => (invite.inviterId, invite.libraryId))
        .map { key =>
          val (inviterId, libId) = key._1
          val (inviter, lib, libOwner) = db.readOnlyReplica { implicit session =>
            val inviter = basicUserRepo.load(inviterId)
            val lib = libraryRepo.get(libId)
            val libOwner = basicUserRepo.load(lib.ownerId)

            (inviter, lib, libOwner)
          }
          val imgUrl = s3ImageStore.avatarUrlByExternalId(Some(200), inviter.externalId, inviter.pictureName, Some("https"))
          val inviterImage = if (imgUrl.endsWith(".jpg.jpg")) imgUrl.dropRight(4) else imgUrl // basicUser appends ".jpg" which causes an extra .jpg in this case
          val libLink = s"""https://www.kifi.com${Library.formatLibraryPath(libOwner.username, libOwner.externalId, lib.slug)}"""

          // send notifications to kifi users only
          val inviteeIdSet = key._2.map(_.userId).flatten.toSet
          elizaClient.sendGlobalNotification(
            userIds = inviteeIdSet,
            title = s"${inviter.firstName} ${inviter.lastName} invited you to follow a Library!",
            body = s"Browse keeps in ${lib.name} to find some interesting gems kept by ${libOwner.firstName}.",
            linkText = "Let's take a look!",
            linkUrl = libLink,
            imageUrl = inviterImage,
            sticky = false,
            category = NotificationCategory.User.LIBRARY_INVITATION
          )

          // send emails to both users & non-users
          key._2.map { invite =>
            invite.userId match {
              case Some(id) =>
                libraryInvitesAbuseMonitor.inspect(inviterId, Some(id), None, libId, key._2.length)
                Left(id)
              case _ =>
                libraryInvitesAbuseMonitor.inspect(inviterId, None, invite.emailAddress, libId, key._2.length)
                Right(invite.emailAddress.get)
            }
            libraryInviteSender.get.inviteUserToLibrary(invite)
          }
        }.toSeq.flatten
    }
    val emailsF = Future.sequence(emailFutures)
    emailsF map (_.filter(_.isDefined).map(_.get))
  }

  def internSystemGeneratedLibraries(userId: Id[User], generateNew: Boolean = true): (Library, Library) = {
    db.readWrite { implicit session =>
      val libMem = libraryMembershipRepo.getWithUserId(userId, None)
      val allLibs = libraryRepo.getByUser(userId, None)

      // Get all current system libraries, for main/secret, make sure only one is active.
      // This corrects any issues with previously created libraries / memberships
      val sysLibs = allLibs.filter(_._2.ownerId == userId)
        .filter(l => l._2.kind == LibraryKind.SYSTEM_MAIN || l._2.kind == LibraryKind.SYSTEM_SECRET)
        .sortBy(_._2.id.get.id)
        .groupBy(_._2.kind)
        .map {
          case (kind, libs) =>
            val (slug, name, visibility) = if (kind == LibraryKind.SYSTEM_MAIN) ("main", "Main Library", LibraryVisibility.DISCOVERABLE) else ("secret", "Secret Library", LibraryVisibility.SECRET)

            val activeLib = libs.head._2.copy(state = LibraryStates.ACTIVE, slug = LibrarySlug(slug), name = name, visibility = visibility, memberCount = 1)
            val membership = libMem.find(m => m.libraryId == activeLib.id.get && m.access == LibraryAccess.OWNER)
            if (membership.isEmpty) airbrake.notify(s"user $userId - non-existing ownership of library kind ${kind} (id: ${activeLib.id.get})")
            val activeMembership = membership.getOrElse(LibraryMembership(libraryId = activeLib.id.get, userId = userId, access = LibraryAccess.OWNER, showInSearch = true)).copy(state = LibraryMembershipStates.ACTIVE)
            val active = (activeMembership, activeLib)
            if (libs.tail.length > 0) airbrake.notify(s"user $userId - duplicate active ownership of library kind ${kind} (ids: ${libs.tail.map(_._2.id.get)})")
            val otherLibs = libs.tail.map {
              case (a, l) =>
                val inactMem = libMem.find(_.libraryId == l.id.get)
                  .getOrElse(LibraryMembership(libraryId = activeLib.id.get, userId = userId, access = LibraryAccess.OWNER, showInSearch = true))
                  .copy(state = LibraryMembershipStates.INACTIVE)
                (inactMem, l.copy(state = LibraryStates.INACTIVE))
            }
            active +: otherLibs
        }.flatten.toList // force eval

      // Save changes
      sysLibs.map {
        case (mem, lib) =>
          libraryRepo.save(lib)
          libraryMembershipRepo.save(mem)
      }

      // If user is missing a system lib, create it
      val mainOpt = if (sysLibs.find(_._2.kind == LibraryKind.SYSTEM_MAIN).isEmpty) {
        val mainLib = libraryRepo.save(Library(name = "Main Library", ownerId = userId, visibility = LibraryVisibility.DISCOVERABLE, slug = LibrarySlug("main"), kind = LibraryKind.SYSTEM_MAIN, memberCount = 1))
        libraryMembershipRepo.save(LibraryMembership(libraryId = mainLib.id.get, userId = userId, access = LibraryAccess.OWNER, showInSearch = true))
        if (!generateNew) {
          airbrake.notify(s"${userId} missing main library")
        }
        searchClient.updateLibraryIndex()
        Some(mainLib)
      } else None

      val secretOpt = if (sysLibs.find(_._2.kind == LibraryKind.SYSTEM_SECRET).isEmpty) {
        val secretLib = libraryRepo.save(Library(name = "Secret Library", ownerId = userId, visibility = LibraryVisibility.SECRET, slug = LibrarySlug("secret"), kind = LibraryKind.SYSTEM_SECRET, memberCount = 1))
        libraryMembershipRepo.save(LibraryMembership(libraryId = secretLib.id.get, userId = userId, access = LibraryAccess.OWNER, showInSearch = true))
        if (!generateNew) {
          airbrake.notify(s"${userId} missing secret library")
        }
        searchClient.updateLibraryIndex()
        Some(secretLib)
      } else None

      val mainLib = sysLibs.find(_._2.kind == LibraryKind.SYSTEM_MAIN).map(_._2).orElse(mainOpt).get
      val secretLib = sysLibs.find(_._2.kind == LibraryKind.SYSTEM_SECRET).map(_._2).orElse(secretOpt).get
      (mainLib, secretLib)
    }
  }

  def inviteUsersToLibrary(libraryId: Id[Library], inviterId: Id[User], inviteList: Seq[(Either[Id[User], EmailAddress], LibraryAccess, Option[String])])(implicit eventContext: HeimdalContext): Either[LibraryFail, Seq[(Either[ExternalId[User], EmailAddress], LibraryAccess)]] = {
    val targetLib = db.readOnlyMaster { implicit s =>
      libraryRepo.get(libraryId)
    }
    if (!(targetLib.ownerId == inviterId || targetLib.visibility == LibraryVisibility.PUBLISHED))
      Left(LibraryFail("permission_denied"))
    else if (targetLib.kind == LibraryKind.SYSTEM_MAIN || targetLib.kind == LibraryKind.SYSTEM_SECRET)
      Left(LibraryFail("cant_invite_to_system_generated_library"))
    else {
      val successInvites = db.readOnlyMaster { implicit s =>
        for (i <- inviteList) yield {
          val (recipient, inviteAccess, msgOpt) = i
          // TODO (aaron): if non-owners invite that's not READ_ONLY, we need to change API to present "partial" failures
          val access = if (targetLib.ownerId != inviterId) LibraryAccess.READ_ONLY else inviteAccess // force READ_ONLY invites for non-owners
          val (inv, extId) = recipient match {
            case Left(id) =>
              (LibraryInvite(libraryId = libraryId, inviterId = inviterId, userId = Some(id), access = access, message = msgOpt), Left(userRepo.get(id).externalId))
            case Right(email) =>
              (LibraryInvite(libraryId = libraryId, inviterId = inviterId, emailAddress = Some(email), access = access, message = msgOpt), Right(email))
          }
          (inv, (extId, access))
        }
      }
      val (inv1, res) = successInvites.unzip
      inviteBulkUsers(inv1)

      trackLibraryInvitation(inviterId, libraryId, inviteList.map { _._1 }, eventContext, action = "sent")

      Right(res)
    }
  }

  def joinLibrary(userId: Id[User], libraryId: Id[Library])(implicit eventContext: HeimdalContext): Either[LibraryFail, Library] = {
    db.readWrite { implicit s =>
      val lib = libraryRepo.get(libraryId)
      val listInvites = libraryInviteRepo.getWithLibraryIdAndUserId(libraryId, userId)

      if (lib.kind == LibraryKind.SYSTEM_MAIN || lib.kind == LibraryKind.SYSTEM_SECRET)
        Left(LibraryFail("cant_join_system_generated_library"))
      else if (lib.visibility != LibraryVisibility.PUBLISHED && listInvites.isEmpty)
        Left(LibraryFail("cant_join_nonpublished_library"))
      else {
        val maxAccess = if (listInvites.isEmpty) LibraryAccess.READ_ONLY else listInvites.sorted.last.access
        libraryMembershipRepo.getOpt(userId, libraryId) match {
          case None =>
            libraryMembershipRepo.save(LibraryMembership(libraryId = libraryId, userId = userId, access = maxAccess, showInSearch = true))
          case Some(mem) =>
            libraryMembershipRepo.save(mem.copy(access = maxAccess, state = LibraryMembershipStates.ACTIVE, createdAt = DateTime.now()))
        }
        val updatedLib = libraryRepo.save(lib.copy(memberCount = libraryMembershipRepo.countWithLibraryId(libraryId)))
        listInvites.map(inv => libraryInviteRepo.save(inv.copy(state = LibraryInviteStates.ACCEPTED)))
        trackLibraryInvitation(userId, libraryId, Seq(), eventContext, action = "accepted")
        searchClient.updateLibraryIndex()
        Right(updatedLib)
      }
    }
  }

  def declineLibrary(userId: Id[User], libraryId: Id[Library]) = {
    db.readWrite { implicit s =>
      val listInvites = libraryInviteRepo.getWithLibraryIdAndUserId(libraryId = libraryId, userId = userId)
      listInvites.map(inv => libraryInviteRepo.save(inv.copy(state = LibraryInviteStates.DECLINED)))
    }
  }

  def leaveLibrary(libraryId: Id[Library], userId: Id[User]): Either[LibraryFail, Unit] = {
    db.readWrite { implicit s =>
      libraryMembershipRepo.getWithLibraryIdAndUserId(libraryId, userId) match {
        case None => Left(LibraryFail("membership_not_found"))
        case Some(mem) if mem.access == LibraryAccess.OWNER => Left(LibraryFail("cannot_leave_own_library"))
        case Some(mem) => {
          libraryMembershipRepo.save(mem.copy(state = LibraryMembershipStates.INACTIVE))
          val lib = libraryRepo.get(libraryId)
          libraryRepo.save(lib.copy(memberCount = libraryMembershipRepo.countWithLibraryId(libraryId)))
          searchClient.updateLibraryIndex()
          Right()
        }
      }
    }
  }

  // Return is Set of Keep -> error message
  private def applyToKeeps(userId: Id[User],
    dstLibraryId: Id[Library],
    keeps: Seq[Keep],
    excludeFromAccess: Set[LibraryAccess],
    saveKeep: (Keep, RWSession) => Either[LibraryError, Unit]): (Seq[Keep], Seq[(Keep, LibraryError)]) = {

    val badKeeps = collection.mutable.Set[(Keep, LibraryError)]()
    val goodKeeps = collection.mutable.Set[Keep]()
    val srcLibs = db.readWrite { implicit s =>
      val groupedKeeps = keeps.groupBy(_.libraryId)
      groupedKeeps.map {
        case (None, keeps) => keeps
        case (Some(fromLibraryId), keeps) =>
          libraryMembershipRepo.getWithLibraryIdAndUserId(fromLibraryId, userId) match {
            case None =>
              badKeeps ++= keeps.map(_ -> LibraryError.SourcePermissionDenied)
              Seq.empty[Keep]
            case Some(memFrom) if excludeFromAccess.contains(memFrom.access) =>
              badKeeps ++= keeps.map(_ -> LibraryError.SourcePermissionDenied)
              Seq.empty[Keep]
            case Some(_) =>
              keeps
          }
      }.flatten.foreach { keep =>
        saveKeep(keep, s) match {
          case Left(error) => badKeeps += keep -> error
          case _ => goodKeeps += keep
        }
      }
      searchClient.updateKeepIndex()
      if (badKeeps.size != keeps.size)
        libraryRepo.updateLastKept(dstLibraryId)
      groupedKeeps.keys.flatten
    }
    implicit val dca = TransactionalCaching.Implicits.directCacheAccess
    srcLibs.map { srcLibId =>
      countByLibraryCache.remove(CountByLibraryKey(srcLibId))
    }
    countByLibraryCache.remove(CountByLibraryKey(dstLibraryId))
    (goodKeeps.toSeq, badKeeps.toSeq)
  }

  def copyKeepsFromCollectionToLibrary(userId: Id[User], libraryId: Id[Library], tagName: Hashtag): Either[LibraryFail, (Seq[Keep], Seq[(Keep, LibraryError)])] = {
    db.readOnlyMaster { implicit s =>
      collectionRepo.getByUserAndName(userId, tagName)
    } match {
      case None =>
        Left(LibraryFail("tag_not_found"))
      case Some(tag) =>
        val keeps = db.readOnlyMaster { implicit s =>
          keepToCollectionRepo.getKeepsForTag(tag.id.get).map { kId => keepRepo.get(kId) }
        }
        Right(copyKeeps(userId, libraryId, keeps, withSource = Some(KeepSource.tagImport)))
    }
  }

  def moveKeepsFromCollectionToLibrary(userId: Id[User], libraryId: Id[Library], tagName: Hashtag): Either[LibraryFail, (Seq[Keep], Seq[(Keep, LibraryError)])] = {
    db.readOnlyMaster { implicit s =>
      collectionRepo.getByUserAndName(userId, tagName)
    } match {
      case None =>
        Left(LibraryFail("tag_not_found"))
      case Some(tag) =>
        val keeps = db.readOnlyMaster { implicit s =>
          keepToCollectionRepo.getKeepsForTag(tag.id.get).map { kId => keepRepo.get(kId) }
        }
        Right(moveKeeps(userId, libraryId, keeps))
    }
  }

  def copyKeeps(userId: Id[User], toLibraryId: Id[Library], keeps: Seq[Keep], withSource: Option[KeepSource]): (Seq[Keep], Seq[(Keep, LibraryError)]) = {
    val (toLibrary, memTo) = db.readOnlyMaster { implicit s =>
      val library = libraryRepo.get(toLibraryId)
      val memTo = libraryMembershipRepo.getWithLibraryIdAndUserId(toLibraryId, userId)
      (library, memTo)
    }
    memTo match {
      case v if v.isEmpty || v.get.access == LibraryAccess.READ_ONLY =>
        (Seq.empty[Keep], keeps.map(_ -> LibraryError.DestPermissionDenied))
      case Some(_) =>
        def saveKeep(k: Keep, s: RWSession): Either[LibraryError, Unit] = {
          implicit val session = s
          keepRepo.getPrimaryByUriAndLibrary(k.uriId, toLibraryId) match {
            case None =>
              val newKeep = keepRepo.save(Keep(title = k.title, uriId = k.uriId, url = k.url, urlId = k.urlId, visibility = toLibrary.visibility,
                userId = k.userId, source = withSource.getOrElse(k.source), libraryId = Some(toLibraryId), inDisjointLib = toLibrary.isDisjoint))
              combineTags(k.id.get, newKeep.id.get)
              Right()
            case Some(existingKeep) if existingKeep.state == KeepStates.INACTIVE =>
              keepRepo.save(existingKeep.copy(state = KeepStates.ACTIVE))
              combineTags(k.id.get, existingKeep.id.get)
              Right()
            case Some(existingKeep) =>
              combineTags(k.id.get, existingKeep.id.get)
              Left(LibraryError.AlreadyExistsInDest)
          }
        }
        applyToKeeps(userId, toLibraryId, keeps, Set(), saveKeep)
    }
  }

  def moveKeeps(userId: Id[User], toLibraryId: Id[Library], keeps: Seq[Keep]): (Seq[Keep], Seq[(Keep, LibraryError)]) = {
    val (toLibrary, memTo) = db.readOnlyMaster { implicit s =>
      val library = libraryRepo.get(toLibraryId)
      val memTo = libraryMembershipRepo.getWithLibraryIdAndUserId(toLibraryId, userId)
      (library, memTo)
    }
    memTo match {
      case v if v.isEmpty || v.get.access == LibraryAccess.READ_ONLY =>
        (Seq.empty[Keep], keeps.map(_ -> LibraryError.DestPermissionDenied))
      case Some(_) =>
        def saveKeep(k: Keep, s: RWSession): Either[LibraryError, Unit] = {
          implicit val session = s
          keepRepo.getPrimaryByUriAndLibrary(k.uriId, toLibraryId) match {
            case None =>
              keepRepo.save(k.copy(visibility = toLibrary.visibility, libraryId = Some(toLibraryId), inDisjointLib = toLibrary.isDisjoint, state = KeepStates.ACTIVE))
              Right()
            case Some(existingKeep) if existingKeep.state == KeepStates.INACTIVE =>
              keepRepo.save(existingKeep.copy(state = KeepStates.ACTIVE))
              keepRepo.save(k.copy(state = KeepStates.INACTIVE))
              combineTags(k.id.get, existingKeep.id.get)
              Right()
            case Some(existingKeep) =>
              if (toLibraryId != k.libraryId.get) {
                keepRepo.save(k.copy(state = KeepStates.INACTIVE))
                combineTags(k.id.get, existingKeep.id.get)
              }
              Left(LibraryError.AlreadyExistsInDest)
          }
        }
        applyToKeeps(userId, toLibraryId, keeps, Set(LibraryAccess.READ_ONLY, LibraryAccess.READ_INSERT), saveKeep)
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

  def getLibraryWithUsernameAndSlug(username: String, slug: LibrarySlug): Either[(Int, String), Library] = {
    val ownerOpt = db.readOnlyMaster { implicit s =>
      ExternalId.asOpt[User](username).flatMap(userRepo.getOpt).orElse {
        userRepo.getByUsername(Username(username))
      }
    }
    ownerOpt match {
      case None =>
        Left((BAD_REQUEST, "invalid_username"))
      case Some(owner) =>
        db.readOnlyMaster { implicit s =>
          libraryRepo.getBySlugAndUserId(userId = owner.id.get, slug = slug)
        } match {
          case None =>
            Left((NOT_FOUND, "no_library_found"))
          case Some(lib) =>
            Right(lib)
        }
    }
  }

  def getLibraryIdAndPassPhraseFromCookie(libraryAccessCookie: String): Option[(Id[Library], HashedPassPhrase)] = { /* cookie is in session, key: library_access */
    val a = libraryAccessCookie.split('/')
    (a.headOption, a.tail.headOption) match {
      case (Some(l), Some(p)) =>
        Library.decodePublicId(PublicId[Library](l)) match {
          case Success(lid) => Some((lid, HashedPassPhrase(p)))
          case _ => None
        }
      case _ => None
    }
  }

  private def trackLibraryInvitation(userId: Id[User], libId: Id[Library], inviteeList: Seq[(Either[Id[User], EmailAddress])], eventContext: HeimdalContext, action: String) = {
    val builder = contextBuilderFactory()
    builder.addExistingContext(eventContext)
    builder += ("action", action)
    builder += ("category", "libraryInvitation")
    builder += ("libraryId", libId.id)

    if (action == "sent") {
      builder += ("libraryOwnerId", userId.id)
      val numUsers = inviteeList.count(_.isLeft)
      val numEmails = inviteeList.size - numUsers
      builder += ("numUserInvited", numUsers)
      builder += ("numNonUserInvited", numEmails)
    }

    heimdal.trackEvent(UserEvent(userId, builder.build, UserEventTypes.INVITED))
  }

  def convertPendingInvites(emailAddress: EmailAddress, userId: Id[User]) = {
    db.readWrite { implicit s =>
      libraryInviteRepo.getByEmailAddress(emailAddress, Set.empty) foreach { libInv =>
        libraryInviteRepo.save(libInv.copy(userId = Some(userId)))
      }
    }
  }

}

sealed abstract class LibraryError(val message: String)
object LibraryError {
  case object SourcePermissionDenied extends LibraryError("source_permission_denied")
  case object DestPermissionDenied extends LibraryError("dest_permission_denied")
  case object AlreadyExistsInDest extends LibraryError("already_exists_in_dest")

  def apply(message: String): LibraryError = {
    message match {
      case SourcePermissionDenied.message => SourcePermissionDenied
      case DestPermissionDenied.message => DestPermissionDenied
      case AlreadyExistsInDest.message => AlreadyExistsInDest
    }
  }
}

case class LibraryFail(message: String) extends AnyVal

@json case class LibraryAddRequest(
  name: String,
  visibility: LibraryVisibility,
  description: Option[String] = None,
  slug: String,
  collaborators: Option[Seq[ExternalId[User]]],
  followers: Option[Seq[ExternalId[User]]])

case class LibraryInfo(
  id: PublicId[Library],
  name: String,
  visibility: LibraryVisibility,
  shortDescription: Option[String],
  url: String,
  owner: BasicUser,
  numKeeps: Int,
  numFollowers: Int,
  kind: LibraryKind,
  lastKept: Option[DateTime])
object LibraryInfo {
  implicit val libraryExternalIdFormat = ExternalId.format[Library]

  implicit val format = (
    (__ \ 'id).format[PublicId[Library]] and
    (__ \ 'name).format[String] and
    (__ \ 'visibility).format[LibraryVisibility] and
    (__ \ 'shortDescription).formatNullable[String] and
    (__ \ 'url).format[String] and
    (__ \ 'owner).format[BasicUser] and
    (__ \ 'numKeeps).format[Int] and
    (__ \ 'numFollowers).format[Int] and
    (__ \ 'kind).format[LibraryKind] and
    (__ \ 'lastKept).formatNullable[DateTime]
  )(LibraryInfo.apply, unlift(LibraryInfo.unapply))

  def fromLibraryAndOwner(lib: Library, owner: BasicUser, keepCount: Int)(implicit config: PublicIdConfiguration): LibraryInfo = {
    LibraryInfo(
      id = Library.publicId(lib.id.get),
      name = lib.name,
      visibility = lib.visibility,
      shortDescription = lib.description,
      url = Library.formatLibraryPath(owner.username, owner.externalId, lib.slug),
      owner = owner,
      numKeeps = keepCount,
      numFollowers = lib.memberCount - 1, // remove owner from count
      kind = lib.kind,
      lastKept = lib.lastKept
    )
  }

  val MaxDescriptionLength = 120
  def descriptionShortener(str: Option[String]): Option[String] = str match {
    case Some(s) => { Some(s.dropRight(s.length - MaxDescriptionLength)) } // will change later!
    case _ => None
  }
}

case class MaybeLibraryMember(member: Either[BasicUser, EmailAddress], access: Option[LibraryAccess], lastInvitedAt: Option[DateTime])

object MaybeLibraryMember {
  implicit val writes = Writes[MaybeLibraryMember] { member =>
    val identityFields = member.member match {
      case Left(user) => Json.toJson(user).as[JsObject]
      case Right(emailAddress) => Json.obj("email" -> emailAddress)
    }
    val libraryRelatedFields = Json.obj("membership" -> member.access, "lastInvitedAt" -> member.lastInvitedAt)
    json.minify(identityFields ++ libraryRelatedFields)
  }
}

case class FullLibraryInfo(
  id: PublicId[Library],
  name: String,
  visibility: LibraryVisibility,
  description: Option[String],
  slug: LibrarySlug,
  url: String,
  kind: LibraryKind,
  lastKept: Option[DateTime],
  owner: BasicUser,
  followers: Seq[BasicUser],
  keeps: Seq[KeepInfo],
  numKeeps: Int,
  numCollaborators: Int,
  numFollowers: Int)

object FullLibraryInfo {
  implicit val writes = Json.writes[FullLibraryInfo]
}

case class LibraryInfoIdKey(libraryId: Id[Library]) extends Key[LibraryInfo] {
  override val version = 1
  val namespace = "library_info_libraryid"
  def toKey(): String = libraryId.id.toString
}

class LibraryInfoIdCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends ImmutableJsonCacheImpl[LibraryInfoIdKey, LibraryInfo](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)
