package com.keepit.commanders

import com.google.inject.{ Provider, Inject }
import com.keepit.commanders.emails.{ LibraryInviteEmailSender, EmailOptOutCommander }
import com.keepit.common.akka.SafeFuture
import com.keepit.common.cache._
import com.keepit.common.crypto.{ PublicIdConfiguration, PublicId }
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.{ Id, ExternalId }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.{ AccessLog, Logging }
import com.keepit.common.mail.template.tags
import com.keepit.common.mail.{ BasicContact, ElectronicMail, EmailAddress }
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.store.{ ImageSize, S3ImageStore }
import com.keepit.common.time.Clock
import com.keepit.eliza.ElizaServiceClient
import com.keepit.heimdal.{ HeimdalContext, HeimdalServiceClient, HeimdalContextBuilderFactory, UserEvent, UserEventTypes }
import com.keepit.inject.FortyTwoConfig
import com.keepit.model.LibraryVisibility.PUBLISHED
import com.keepit.model._
import com.keepit.search.SearchServiceClient
import com.keepit.social.{ SocialNetworks, BasicUser }
import com.kifi.macros.json
import org.joda.time.DateTime
import play.api.http.Status._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.functional.syntax._
import play.api.libs.json._
import scala.concurrent._
import scala.concurrent.duration.Duration
import scala.util.Success
import com.keepit.common.json
import com.keepit.common.core._
import com.keepit.abook.ABookServiceClient
import com.keepit.abook.model.RichContact

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
    userRepo: UserRepo,
    userCommander: Provider[UserCommander],
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
    abookClient: ABookServiceClient,
    libraryAnalytics: LibraryAnalytics,
    libraryInviteSender: Provider[LibraryInviteEmailSender],
    heimdal: HeimdalServiceClient,
    contextBuilderFactory: HeimdalContextBuilderFactory,
    keepImageCommander: KeepImageCommander,
    libraryImageCommander: LibraryImageCommander,
    applicationConfig: FortyTwoConfig,
    uriSummaryCommander: URISummaryCommander,
    socialUserInfoRepo: SocialUserInfoRepo,
    experimentCommander: LocalUserExperimentCommander,
    systemValueRepo: SystemValueRepo,
    implicit val publicIdConfig: PublicIdConfiguration,
    clock: Clock) extends Logging {

  def libraryMetaTags(library: Library): Future[PublicPageMetaTags] = {
    db.readOnlyMasterAsync { implicit s =>
      val owner = userRepo.get(library.ownerId)
      val urlPathOnly = Library.formatLibraryPath(owner.username, owner.externalId, library.slug)
      if (library.visibility != PUBLISHED) {
        PublicPageMetaPrivateTags(urlPathOnly)
      } else {
        val facebookId: Option[String] = socialUserInfoRepo.getByUser(owner.id.get).filter(i => i.networkType == SocialNetworks.FACEBOOK).map(_.socialId.id).headOption

        val keeps = keepRepo.getByLibrary(library.id.get, 0, 50)

        //facebook OG recommends:
        //We suggest that you use an image of at least 1200x630 pixels.
        val imageUrls: Seq[String] = {
          val images: Seq[KeepImage] = keepImageCommander.getBestImagesForKeeps(keeps.map(_.id.get).toSet, ProcessedImageSize.XLarge.idealSize).values.flatten.toSeq
          val sorted: Seq[KeepImage] = images.sortWith {
            case (image1, image2) =>
              (image1.imageSize.width * image1.imageSize.height) > (image2.imageSize.width * image2.imageSize.height)
          }
          val urls: Seq[String] = sorted.take(10) map { image =>
            val url = keepImageCommander.getUrl(image)
            if (url.startsWith("http:") || url.startsWith("https:")) url else s"http:$url"
          }
          //last image is the kifi image we want to append to all image lists
          if (urls.isEmpty) Seq("https://djty7jcqog9qu.cloudfront.net/assets/fbc1200X630.png") else urls
        }

        val url = {
          val fullUrl = s"${applicationConfig.applicationBaseUrl}$urlPathOnly"
          if (fullUrl.startsWith("http") || fullUrl.startsWith("https:")) fullUrl else s"http:$fullUrl"
        }

        val lowQualityLibrary: Boolean = {
          keeps.size <= 3 || ((library.description.isEmpty || library.description.get.length <= 10) && keeps.size <= 6)
        }

        PublicPageMetaFullTags(
          unsafeTitle = s"${library.name} by ${owner.firstName} ${owner.lastName} \u2022 Kifi",
          url = url,
          urlPathOnly = urlPathOnly,
          unsafeDescription = PublicPageMetaTags.generateMetaTagsDescription(library.description, owner.fullName, library.name),
          images = imageUrls,
          facebookId = facebookId,
          createdAt = library.createdAt,
          updatedAt = library.updatedAt,
          unsafeFirstName = owner.firstName,
          unsafeLastName = owner.lastName,
          noIndex = lowQualityLibrary)
      }
    }
  }

  def getKeeps(libraryId: Id[Library], offset: Int, limit: Int): Future[Seq[Keep]] = {
    if (limit > 0) db.readOnlyReplicaAsync { implicit s => keepRepo.getByLibrary(libraryId, offset, limit) }
    else Future.successful(Seq.empty)
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

  def getLibraryById(userIdOpt: Option[Id[User]], showPublishedLibraries: Boolean, id: Id[Library], imageSize: ImageSize): Future[(FullLibraryInfo, String)] = {
    val lib = db.readOnlyMaster { implicit s => libraryRepo.get(id) }
    createFullLibraryInfo(userIdOpt, showPublishedLibraries, lib, imageSize).map { libInfo =>
      val accessStr = userIdOpt.flatMap(getAccessStr(_, id)) getOrElse "none"
      (libInfo, accessStr)
    }
  }

  def getLibrarySummaries(libraryIds: Seq[Id[Library]]): Seq[LibraryInfo] = {
    db.readOnlyReplica { implicit session =>
      val librariesById = libraryRepo.getLibraries(libraryIds.toSet)
      val ownersById = basicUserRepo.loadAll(librariesById.values.map(_.ownerId).toSet)
      val keepCountsByLibraryId = keepRepo.getCountsByLibrary(libraryIds.toSet).withDefaultValue(0)
      libraryIds.map { libId =>
        val library = librariesById(libId)
        val owner = ownersById(library.ownerId)
        val keepCount = keepCountsByLibraryId(libId)
        LibraryInfo.fromLibraryAndOwner(library, owner, keepCount, None)
      }
    }
  }

  def getBasicLibraryStatistics(libraryIds: Set[Id[Library]]): Map[Id[Library], BasicLibraryStatistics] = {
    db.readOnlyReplica { implicit session =>
      val memberCountByLibraryId = libraryRepo.getLibraries(libraryIds).mapValues(_.memberCount)
      val keepCountsByLibraryId = keepRepo.getCountsByLibrary(libraryIds).withDefaultValue(0)
      libraryIds.map { libId => libId -> BasicLibraryStatistics(memberCountByLibraryId(libId), keepCountsByLibraryId(libId)) }.toMap
    }
  }

  def getLibrarySummaryAndAccessString(userIdOpt: Option[Id[User]], id: Id[Library]): (LibraryInfo, String) = {
    val Seq(libInfo) = getLibrarySummaries(Seq(id))
    val accessStr = userIdOpt.flatMap(getAccessStr(_, id)) getOrElse "none"
    (libInfo, accessStr)
  }

  def getLibraryWithOwnerAndCounts(libraryId: Id[Library], viewerUserId: Id[User]): Either[LibraryFail, (Library, BasicUser, Int, Int, Option[Boolean])] = {
    db.readOnlyReplica { implicit s =>
      val library = libraryRepo.get(libraryId)
      val mine = library.ownerId == viewerUserId
      val following = if (mine) None else Some(libraryMembershipRepo.getWithLibraryIdAndUserId(libraryId, viewerUserId).isDefined)
      if (library.visibility == LibraryVisibility.PUBLISHED || mine || following.get) {
        val owner = basicUserRepo.load(library.ownerId)
        val keepCount = keepRepo.getCountByLibrary(library.id.get)
        val followerCount = libraryMembershipRepo.countWithLibraryIdByAccess(library.id.get).apply(LibraryAccess.READ_ONLY)
        Right(library, owner, keepCount, followerCount, following)
      } else {
        Left(LibraryFail(FORBIDDEN, "library_access_denied"))
      }
    }
  }

  def createFullLibraryInfos(viewerUserIdOpt: Option[Id[User]], showPublishedLibraries: Boolean, maxMembersShown: Int, maxKeepsShown: Int, idealKeepImageSize: ImageSize, libraries: Seq[Library], idealLibraryImageSize: ImageSize): Future[Seq[FullLibraryInfo]] = {
    val futureKeepInfosByLibraryId = libraries.map { library =>
      library.id.get -> {
        if (maxKeepsShown > 0) {
          val keeps = db.readOnlyMaster { implicit session => keepRepo.getByLibrary(library.id.get, 0, maxKeepsShown) }
          keepsCommanderProvider.get.decorateKeepsIntoKeepInfos(viewerUserIdOpt, showPublishedLibraries, keeps, idealKeepImageSize)
        } else Future.successful(Seq.empty)
      }
    }.toMap

    val followerInfosByLibraryId = libraries.map { library =>
      val (collaborators, followers, _, counts) = getLibraryMembers(library.id.get, 0, maxMembersShown, fillInWithInvites = false)
      val inviters: Seq[LibraryMembership] = viewerUserIdOpt.map { userId =>
        db.readOnlyReplica { implicit session =>
          libraryInviteRepo.getWithLibraryIdAndUserId(library.id.get, userId).filter { invite =>
            invite.inviterId != library.ownerId
          }.map { invite =>
            libraryMembershipRepo.getWithLibraryIdAndUserId(library.id.get, invite.inviterId)
          }
        }.flatten
      }.getOrElse(Seq.empty)
      library.id.get -> ((inviters ++ collaborators.filter(!inviters.contains(_)) ++ followers.filter(!inviters.contains(_))).map(_.userId), counts)
    }.toMap

    val usersById = {
      val allUsersShown = libraries.flatMap { library => followerInfosByLibraryId(library.id.get)._1 :+ library.ownerId }.toSet
      db.readOnlyReplica { implicit s => basicUserRepo.loadAll(allUsersShown) }
    }

    val keepCountsByLibraries = db.readOnlyReplica { implicit s =>
      keepRepo.getCountsByLibrary(libraries.map(_.id.get).toSet)
    }

    val countsByLibraryId = libraries.map { library =>
      val counts = followerInfosByLibraryId(library.id.get)._2
      val collaboratorCount = counts(LibraryAccess.READ_WRITE)
      val followerCount = counts(LibraryAccess.READ_INSERT) + counts(LibraryAccess.READ_ONLY)
      val keepCount = keepCountsByLibraries.getOrElse(library.id.get, 0)
      library.id.get -> (collaboratorCount, followerCount, keepCount)
    }.toMap

    val futureFullLibraryInfos = libraries.map { lib =>
      futureKeepInfosByLibraryId(lib.id.get).map { keepInfos =>
        val (collaboratorCount, followerCount, keepCount) = countsByLibraryId(lib.id.get)
        val owner = usersById(lib.ownerId)
        val followers = followerInfosByLibraryId(lib.id.get)._1.map(usersById(_))
        val libImageOpt = libraryImageCommander.getBestImageForLibrary(lib.id.get, idealLibraryImageSize)
        FullLibraryInfo(
          id = Library.publicId(lib.id.get),
          name = lib.name,
          owner = owner,
          description = lib.description,
          slug = lib.slug,
          url = Library.formatLibraryPath(owner.username, owner.externalId, lib.slug),
          color = lib.color,
          kind = lib.kind,
          visibility = lib.visibility,
          image = libImageOpt.map(LibraryImageInfo.createInfo(_)),
          followers = followers,
          keeps = keepInfos,
          numKeeps = keepCount,
          numCollaborators = collaboratorCount,
          numFollowers = followerCount,
          lastKept = lib.lastKept
        )
      }
    }
    Future.sequence(futureFullLibraryInfos)
  }

  def createFullLibraryInfo(viewerUserIdOpt: Option[Id[User]], showPublishedLibraries: Boolean, library: Library, libImageSize: ImageSize): Future[FullLibraryInfo] = {
    createFullLibraryInfos(viewerUserIdOpt, showPublishedLibraries, 10, 10, ProcessedImageSize.Large.idealSize, Seq(library), libImageSize).imap { case Seq(fullLibraryInfo) => fullLibraryInfo }
  }

  def getLibraryMembers(libraryId: Id[Library], offset: Int, limit: Int, fillInWithInvites: Boolean): (Seq[LibraryMembership], Seq[LibraryMembership], Seq[(Either[Id[User], EmailAddress], Set[LibraryInvite])], Map[LibraryAccess, Int]) = {
    val collaboratorsAccess: Set[LibraryAccess] = Set(LibraryAccess.READ_INSERT, LibraryAccess.READ_WRITE)
    val followersAccess: Set[LibraryAccess] = Set(LibraryAccess.READ_ONLY)
    val relevantInviteStates = Set(LibraryInviteStates.ACTIVE)

    val memberCount = db.readOnlyMaster { implicit s => libraryMembershipRepo.countWithLibraryIdByAccess(libraryId) }

    if (limit > 0) db.readOnlyMaster { implicit session =>
      // Get Collaborators
      val collaborators = libraryMembershipRepo.pageWithLibraryIdAndAccess(libraryId, offset, limit, collaboratorsAccess)
      val collaboratorsShown = collaborators.length

      val numCollaborators = memberCount(LibraryAccess.READ_INSERT) + memberCount(LibraryAccess.READ_WRITE)
      val numMembers = numCollaborators + memberCount(LibraryAccess.READ_ONLY)

      // Get Followers
      val followersLimit = limit - collaboratorsShown
      val followers = if (followersLimit == 0) Seq.empty[LibraryMembership] else {
        val followersOffset = if (collaboratorsShown > 0) 0 else {
          val collaboratorsTotal = numCollaborators
          offset - collaboratorsTotal
        }
        libraryMembershipRepo.pageWithLibraryIdAndAccess(libraryId, followersOffset, followersLimit, followersAccess)
      }

      // Get Invitees with Invites
      val membersShown = collaborators.length + followers.length
      val inviteesLimit = limit - membersShown
      val inviteesWithInvites = if (inviteesLimit == 0 || !fillInWithInvites) Seq.empty[(Either[Id[User], EmailAddress], Set[LibraryInvite])] else {
        val inviteesOffset = if (membersShown > 0) 0 else {
          val membersTotal = numMembers
          offset - membersTotal
        }
        libraryInviteRepo.pageInviteesByLibraryId(libraryId, inviteesOffset, inviteesLimit, relevantInviteStates)
      }
      (collaborators, followers, inviteesWithInvites, memberCount)
    }
    else (Seq.empty, Seq.empty, Seq.empty, memberCount)
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
      if (libAddReq.name.isEmpty || !Library.isValidName(libAddReq.name)) { Some("invalid_name") }
      else if (libAddReq.slug.isEmpty || !LibrarySlug.isValidSlug(libAddReq.slug)) { Some("invalid_slug") }
      else { None }
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
            val library = db.readWrite { implicit s =>
              libraryAliasRepo.reclaim(ownerId, validSlug)
              libraryRepo.getOpt(ownerId, validSlug) match {
                case None =>
                  val lib = libraryRepo.save(Library(ownerId = ownerId, name = libAddReq.name, description = libAddReq.description,
                    visibility = libAddReq.visibility, slug = validSlug, color = libAddReq.color, kind = LibraryKind.USER_CREATED, memberCount = 1))
                  libraryMembershipRepo.save(LibraryMembership(libraryId = lib.id.get, userId = ownerId, access = LibraryAccess.OWNER, showInSearch = true, visibility = LibraryMembershipVisibilityStates.VISIBLE))
                  lib
                case Some(lib) =>
                  val newLib = libraryRepo.save(lib.copy(state = LibraryStates.ACTIVE))
                  libraryMembershipRepo.getWithLibraryIdAndUserId(libraryId = lib.id.get, userId = ownerId, None) match {
                    case None => libraryMembershipRepo.save(LibraryMembership(libraryId = lib.id.get, userId = ownerId, access = LibraryAccess.OWNER, showInSearch = true, visibility = LibraryMembershipVisibilityStates.VISIBLE))
                    case Some(mem) => libraryMembershipRepo.save(mem.copy(state = LibraryMembershipStates.ACTIVE))
                  }
                  newLib
              }
            }
            libraryAnalytics.createLibrary(ownerId, library, context)
            searchClient.updateLibraryIndex()
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

  def modifyLibrary(libraryId: Id[Library], userId: Id[User],
    name: Option[String] = None,
    description: Option[String] = None,
    slug: Option[String] = None,
    visibility: Option[LibraryVisibility] = None,
    color: Option[HexColor] = None): Either[LibraryFail, Library] = {

    val targetLib = db.readOnlyMaster { implicit s => libraryRepo.get(libraryId) }
    if (targetLib.ownerId != userId) {
      Left(LibraryFail(FORBIDDEN, "permission_denied"))
    } else {
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
        newName <- validName(name).right
        newSlug <- validSlug(slug).right
      } yield {
        val newDescription: Option[String] = description.orElse(targetLib.description)
        val newVisibility: LibraryVisibility = visibility.getOrElse(targetLib.visibility)
        val newColor: Option[HexColor] = color.orElse(targetLib.color)
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
          if (targetLib.slug != newSlug) {
            val ownerId = targetLib.ownerId
            libraryAliasRepo.reclaim(ownerId, newSlug)
            libraryAliasRepo.alias(ownerId, targetLib.slug, targetLib.id.get)
          }
          libraryRepo.save(targetLib.copy(name = newName, slug = newSlug, visibility = newVisibility, description = newDescription, color = newColor, state = LibraryStates.ACTIVE))
        }
      }
      searchClient.updateLibraryIndex()
      result
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
            libraryMembershipRepo.getWithLibraryIdAndUserId(library.id.get, id).nonEmpty ||
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

  def processInvites(invites: Seq[LibraryInvite]): Future[Seq[ElectronicMail]] = {
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
            libraryInviteSender.get.sendInvite(invite)
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
            if (membership.isEmpty) airbrake.notify(s"user $userId - non-existing ownership of library kind $kind (id: ${activeLib.id.get})")
            val activeMembership = membership.getOrElse(LibraryMembership(libraryId = activeLib.id.get, userId = userId, access = LibraryAccess.OWNER, showInSearch = true, visibility = LibraryMembershipVisibilityStates.VISIBLE)).copy(state = LibraryMembershipStates.ACTIVE)
            val active = (activeMembership, activeLib)
            if (libs.tail.length > 0) airbrake.notify(s"user $userId - duplicate active ownership of library kind $kind (ids: ${libs.tail.map(_._2.id.get)})")
            val otherLibs = libs.tail.map {
              case (a, l) =>
                val inactMem = libMem.find(_.libraryId == l.id.get)
                  .getOrElse(LibraryMembership(libraryId = activeLib.id.get, userId = userId, access = LibraryAccess.OWNER, showInSearch = true, visibility = LibraryMembershipVisibilityStates.VISIBLE))
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
        libraryMembershipRepo.save(LibraryMembership(libraryId = mainLib.id.get, userId = userId, access = LibraryAccess.OWNER, showInSearch = true, visibility = LibraryMembershipVisibilityStates.VISIBLE))
        if (!generateNew) {
          airbrake.notify(s"$userId missing main library")
        }
        searchClient.updateLibraryIndex()
        Some(mainLib)
      } else None

      val secretOpt = if (sysLibs.find(_._2.kind == LibraryKind.SYSTEM_SECRET).isEmpty) {
        val secretLib = libraryRepo.save(Library(name = "Secret Library", ownerId = userId, visibility = LibraryVisibility.SECRET, slug = LibrarySlug("secret"), kind = LibraryKind.SYSTEM_SECRET, memberCount = 1))
        libraryMembershipRepo.save(LibraryMembership(libraryId = secretLib.id.get, userId = userId, access = LibraryAccess.OWNER, showInSearch = true, visibility = LibraryMembershipVisibilityStates.VISIBLE))
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

  def inviteUsersToLibrary(libraryId: Id[Library], inviterId: Id[User], inviteList: Seq[(Either[Id[User], EmailAddress], LibraryAccess, Option[String])])(implicit eventContext: HeimdalContext): Future[Either[LibraryFail, Seq[(Either[BasicUser, RichContact], LibraryAccess)]]] = {
    val targetLib = db.readOnlyMaster { implicit s =>
      libraryRepo.get(libraryId)
    }
    if (!(targetLib.ownerId == inviterId || targetLib.visibility == LibraryVisibility.PUBLISHED))
      Future.successful(Left(LibraryFail(FORBIDDEN, "permission_denied")))
    else if (targetLib.kind == LibraryKind.SYSTEM_MAIN || targetLib.kind == LibraryKind.SYSTEM_SECRET)
      Future.successful(Left(LibraryFail(BAD_REQUEST, "cant_invite_to_system_generated_library")))
    else {
      val futureInvitedContactsByEmailAddress = {
        val invitedEmailAddresses = inviteList.collect { case (Right(emailAddress), _, _) => emailAddress }
        abookClient.internKifiContacts(inviterId, invitedEmailAddresses.map(BasicContact(_)): _*).imap { kifiContacts =>
          (invitedEmailAddresses zip kifiContacts).toMap
        }
      }

      val invitedBasicUsersById = {
        val invitedUserIds = inviteList.collect { case (Left(userId), _, _) => userId }
        db.readOnlyMaster { implicit s => basicUserRepo.loadAll(invitedUserIds.toSet) }
      }

      futureInvitedContactsByEmailAddress.map { invitedContactsByEmailAddress =>
        val invitesAndInvitees = db.readOnlyMaster { implicit s =>
          for ((recipient, inviteAccess, msgOpt) <- inviteList) yield {
            val access = if (targetLib.ownerId != inviterId) LibraryAccess.READ_ONLY else inviteAccess
            recipient match {
              case Left(userId) =>
                libraryMembershipRepo.getWithLibraryIdAndUserId(libraryId, userId) match {
                  case Some(mem) if mem.access == access =>
                    None
                  case _ =>
                    val newInvite = LibraryInvite(libraryId = libraryId, inviterId = inviterId, userId = Some(userId), access = access, message = msgOpt)
                    val inviteeInfo = (Left(invitedBasicUsersById(userId)), access)
                    Some(newInvite, inviteeInfo)
                }
              case Right(email) =>
                val newInvite = LibraryInvite(libraryId = libraryId, inviterId = inviterId, emailAddress = Some(email), access = access, message = msgOpt)
                val inviteeInfo = (Right(invitedContactsByEmailAddress(email)), access)
                Some(newInvite, inviteeInfo)
            }
          }
        }
        val (invites, inviteesWithAccess) = invitesAndInvitees.flatten.unzip
        processInvites(invites)

        libraryAnalytics.sendLibraryInvite(inviterId, libraryId, inviteList.map { _._1 }, eventContext)

        Right(inviteesWithAccess)
      }
    }
  }

  def notifyOwnerOfNewFollower(newFollowerId: Id[User], lib: Library): Unit = {
    val (follower, owner) = db.readOnlyReplica { implicit session =>
      userRepo.get(newFollowerId) -> userRepo.get(lib.ownerId)
    }
    elizaClient.sendGlobalNotification(
      userIds = Set(lib.ownerId),
      title = "New Library Follower",
      body = s"${follower.firstName} ${follower.lastName} is now following your Library ${lib.name}",
      linkText = "Go to Library",
      linkUrl = "https://kifi.com" + Library.formatLibraryPath(owner.username, owner.externalId, lib.slug),
      imageUrl = s3ImageStore.avatarUrlByUser(follower),
      sticky = false,
      category = NotificationCategory.User.LIBRARY_FOLLOWED
    )
  }

  def notifyFollowersOfNewKeeps(library: Library, newKeeps: Keep*): Unit = {
    newKeeps.foreach { newKeep =>
      if (newKeep.libraryId.get != library.id.get) { throw new IllegalArgumentException(s"Keep ${newKeep.id.get} does not belong to expected library ${library.id.get}") }
    }
    val (relevantFollowers, usersById) = db.readOnlyReplica { implicit session =>
      val relevantFollowers = libraryMembershipRepo.getWithLibraryId(library.id.get).map(_.userId).filter(experimentCommander.userHasExperiment(_, ExperimentType.NEW_KEEP_NOTIFICATIONS)).toSet
      val usersById = userRepo.getUsers(newKeeps.map(_.userId) :+ library.ownerId)
      (relevantFollowers, usersById)
    }
    val owner = usersById(library.ownerId)
    newKeeps.foreach { newKeep =>
      val toBeNotified = relevantFollowers - newKeep.userId
      if (toBeNotified.nonEmpty) {
        val keeper = usersById(newKeep.userId)
        elizaClient.sendGlobalNotification(
          userIds = toBeNotified,
          title = s"New Keep in ${library.name}",
          body = s"${keeper.firstName} has just kept ${newKeep.title.getOrElse("a new item")}",
          linkText = "Go to Library",
          linkUrl = "https://kifi.com" + Library.formatLibraryPath(owner.username, owner.externalId, library.slug),
          imageUrl = s3ImageStore.avatarUrlByUser(keeper),
          sticky = false,
          category = NotificationCategory.User.NEW_KEEP
        )
      }
    }
  }

  def joinLibrary(userId: Id[User], libraryId: Id[Library])(implicit eventContext: HeimdalContext): Either[LibraryFail, Library] = {
    db.readWrite { implicit s =>
      val lib = libraryRepo.get(libraryId)
      val listInvites = libraryInviteRepo.getWithLibraryIdAndUserId(libraryId, userId)

      if (lib.kind == LibraryKind.SYSTEM_MAIN || lib.kind == LibraryKind.SYSTEM_SECRET)
        Left(LibraryFail(FORBIDDEN, "cant_join_system_generated_library"))
      else if (lib.visibility != LibraryVisibility.PUBLISHED && listInvites.isEmpty)
        Left(LibraryFail(FORBIDDEN, "cant_join_nonpublished_library"))
      else {
        val maxAccess = if (listInvites.isEmpty) LibraryAccess.READ_ONLY else listInvites.sorted.last.access
        libraryMembershipRepo.getWithLibraryIdAndUserId(libraryId, userId, None) match {
          case None =>
            libraryMembershipRepo.save(LibraryMembership(libraryId = libraryId, userId = userId, access = maxAccess, showInSearch = true, visibility = LibraryMembershipVisibilityStates.VISIBLE))
            notifyOwnerOfNewFollower(userId, lib)
          case Some(mem) =>
            val maxWithExisting = (maxAccess :: mem.access :: Nil).sorted.last
            libraryMembershipRepo.save(mem.copy(access = maxWithExisting, state = LibraryMembershipStates.ACTIVE))
        }
        val updatedLib = libraryRepo.save(lib.copy(memberCount = libraryMembershipRepo.countWithLibraryId(libraryId)))
        listInvites.map(inv => libraryInviteRepo.save(inv.copy(state = LibraryInviteStates.ACCEPTED)))

        val keepCount = keepRepo.getCountByLibrary(libraryId)
        libraryAnalytics.acceptLibraryInvite(userId, libraryId, eventContext)
        libraryAnalytics.followLibrary(userId, lib, keepCount, eventContext)
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

  def leaveLibrary(libraryId: Id[Library], userId: Id[User])(implicit eventContext: HeimdalContext): Either[LibraryFail, Unit] = {
    db.readWrite { implicit s =>
      libraryMembershipRepo.getWithLibraryIdAndUserId(libraryId, userId, None) match {
        case None => Right()
        case Some(mem) if mem.access == LibraryAccess.OWNER => Left(LibraryFail(BAD_REQUEST, "cannot_leave_own_library"))
        case Some(mem) => {
          libraryMembershipRepo.save(mem.copy(state = LibraryMembershipStates.INACTIVE))
          val lib = libraryRepo.get(libraryId)
          libraryRepo.save(lib.copy(memberCount = libraryMembershipRepo.countWithLibraryId(libraryId)))

          val keepCount = keepRepo.getCountByLibrary(libraryId)
          libraryAnalytics.unfollowLibrary(userId, lib, keepCount, eventContext)
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
    saveKeep: (Keep, RWSession) => Either[LibraryError, Keep]): (Seq[Keep], Seq[(Keep, LibraryError)]) = {

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
          case Right(successKeep) => goodKeeps += successKeep
        }
      }
      if (badKeeps.size != keeps.size)
        libraryRepo.updateLastKept(dstLibraryId)
      groupedKeeps.keys.flatten
    }
    searchClient.updateKeepIndex()

    implicit val dca = TransactionalCaching.Implicits.directCacheAccess
    srcLibs.map { srcLibId =>
      countByLibraryCache.remove(CountByLibraryKey(srcLibId))
    }
    countByLibraryCache.remove(CountByLibraryKey(dstLibraryId))
    (goodKeeps.toSeq, badKeeps.toSeq)
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
                userId = userId, source = withSource.getOrElse(k.source), libraryId = Some(toLibraryId), inDisjointLib = toLibrary.isDisjoint))
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
        libraryAnalytics.editLibrary(userId, toLibrary, context, Some("copy_keeps"))
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
        libraryAnalytics.editLibrary(userId, toLibrary, context, Some("move_keeps"))
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

  def getLibraryWithUsernameAndSlug(username: String, slug: LibrarySlug, followRedirect: Boolean = false): Either[LibraryFail, Library] = {
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
            if ((isUserAlias || isLibraryAlias) && !followRedirect) Left(LibraryFail(MOVED_PERMANENTLY, Library.formatLibraryPath(owner.username, owner.externalId, library.slug)))
            else Right(library)
        }
    }
  }

  def getLibraryBySlugOrAlias(ownerId: Id[User], slug: LibrarySlug): Option[(Library, Boolean)] = {
    db.readOnlyMaster { implicit session =>
      libraryRepo.getBySlugAndUserId(ownerId, slug).map((_, false)) orElse
        libraryAliasRepo.getByOwnerIdAndSlug(ownerId, slug).map(alias => (libraryRepo.get(alias.libraryId), true)).filter(_._1.state == LibraryStates.ACTIVE)
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

  def getMarketingSiteSuggestedLibraries(): Future[Seq[MarketingSuggestedLibraryInfo]] = {
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
      ).map(value => (value.id, value)).toMap

      val libIds = systemValueLibraries.keySet
      val libPublicIdsToIds = libIds.map { id => (Library.publicId(id), id) }.toMap
      val libIdsMap = db.readOnlyReplica { implicit s => libraryRepo.getLibraries(libIds) } filter {
        case (_, lib) => lib.visibility == LibraryVisibility.PUBLISHED
      }

      val fullLibInfosF = createFullLibraryInfos(viewerUserIdOpt = None,
        showPublishedLibraries = true,
        maxMembersShown = 0,
        maxKeepsShown = 0,
        idealKeepImageSize = ProcessedImageSize.Medium.idealSize,
        libraries = libIdsMap.values.toSeq,
        idealLibraryImageSize = ProcessedImageSize.Medium.idealSize)

      fullLibInfosF map { libInfos =>
        libInfos map { info =>
          val extraInfo = systemValueLibraries.get(libPublicIdsToIds(info.id))
          MarketingSuggestedLibraryInfo.fromFullLibraryInfo(info, extraInfo)
        }
      }
    } getOrElse Future.successful(Seq.empty)
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

  /**
   * 1. non user: number of public libraries that are “displayable on profile” (see library pref) plus libraries i follow that are public
   * 2. my own profile view: total number of libraries I own and I follow, including main and secret, not including pending invites to libs
   * 3. logged in user viewing another’s profile: Everything in 1 (above) + libraries user has access to (even if private)
   */
  def countLibraries(userId: Id[User], viewer: Option[Id[User]]): Int = viewer match {
    case None => countLibrariesForAnonymous(userId)
    case Some(id) if id == userId => countLibrariesForSelf(userId)
    case Some(id) => countLibrariesForOtherUser(userId, id)
  }

  private def countLibrariesForOtherUser(userId: Id[User], friendId: Id[User]): Int = {
    db.readOnlyReplica { implicit s =>
      val showFollowLibraries = true
      libraryMembershipRepo.countLibrariesForOtherUser(userId, friendId, countFollowLibraries = showFollowLibraries)
    }
  }

  private def countLibrariesForSelf(userId: Id[User]): Int = {
    db.readOnlyReplica { implicit s =>
      libraryMembershipRepo.countLibrariesToSelf(userId)
    }
  }

  private def countLibrariesForAnonymous(userId: Id[User]): Int = {
    db.readOnlyReplica { implicit s =>
      val showFollowLibraries = true
      libraryMembershipRepo.countLibrariesOfUserFromAnonymos(userId, countFollowLibraries = showFollowLibraries)
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

case class LibraryFail(status: Int, message: String)

@json case class LibraryAddRequest(
  name: String,
  visibility: LibraryVisibility,
  description: Option[String] = None,
  slug: String,
  color: Option[HexColor] = None)

case class LibraryInfo(
  id: PublicId[Library],
  name: String,
  visibility: LibraryVisibility,
  shortDescription: Option[String],
  url: String,
  color: Option[HexColor] = None,
  owner: BasicUser,
  numKeeps: Int,
  numFollowers: Int,
  kind: LibraryKind,
  lastKept: Option[DateTime],
  inviter: Option[BasicUser])
object LibraryInfo {
  implicit val libraryExternalIdFormat = ExternalId.format[Library]

  implicit val format = (
    (__ \ 'id).format[PublicId[Library]] and
    (__ \ 'name).format[String] and
    (__ \ 'visibility).format[LibraryVisibility] and
    (__ \ 'shortDescription).formatNullable[String] and
    (__ \ 'url).format[String] and
    (__ \ 'color).formatNullable[HexColor] and
    (__ \ 'owner).format[BasicUser] and
    (__ \ 'numKeeps).format[Int] and
    (__ \ 'numFollowers).format[Int] and
    (__ \ 'kind).format[LibraryKind] and
    (__ \ 'lastKept).formatNullable[DateTime] and
    (__ \ 'inviter).formatNullable[BasicUser]
  )(LibraryInfo.apply, unlift(LibraryInfo.unapply))

  def fromLibraryAndOwner(lib: Library, owner: BasicUser, keepCount: Int, inviter: Option[BasicUser])(implicit config: PublicIdConfiguration): LibraryInfo = {
    LibraryInfo(
      id = Library.publicId(lib.id.get),
      name = lib.name,
      visibility = lib.visibility,
      shortDescription = lib.description,
      url = Library.formatLibraryPath(owner.username, owner.externalId, lib.slug),
      color = lib.color,
      owner = owner,
      numKeeps = keepCount,
      numFollowers = lib.memberCount - 1, // remove owner from count
      kind = lib.kind,
      lastKept = lib.lastKept,
      inviter = inviter
    )
  }

  val MaxDescriptionLength = 120
  def descriptionShortener(str: Option[String]): Option[String] = str match {
    case Some(s) => { Some(s.dropRight(s.length - MaxDescriptionLength)) } // will change later!
    case _ => None
  }

}

case class MaybeLibraryMember(member: Either[BasicUser, BasicContact], access: Option[LibraryAccess], lastInvitedAt: Option[DateTime])

object MaybeLibraryMember {
  implicit val writes = Writes[MaybeLibraryMember] { member =>
    val identityFields = member.member.fold(user => Json.toJson(user), contact => Json.toJson(contact)).as[JsObject]
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
  color: Option[HexColor] = None,
  image: Option[LibraryImageInfo] = None,
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
  override val version = 2
  val namespace = "library_info_libraryid"
  def toKey(): String = libraryId.id.toString
}

class LibraryInfoIdCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends ImmutableJsonCacheImpl[LibraryInfoIdKey, LibraryInfo](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

@json case class MarketingSuggestedLibraryInfo(
  id: PublicId[Library],
  name: String,
  caption: Option[String],
  url: String,
  image: Option[LibraryImageInfo] = None,
  owner: BasicUser,
  numKeeps: Int,
  numFollowers: Int,
  color: Option[HexColor])

object MarketingSuggestedLibraryInfo {
  def fromFullLibraryInfo(info: FullLibraryInfo, extra: Option[MarketingSuggestedLibrarySystemValue] = None) = {
    MarketingSuggestedLibraryInfo(
      id = info.id,
      name = info.name,
      caption = extra flatMap (_.caption),
      url = info.url,
      image = info.image,
      owner = info.owner,
      numKeeps = info.numKeeps,
      numFollowers = info.numFollowers,
      color = info.color)
  }
}

