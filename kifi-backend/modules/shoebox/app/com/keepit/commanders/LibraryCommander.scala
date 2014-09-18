package com.keepit.commanders

import com.google.inject.{ Provider, Inject }
import com.keepit.commanders.emails.EmailOptOutCommander
import com.keepit.common.cache.{ ImmutableJsonCacheImpl, FortyTwoCachePlugin, CacheStatistics, Key }
import com.keepit.common.crypto.{ PublicIdConfiguration, PublicId }
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.db.{ Id, ExternalId }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.mail.{ LocalPostOffice, SystemEmailAddress, ElectronicMail, EmailAddress }
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.store.S3ImageStore
import com.keepit.common.time.Clock
import com.keepit.heimdal.HeimdalContext
import com.keepit.model._
import com.keepit.search.SearchServiceClient
import com.keepit.social.BasicUser
import com.kifi.macros.json
import play.api.libs.functional.syntax._
import play.api.libs.json._
import com.keepit.common.logging.{ AccessLog, Logging }
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent._
import scala.concurrent.duration.Duration

class LibraryCommander @Inject() (
    db: Database,
    libraryRepo: LibraryRepo,
    libraryMembershipRepo: LibraryMembershipRepo,
    libraryInviteRepo: LibraryInviteRepo,
    userRepo: UserRepo,
    basicUserRepo: BasicUserRepo,
    keepRepo: KeepRepo,
    keepToCollectionRepo: KeepToCollectionRepo,
    keepsCommanderProvider: Provider[KeepsCommander],
    collectionRepo: CollectionRepo,
    postOffice: LocalPostOffice,
    s3ImageStore: S3ImageStore,
    emailOptOutCommander: EmailOptOutCommander,
    airbrake: AirbrakeNotifier,
    searchClient: SearchServiceClient,
    keptAnalytics: KeepingAnalytics,
    implicit val publicIdConfig: PublicIdConfiguration,
    clock: Clock) extends Logging {

  def createFullLibraryInfo(userId: Id[User], library: Library): Future[FullLibraryInfo] = {

    val (lib, owner, collabs, follows, numCollabs, numFollows, keeps, keepCount) = db.readOnlyReplica { implicit s =>
      val owner = basicUserRepo.load(library.ownerId)
      val (firstFollows, firstCollabs) = libraryMembershipRepo.pageWithLibraryIdAndAccess(library.id.get, 10, 0, Set(LibraryAccess.READ_INSERT, LibraryAccess.READ_WRITE, LibraryAccess.READ_ONLY)).partition(u => u.access == LibraryAccess.READ_ONLY)
      val collabs = firstCollabs.map(m => basicUserRepo.load(m.userId))
      val follows = firstFollows.map(m => basicUserRepo.load(m.userId))
      val collabCount = libraryMembershipRepo.countWithLibraryIdAndAccess(library.id.get, Set(LibraryAccess.READ_WRITE, LibraryAccess.READ_INSERT))
      val followCount = libraryMembershipRepo.countWithLibraryIdAndAccess(library.id.get, Set(LibraryAccess.READ_ONLY))

      val keeps = keepRepo.getByLibrary(library.id.get, 10, 0)
      val keepCount = keepRepo.getCountByLibrary(library.id.get)
      (library, owner, collabs, follows, collabCount, followCount, keeps, keepCount)
    }

    keepsCommanderProvider.get.decorateKeepsIntoKeepInfos(userId, keeps).map { keepInfos =>
      FullLibraryInfo(
        id = Library.publicId(lib.id.get),
        name = lib.name,
        ownerId = owner.externalId,
        description = lib.description,
        slug = lib.slug,
        url = Library.formatLibraryPath(owner.username, owner.externalId, lib.slug),
        kind = lib.kind,
        visibility = lib.visibility,
        collaborators = collabs,
        followers = follows,
        keeps = keepInfos,
        numKeeps = keepCount,
        numCollaborators = numCollabs,
        numFollowers = numFollows)
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
        val myLibs = db.readOnlyReplica { implicit s =>
          for (t <- libraryRepo.getByUser(ownerId) if t._1 == LibraryAccess.OWNER) yield {
            t._2
          }
        }
        val exists = myLibs.exists(lib => lib.name == libAddReq.name || lib.slug == libAddReq.slug)
        if (exists) {
          Left(LibraryFail("library_name_or_slug_exists"))
        } else {
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
          val validVisibility = libAddReq.visibility
          val validSlug = LibrarySlug(libAddReq.slug)

          val library = db.readWrite { implicit s =>
            libraryRepo.getOpt(ownerId, validSlug) match {
              case None =>
                val lib = libraryRepo.save(Library(ownerId = ownerId, name = libAddReq.name, description = libAddReq.description,
                  visibility = validVisibility, slug = validSlug, kind = LibraryKind.USER_CREATED, memberCount = 1))
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
          val bulkInvites1 = for (c <- collaboratorIds) yield LibraryInvite(libraryId = library.id.get, ownerId = ownerId, userId = Some(c), access = LibraryAccess.READ_WRITE)
          val bulkInvites2 = for (c <- followerIds) yield LibraryInvite(libraryId = library.id.get, ownerId = ownerId, userId = Some(c), access = LibraryAccess.READ_ONLY)

          inviteBulkUsers(bulkInvites1 ++ bulkInvites2)
          Right(library)
        }
      }
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

      for {
        newName <- validName(name.getOrElse(targetLib.name)).right
        newSlug <- validSlug(slug.getOrElse(targetLib.slug.value)).right
      } yield {
        val newDescription: Option[String] = description.orElse(targetLib.description)
        val newVisibility: LibraryVisibility = visibility.getOrElse(targetLib.visibility)
        future {
          val keeps = db.readOnlyMaster { implicit s =>
            keepRepo.getByLibrary(libraryId, Int.MaxValue, 0)
          }
          if (keeps.nonEmpty) {
            db.readWriteBatch(keeps) { (s, k) =>
              keepRepo.save(k.copy(visibility = newVisibility))(s)
            }
          }
        }
        db.readWrite { implicit s =>
          libraryRepo.save(targetLib.copy(name = newName, slug = LibrarySlug(newSlug), visibility = newVisibility, description = newDescription))
        }
      }
    }
  }

  def removeLibrary(libraryId: Id[Library], userId: Id[User])(implicit context: HeimdalContext): Either[LibraryFail, String] = {
    val oldLibrary = db.readOnlyMaster { implicit s => libraryRepo.get(libraryId) }
    if (oldLibrary.ownerId != userId) {
      Left(LibraryFail("permission_denied"))
    } else if (oldLibrary.kind == LibraryKind.SYSTEM_MAIN || oldLibrary.kind == LibraryKind.SYSTEM_SECRET) {
      Left(LibraryFail("cant_delete_system_generated_library"))
    } else {
      val keepsInLibrary = db.readWrite { implicit s =>
        val removedLibrary = libraryRepo.save(oldLibrary.withState(LibraryStates.INACTIVE))
        libraryMembershipRepo.getWithLibraryId(removedLibrary.id.get).map { m =>
          libraryMembershipRepo.save(m.withState(LibraryMembershipStates.INACTIVE))
        }
        libraryInviteRepo.getWithLibraryId(removedLibrary.id.get).map { inv =>
          libraryInviteRepo.save(inv.withState(LibraryInviteStates.INACTIVE))
        }
        keepRepo.getByLibrary(removedLibrary.id.get, Int.MaxValue, 0)
      }
      future {
        val savedKeeps = db.readWriteBatch(keepsInLibrary) { (s, k) =>
          keepRepo.save(k.copy(state = KeepStates.INACTIVE))(s)
        }
        keptAnalytics.unkeptPages(userId, savedKeeps.keySet.toSeq, context)
        searchClient.updateURIGraph()
      }
      Right("success")
    }
  }

  def canViewLibrary(userId: Option[Id[User]], lib: Library,
    inviteAuthToken: Option[String] = None,
    passcode: Option[HashedPassPhrase] = None): Boolean = {

    lib.visibility == LibraryVisibility.PUBLISHED || // published library
      db.readOnlyMaster { implicit s =>
        userId match {
          case Some(id) =>
            libraryMembershipRepo.getOpt(userId = id, libraryId = lib.id.get).nonEmpty ||
              libraryInviteRepo.getWithLibraryIdAndUserId(userId = id, libraryId = lib.id.get, excludeState = Some(LibraryInviteStates.INACTIVE)).nonEmpty
          case None =>
            if (passcode.nonEmpty && inviteAuthToken.nonEmpty)
              libraryInviteRepo.getWithLibraryId(libraryId = lib.id.get)
                .exists(i =>
                  HashedPassPhrase.generateHashedPhrase(i.passCode) == passcode.get &&
                    i.authToken == inviteAuthToken.get)
            else
              false
        }
      }
  }

  def copyKeepsFromCollectionToLibrary(libraryId: Id[Library], tagName: Hashtag): Either[LibraryFail, Seq[(Keep, LibraryError)]] = {
    val (library, ownerId, memTo, tagOpt, keeps) = db.readOnlyMaster { implicit s =>
      val library = libraryRepo.get(libraryId)
      val ownerId = library.ownerId
      val memTo = libraryMembershipRepo.getWithLibraryIdAndUserId(libraryId, ownerId)
      val tagOpt = collectionRepo.getByUserAndName(ownerId, tagName)
      val keeps = tagOpt match {
        case None => Seq.empty
        case Some(tag) => keepToCollectionRepo.getByCollection(tag.id.get).map(k2c => keepRepo.get(k2c.keepId))
      }
      (library, ownerId, memTo, tagOpt, keeps)
    }
    (memTo, tagOpt) match {
      case (_, None) => Left(LibraryFail("tag_not_found"))
      case (v, _) if v.isEmpty || v.get.access == LibraryAccess.READ_ONLY =>
        Right(keeps.map(_ -> LibraryError.DestPermissionDenied).toSeq)
      case (_, Some(tag)) =>
        def saveKeep(k: Keep, s: RWSession): Unit = {
          implicit val session = s
          // Swap to this when we remove the constraint:
          //          val newKeep = keepRepo.save(Keep(title = k.title, uriId = k.uriId, url = k.url, urlId = k.urlId, visibility = library.visibility,
          //            userId = k.userId, source = KeepSource.tagImport, libraryId = Some(libraryId)))
          //          keepToCollectionRepo.save(KeepToCollection(keepId = newKeep.id.get, collectionId = tag.id.get))
          keepRepo.save(k.copy(libraryId = Some(libraryId), visibility = library.visibility))
        }
        val badKeeps = applyToKeeps(ownerId, library, keeps, Set(), saveKeep)
        Right(badKeeps.toSeq)
    }
  }

  def getLibrariesByUser(userId: Id[User]): (Seq[(LibraryAccess, Library)], Seq[(LibraryInvite, Library)]) = {
    db.readOnlyMaster { implicit s =>
      val myLibraries = libraryRepo.getByUser(userId)
      val myInvites = libraryInviteRepo.getByUser(userId, Set(LibraryInviteStates.ACCEPTED, LibraryInviteStates.INACTIVE))
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

  private def inviteBulkUsers(invites: Seq[LibraryInvite]) {
    db.readWrite { implicit s =>
      invites.map { invite => libraryInviteRepo.save(invite) }
    }
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
        if (!generateNew)
          airbrake.notify(s"${userId} missing main library")
        Some(mainLib)
      } else None

      val secretOpt = if (sysLibs.find(_._2.kind == LibraryKind.SYSTEM_SECRET).isEmpty) {
        val secretLib = libraryRepo.save(Library(name = "Secret Library", ownerId = userId, visibility = LibraryVisibility.SECRET, slug = LibrarySlug("secret"), kind = LibraryKind.SYSTEM_SECRET, memberCount = 1))
        libraryMembershipRepo.save(LibraryMembership(libraryId = secretLib.id.get, userId = userId, access = LibraryAccess.OWNER, showInSearch = true))
        if (!generateNew)
          airbrake.notify(s"${userId} missing secret library")
        Some(secretLib)
      } else None

      val mainLib = sysLibs.find(_._2.kind == LibraryKind.SYSTEM_MAIN).map(_._2).orElse(mainOpt).get
      val secretLib = sysLibs.find(_._2.kind == LibraryKind.SYSTEM_SECRET).map(_._2).orElse(secretOpt).get
      (mainLib, secretLib)
    }
  }

  def inviteUsersToLibrary(libraryId: Id[Library], inviterId: Id[User], inviteList: Seq[(Either[Id[User], EmailAddress], LibraryAccess, Option[String])]): Either[LibraryFail, Seq[(Either[ExternalId[User], EmailAddress], LibraryAccess)]] = {
    db.readWrite { implicit s =>
      val targetLib = libraryRepo.get(libraryId)
      if (targetLib.ownerId != inviterId)
        Left(LibraryFail("permission_denied"))
      else if (targetLib.kind == LibraryKind.SYSTEM_MAIN || targetLib.kind == LibraryKind.SYSTEM_SECRET)
        Left(LibraryFail("cant_invite_to_system_generated_library"))
      else {
        val successInvites = for (i <- inviteList) yield {
          val (recipient, access, msgOpt) = i
          val (inv, extId) = recipient match {
            case Left(id) =>
              (LibraryInvite(libraryId = libraryId, ownerId = inviterId, userId = Some(id), access = access, message = msgOpt), Left(userRepo.get(id).externalId))
            case Right(email) =>
              (LibraryInvite(libraryId = libraryId, ownerId = inviterId, emailAddress = Some(email), access = access, message = msgOpt), Right(email))
          }
          (inv, (extId, access))
        }
        val (inv1, res) = successInvites.unzip
        inviteBulkUsers(inv1)
        Right(res)
      }
    }
  }

  def joinLibrary(userId: Id[User], libraryId: Id[Library]): Either[LibraryFail, Library] = {
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
            libraryMembershipRepo.save(mem.copy(state = LibraryMembershipStates.ACTIVE))
        }
        libraryRepo.updateMemberCount(libraryId)
        listInvites.map(inv => libraryInviteRepo.save(inv.copy(state = LibraryInviteStates.ACCEPTED)))
        Right(lib)
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
          libraryRepo.updateMemberCount(libraryId)
          Right()
        }
      }
    }
  }

  // Return is Set of Keep -> error message
  private def applyToKeeps(userId: Id[User],
    library: Library,
    keeps: Seq[Keep],
    excludeFromAccess: Set[LibraryAccess],
    saveKeep: (Keep, RWSession) => Unit): Seq[(Keep, LibraryError)] = {

    val badKeeps = collection.mutable.Set[(Keep, LibraryError)]()
    db.readWrite { implicit s =>
      // todo: make more performant
      val existingURIs = keepRepo.getByLibrary(library.id.get, 10000, 0).map(_.uriId).toSet
      keeps.groupBy(_.libraryId).map {
        case (None, keeps) => keeps
        case (Some(fromLibraryId), keeps) =>
          libraryMembershipRepo.getWithLibraryIdAndUserId(fromLibraryId, userId) match {
            case None =>
              badKeeps ++= keeps.map(_ -> LibraryError.SourcePermissionDenied)
              Seq[Keep]()
            case Some(memFrom) if excludeFromAccess.contains(memFrom.access) =>
              badKeeps ++= keeps.map(_ -> LibraryError.SourcePermissionDenied)
              Seq[Keep]()
            case Some(_) =>
              keeps
          }
      }.flatten.foreach { keep =>
        if (!existingURIs.contains(keep.uriId)) {
          saveKeep(keep, s)
        } else {
          badKeeps += keep -> LibraryError.AlreadyExistsInDest
        }
      }
    }
    badKeeps.toSeq
  }

  def copyKeeps(userId: Id[User], toLibraryId: Id[Library], keeps: Seq[Keep]): Seq[(Keep, LibraryError)] = {
    val (library, memTo) = db.readOnlyMaster { implicit s =>
      val library = libraryRepo.get(toLibraryId)
      val memTo = libraryMembershipRepo.getWithLibraryIdAndUserId(toLibraryId, userId)
      (library, memTo)
    }
    memTo match {
      case v if v.isEmpty || v.get.access == LibraryAccess.READ_ONLY =>
        keeps.map(_ -> LibraryError.DestPermissionDenied)
      case Some(_) =>
        def saveKeep(k: Keep, s: RWSession): Unit = {
          implicit val session = s
          val newKeep = keepRepo.save(Keep(title = k.title, uriId = k.uriId, url = k.url, urlId = k.urlId, visibility = library.visibility,
            userId = k.userId, source = k.source, libraryId = Some(toLibraryId)))
          keepToCollectionRepo.getByKeep(k.id.get).map { k2c =>
            keepToCollectionRepo.save(KeepToCollection(keepId = newKeep.id.get, collectionId = k2c.collectionId))
          }
        }

        val badKeeps = applyToKeeps(userId, library, keeps, Set(), saveKeep)
        badKeeps
    }
  }

  def moveKeeps(userId: Id[User], toLibraryId: Id[Library], keeps: Seq[Keep]): Seq[(Keep, LibraryError)] = {
    val (library, memTo) = db.readOnlyMaster { implicit s =>
      val library = libraryRepo.get(toLibraryId)
      val memTo = libraryMembershipRepo.getWithLibraryIdAndUserId(toLibraryId, userId)
      (library, memTo)
    }
    memTo match {
      case v if v.isEmpty || v.get.access == LibraryAccess.READ_ONLY =>
        keeps.map(_ -> LibraryError.DestPermissionDenied)
      case Some(_) =>

        def saveKeep(k: Keep, s: RWSession): Unit = {
          implicit val session = s
          keepRepo.save(k.copy(libraryId = Some(toLibraryId)))
        }

        val badKeeps = applyToKeeps(userId, library, keeps, Set(LibraryAccess.READ_ONLY, LibraryAccess.READ_INSERT), saveKeep)
        badKeeps
    }
  }

  def inviteNotification(inviterId: Id[User], inviteeId: Id[User], libraryId: Id[Library]): Unit = {
    val (inviter, invitee, library, owner) = db.readOnlyMaster { implicit s =>
      val inviter = userRepo.get(inviterId)
      val invitee = userRepo.get(inviteeId)
      val library = libraryRepo.get(libraryId)
      val owner = userRepo.get(library.ownerId)
      (inviter, invitee, library, owner)
    }
    invitee.primaryEmail match {
      case None => {}
      case Some(email) =>
        val libraryLink = s"""www.kifi.com/${owner.username.getOrElse(owner.externalId)}/${library.slug}?auth=${library.universalLink}"""

        val imageUrl = s3ImageStore.avatarUrlByExternalId(Some(200), inviter.externalId, inviter.pictureName.getOrElse("0"), Some("https"))
        val unsubLink = s"https://www.kifi.com${com.keepit.controllers.website.routes.EmailOptOutController.optOut(emailOptOutCommander.generateOptOutToken(email))}"
        db.readWrite { implicit session =>
          postOffice.sendMail(ElectronicMail(
            from = SystemEmailAddress.NOTIFICATIONS,
            to = Seq(email),
            subject = "Kifi.com | You've been invited to a library!",
            htmlBody = views.html.email.libraryInvitation(invitee.firstName, inviter, imageUrl, library.name, library.description, libraryLink, unsubLink).body,
            category = NotificationCategory.User.LIBRARY_INVITATION
          ))
        }
    }
  }

  def getMainAndSecretLibrariesForUser(userId: Id[User])(implicit session: RWSession) = {
    val libs = libraryRepo.getByUser(userId)
    val mainOpt = libs.find {
      case (acc, lib) =>
        acc == LibraryAccess.OWNER && lib.kind == LibraryKind.SYSTEM_MAIN
    }
    val secretOpt = libs.find {
      case (acc, lib) =>
        acc == LibraryAccess.OWNER && lib.kind == LibraryKind.SYSTEM_SECRET
    }
    val (main, secret) = if (mainOpt.isEmpty || secretOpt.isEmpty) {
      // Right now, we don't have any users without libraries. However, I'd prefer to be safe for now
      // and fix it if a user's libraries are not set up.
      log.error(s"Unable to get main or secret libraries for user $userId: $mainOpt $secretOpt")
      internSystemGeneratedLibraries(userId)
    } else (mainOpt.get._2, secretOpt.get._2)
    (main, secret)
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
  ownerId: ExternalId[User],
  numKeeps: Int,
  kind: LibraryKind)
object LibraryInfo {
  implicit val libraryExternalIdFormat = ExternalId.format[Library]

  implicit val format = (
    (__ \ 'id).format[PublicId[Library]] and
    (__ \ 'name).format[String] and
    (__ \ 'visibility).format[LibraryVisibility] and
    (__ \ 'shortDescription).formatNullable[String] and
    (__ \ 'url).format[String] and
    (__ \ 'ownerId).format[ExternalId[User]] and
    (__ \ 'numKeeps).format[Int] and
    (__ \ 'kind).format[LibraryKind]
  )(LibraryInfo.apply, unlift(LibraryInfo.unapply))

  def fromLibraryAndOwner(lib: Library, owner: User, keepCount: Int)(implicit config: PublicIdConfiguration): LibraryInfo = {
    LibraryInfo(
      id = Library.publicId(lib.id.get),
      name = lib.name,
      visibility = lib.visibility,
      shortDescription = lib.description,
      url = Library.formatLibraryPath(owner.username, owner.externalId, lib.slug),
      ownerId = owner.externalId,
      numKeeps = keepCount,
      kind = lib.kind
    )
  }

  val MaxDescriptionLength = 120
  def descriptionShortener(str: Option[String]): Option[String] = str match {
    case Some(s) => { Some(s.dropRight(s.length - MaxDescriptionLength)) } // will change later!
    case _ => None
  }
}

private case class GroupHolder(count: Int, users: Seq[BasicUser], isMore: Boolean)
private object GroupHolder {
  implicit val format = (
    (__ \ 'count).format[Int] and
    (__ \ 'users).format[Seq[BasicUser]] and
    (__ \ 'isMore).format[Boolean]
  )(GroupHolder.apply, unlift(GroupHolder.unapply))
}

private case class KeepsHolder(count: Int, keeps: Seq[KeepInfo], isMore: Boolean)
private object KeepsHolder {
  implicit val format = (
    (__ \ 'count).format[Int] and
    (__ \ 'keeps).format[Seq[KeepInfo]] and
    (__ \ 'isMore).format[Boolean]
  )(KeepsHolder.apply, unlift(KeepsHolder.unapply))
}

case class FullLibraryInfo(
  id: PublicId[Library],
  name: String,
  visibility: LibraryVisibility,
  description: Option[String],
  slug: LibrarySlug,
  url: String,
  kind: LibraryKind,
  ownerId: ExternalId[User],
  collaborators: Seq[BasicUser],
  followers: Seq[BasicUser],
  keeps: Seq[KeepInfo],
  numKeeps: Int,
  numCollaborators: Int,
  numFollowers: Int)

object FullLibraryInfo {
  implicit val format = (
    (__ \ 'id).format[PublicId[Library]] and
    (__ \ 'name).format[String] and
    (__ \ 'visibility).format[LibraryVisibility] and
    (__ \ 'description).formatNullable[String] and
    (__ \ 'slug).format[LibrarySlug] and
    (__ \ 'url).format[String] and
    (__ \ 'kind).format[LibraryKind] and
    (__ \ 'ownerId).format[ExternalId[User]] and
    (__ \ 'collaborators).format[Seq[BasicUser]] and
    (__ \ 'followers).format[Seq[BasicUser]] and
    (__ \ 'keeps).format[Seq[KeepInfo]] and
    (__ \ 'numKeeps).format[Int] and
    (__ \ 'numCollaborators).format[Int] and
    (__ \ 'numFollowers).format[Int]
  )(FullLibraryInfo.apply, unlift(FullLibraryInfo.unapply))
}

case class LibraryInfoIdKey(libraryId: Id[Library]) extends Key[LibraryInfo] {
  override val version = 1
  val namespace = "library_info_libraryid"
  def toKey(): String = libraryId.id.toString
}

class LibraryInfoIdCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends ImmutableJsonCacheImpl[LibraryInfoIdKey, LibraryInfo](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)
