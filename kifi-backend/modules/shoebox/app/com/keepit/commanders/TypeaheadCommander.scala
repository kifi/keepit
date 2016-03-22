package com.keepit.commanders

import com.google.inject.{ Provider, Inject }
import com.keepit.abook.ABookServiceClient
import com.keepit.abook.model.RichContact
import com.keepit.common.concurrent.FutureHelpers
import com.keepit.common.core._
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.{ AirbrakeNotifier, SystemAdminMailSender }
import com.keepit.common.logging.Logging.LoggerWithPrefix
import com.keepit.common.logging.{ LogPrefix, Logging }
import com.keepit.common.mail.{ BasicContact, EmailAddress }
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.store.ImagePath
import com.keepit.common.time.DateTimeJsonFormat
import com.keepit.model.{ SocialUserConnectionsKey, _ }
import com.keepit.search.SearchServiceClient
import com.keepit.social.{ BasicUser, SocialNetworkType, SocialNetworks, TypeaheadUserHit }
import com.keepit.typeahead.{ LibraryTypeahead, TypeaheadHit, KifiUserTypeahead, SocialUserTypeahead }
import com.kifi.macros.json
import org.joda.time.DateTime
import play.api.libs.concurrent.Execution.Implicits._
import com.keepit.common.CollectionHelpers.dedupBy

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Future

class TypeaheadCommander @Inject() (
    db: Database,
    airbrake: AirbrakeNotifier,
    socialUserConnectionsCache: SocialUserConnectionsCache,
    socialConnectionRepo: SocialConnectionRepo,
    socialUserInfoRepo: SocialUserInfoRepo,
    invitationRepo: InvitationRepo,
    basicUserRepo: BasicUserRepo,
    friendRequestRepo: FriendRequestRepo,
    abookServiceClient: ABookServiceClient,
    socialUserTypeahead: SocialUserTypeahead,
    kifiUserTypeahead: KifiUserTypeahead,
    libraryTypeahead: LibraryTypeahead,
    searchClient: SearchServiceClient,
    interactionCommander: UserInteractionCommander,
    libraryRepo: LibraryRepo,
    libraryMembershipRepo: LibraryMembershipRepo,
    organizationAvatarCommander: Provider[OrganizationAvatarCommander],
    permissionCommander: Provider[PermissionCommander],
    pathCommander: PathCommander,
    implicit val config: PublicIdConfiguration) extends Logging {

  type NetworkTypeAndHit = (SocialNetworkType, TypeaheadHit[_])

  private def emailId(email: EmailAddress) = s"email/${email.address}"
  private def socialId(sci: SocialUserBasicInfo) = s"${sci.networkType}/${sci.socialId.id}"

  private def queryContacts(userId: Id[User], search: Option[String], limit: Int): Future[Seq[RichContact]] = {
    val futureContacts = search.map(_.trim) match {
      case Some(query) if query.nonEmpty => abookServiceClient.prefixQuery(userId, query, Some(limit)).map { hits => hits.map(_.info) }
      case _ => abookServiceClient.getContactsByUser(userId, pageSize = Some(limit))
    }
    futureContacts map { items => dedupBy(items)(_.email.address.toLowerCase) }
  }

  def queryNonUserContacts(userId: Id[User], query: String, limit: Int): Future[Seq[RichContact]] = {
    // TODO(jared,ray): filter in the abook service instead for efficiency and correctness
    queryContacts(userId, Some(query), 2 * limit).map { contacts => contacts.filter(_.userId.isEmpty).take(limit) }
  }

  private val snMap: Map[SocialNetworkType, Int] =
    Map(
      SocialNetworks.FACEBOOK -> 0,
      SocialNetworks.LINKEDIN -> 1,
      SocialNetworks.TWITTER -> 2,
      SocialNetworks.FORTYTWO -> 3,
      SocialNetworks.EMAIL -> 4,
      SocialNetworks.FORTYTWO_NF -> 5
    )

  private val snOrd = new Ordering[SocialNetworkType] {
    def compare(x: SocialNetworkType, y: SocialNetworkType) = if (x == y) 0 else snMap(x) compare snMap(y)
  }

  //  val genericOrdering = TypeaheadHit.defaultOrdering[_]
  private def genericOrdering[_] = new Ordering[TypeaheadHit[_]] {
    def compare(x: TypeaheadHit[_], y: TypeaheadHit[_]): Int = {
      var cmp = (x.score compare y.score)
      if (cmp == 0) {
        cmp = x.name compare y.name
        if (cmp == 0) {
          cmp = x.ordinal compare y.ordinal
        }
      }
      cmp
    }
  }

  private val hitOrd = new Ordering[NetworkTypeAndHit] {
    val genOrd = genericOrdering
    def compare(x: NetworkTypeAndHit, y: NetworkTypeAndHit): Int = {
      if (x._2.score == y._2.score) {
        var cmp = snOrd.compare(x._1, y._1)
        if (cmp == 0) {
          cmp = genOrd.compare(x._2, y._2)
        }
        cmp
      } else {
        genOrd.compare(x._2, y._2)
      }
    }
  }

  private def includeHit(hit: TypeaheadHit[SocialUserBasicInfo]): Boolean = {
    hit.info.networkType match {
      case SocialNetworks.FACEBOOK | SocialNetworks.LINKEDIN | SocialNetworks.TWITTER => hit.info.userId.isEmpty
      case SocialNetworks.FORTYTWO => false // see KifiUserTypeahead!
      case _ => true
    }
  }

  private def fetchAll(socialF: Future[Seq[TypeaheadHit[SocialUserBasicInfo]]],
    kifiF: Future[Seq[TypeaheadHit[User]]],
    abookF: Future[Seq[TypeaheadHit[RichContact]]],
    nfUsersF: Future[Seq[TypeaheadHit[TypeaheadUserHit]]]) = {
    for {
      socialHits <- socialF
      kifiHits <- kifiF
      abookHits <- abookF
      nfUserHits <- nfUsersF
    } yield {
      val socialHitsTup = socialHits.map(h => (h.info.networkType, h))
      val kifiHitsTup = kifiHits.map(h => (SocialNetworks.FORTYTWO, h))
      val abookHitsTup = abookHits.map(h => (SocialNetworks.EMAIL, h))
      val nfUserHitsTup = nfUserHits.map(h => (SocialNetworks.FORTYTWO_NF, h))
      log.infoP(s"social.len=${socialHitsTup.length} kifi.len=${kifiHitsTup.length} abook.len=${abookHitsTup.length} nf.len=${nfUserHits.length}")
      val sorted = (socialHitsTup ++ kifiHitsTup ++ abookHitsTup ++ nfUserHitsTup).sorted(hitOrd)
      log.infoP(s"all.sorted(len=${sorted.length}):${sorted.take(10).mkString(",")}")
      sorted
    }
  }

  private def aggregate(userId: Id[User], q: String, limitOpt: Option[Int], contacts: Set[ContactType]): Future[Seq[NetworkTypeAndHit]] = {
    implicit val prefix = LogPrefix(s"aggregate($userId,$q,$limitOpt)")
    // FB or LN
    val socialF = if (contacts.contains(ContactType.SOCIAL))
      socialUserTypeahead.topN(userId, q, limitOpt map (_ * 3))(TypeaheadHit.defaultOrdering[SocialUserBasicInfo]) map { res =>
        res.collect {
          case hit if includeHit(hit) => hit
        }
      }
    else { Future.successful(Seq.empty) }
    // Friends on Kifi
    val kifiF = if (contacts.contains(ContactType.KIFI_FRIEND)) kifiUserTypeahead.topN(userId, q, limitOpt)(TypeaheadHit.defaultOrdering[User]) else Future.successful(Seq.empty)
    // Email Contacts
    val abookF = if (!contacts.contains(ContactType.EMAIL) || q.length < 2) Future.successful(Seq.empty) else abookServiceClient.prefixQuery(userId, q, limitOpt.map(_ * 2))
    // Non-Friends on Kifi
    val nfUsersF = if (!contacts.contains(ContactType.KIFI_NON_FRIEND) || q.length < 2) Future.successful(Seq.empty) else searchClient.userTypeaheadWithUserId(userId, q, limitOpt.getOrElse(100), filter = "nf")

    limitOpt match {
      case None => fetchAll(socialF, kifiF, abookF, nfUsersF)
      case Some(limit) =>
        val social: Future[Seq[NetworkTypeAndHit]] = socialF.map { hits =>
          val hitsMap = hits.groupBy(_.info.networkType)
          val fb = hitsMap.getOrElse(SocialNetworks.FACEBOOK, Seq.empty)
          val lnkd = hitsMap.getOrElse(SocialNetworks.LINKEDIN, Seq.empty)
          val twtr = hitsMap.getOrElse(SocialNetworks.TWITTER, Seq.empty)
          (fb ++ lnkd ++ twtr).map(hit => (hit.info.networkType, hit))
        }
        val kifi: Future[Seq[NetworkTypeAndHit]] = kifiF.map { hits => hits.map(hit => (SocialNetworks.FORTYTWO, hit)) }
        val abook: Future[Seq[NetworkTypeAndHit]] = abookF.map { hits =>
          val distinctEmails = dedupBy(hits)(_.info.email.address.toLowerCase)
          distinctEmails.map { SocialNetworks.EMAIL -> _ }
        }
        val nf: Future[Seq[NetworkTypeAndHit]] = nfUsersF.map { hits => hits.map(hit => (SocialNetworks.FORTYTWO_NF, hit)) }
        val futures: Seq[Future[Seq[NetworkTypeAndHit]]] = Seq(social, kifi, abook, nf)
        fetchFirst(limit, futures)
    }
  }

  private def fetchFirst(limit: Int, futures: Iterable[Future[(Seq[NetworkTypeAndHit])]]): Future[Seq[NetworkTypeAndHit]] = {
    val bestHits = new ArrayBuffer[NetworkTypeAndHit] // hits with score == 0
    val allHits = new ArrayBuffer[NetworkTypeAndHit]
    FutureHelpers.processWhile[Seq[NetworkTypeAndHit]](futures, { hits =>
      val ordered = hits.sorted(hitOrd)
      log.info(s"[fetchFirst($limit)] ordered=${ordered.mkString(",")} bestHits.size=${bestHits.size}")
      bestHits ++= ordered.takeWhile { case (_, hit) => hit.score == 0 }
      (bestHits.length < limit) tap { res => if (res) allHits ++= ordered }
    }) map { _ =>
      (if (bestHits.length >= limit) bestHits else allHits.sorted(hitOrd)).take(limit)
    }
  }

  private def inviteStatus(inv: Invitation): (String, Option[DateTime]) = {
    inv.state match {
      case InvitationStates.ACCEPTED | InvitationStates.JOINED => ("joined", None) // check db
      case InvitationStates.INACTIVE => ("", None)
      case _ => ("invited", inv.lastSentAt.orElse(Some(inv.updatedAt)))
    }
  }

  private def joinWithInviteStatus(userId: Id[User], top: Seq[NetworkTypeAndHit], emailInvitesMap: Map[EmailAddress, Invitation], socialInvitesMap: Map[Id[SocialUserInfo], Invitation], pictureUrl: Boolean): Seq[ConnectionWithInviteStatus] = {
    val frMap = if (top.exists(t => t._1 == SocialNetworks.FORTYTWO_NF)) db.readOnlyMaster { implicit ro =>
      friendRequestRepo.getBySender(userId).map { fr => fr.recipientId -> fr }.toMap
    }
    else Map.empty[Id[User], FriendRequest]

    top flatMap {
      case (snType, hit) => hit.info match {
        case e: RichContact if !e.userId.contains(userId) =>
          val (status, lastSentAt) = emailInvitesMap.get(e.email) map { inv => inviteStatus(inv) } getOrElse ("", None)
          Some(ConnectionWithInviteStatus(e.name.getOrElse(""), hit.score, SocialNetworks.EMAIL.name, None, emailId(e.email), status, None, lastSentAt))

        case sci: SocialUserBasicInfo if !sci.userId.contains(userId) =>
          val (status, lastSentAt) = socialInvitesMap.get(sci.id) map { inv => inviteStatus(inv) } getOrElse ("", None)
          Some(ConnectionWithInviteStatus(sci.fullName, hit.score, sci.networkType.name, if (pictureUrl) sci.getPictureUrl(75, 75) else None, socialId(sci), status, None, lastSentAt))

        case u: User if !u.id.contains(userId) =>
          Some(ConnectionWithInviteStatus(u.fullName, hit.score, SocialNetworks.FORTYTWO.name, if (pictureUrl) u.pictureName.map(_ + ".jpg") else None, s"fortytwo/${u.externalId}", "joined"))

        case bu: TypeaheadUserHit if bu.userId != userId => // todo(Ray): uptake User API from search
          val name = s"${bu.firstName} ${bu.lastName}".trim // if not good enough, lookup User
          val picUrl = if (pictureUrl) Some(bu.pictureName) else None
          val frOpt = frMap.get(bu.userId)
          Some(ConnectionWithInviteStatus(name, hit.score, snType.name, picUrl, s"fortytwo/${bu.externalId}", frOpt.map(_ => "requested").getOrElse("joined"), None, frOpt.map(_.createdAt)))

        case _: RichContact | SocialUserBasicInfo | User | TypeaheadUserHit => None

        case _ =>
          log.warn(s"Unknown hit type: $hit")
          None
      }
    }
  }

  def searchWithInviteStatus(userId: Id[User], query: String, limit: Option[Int], pictureUrl: Boolean): Future[Seq[ConnectionWithInviteStatus]] = {
    implicit val prefix = LogPrefix(s"searchWIS($userId,$query,$limit)")

    val socialInvitesF = db.readOnlyMasterAsync { implicit ro =>
      invitationRepo.getSocialInvitesBySenderId(userId) // not cached
    }
    val emailInvitesF = db.readOnlyMasterAsync { implicit ro =>
      invitationRepo.getEmailInvitesBySenderId(userId)
    }

    val q = query.trim
    if (q.length == 0) Future.successful(Seq.empty[ConnectionWithInviteStatus])
    else {
      aggregate(userId, q, limit, ContactType.getAll()) flatMap { top =>
        for {
          socialInvites <- socialInvitesF
          emailInvites <- emailInvitesF
        } yield {
          val socialInvitesMap = socialInvites.map { inv => inv.recipientSocialUserId.get -> inv }.toMap // overhead
          val emailInvitesMap = emailInvites.map { inv => inv.recipientEmailAddress.get -> inv }.toMap
          val resWithStatus = joinWithInviteStatus(userId, top, emailInvitesMap, socialInvitesMap, pictureUrl)
          val res = limit.map { n =>
            resWithStatus.take(n)
          }.getOrElse(resWithStatus)
          log.infoP(s"result=${res.mkString(",")}")
          res
        }
      }
    }
  }

  private def searchFriendsAndContacts(userId: Id[User], query: String, includeSelf: Boolean, limit: Option[Int]): Future[(Seq[(Id[User], BasicUser)], Seq[BasicContact])] = {
    aggregate(userId, query, limit, Set(ContactType.KIFI_FRIEND, ContactType.EMAIL)).map { hits =>
      val (users, contacts) = hits.map(_._2.info).foldLeft((Seq.empty[User], Seq.empty[RichContact])) {
        case ((us, cs), nextContact: RichContact) => (us, cs :+ nextContact)
        case ((us, cs), nextUser: User) => (us :+ nextUser, cs)
        case ((us, cs), nextHit) =>
          airbrake.notify(new IllegalArgumentException(s"Unknown hit type: $nextHit"))
          (us, cs)
      }

      val userResults = users.collect {
        case user if includeSelf || !user.id.contains(userId) =>
          user.id.get -> BasicUser.fromUser(user)
      }
      val emailResults = contacts.collect {
        case richContact if (includeSelf || !richContact.userId.contains(userId)) && !richContact.userId.exists(uid => users.exists(u => u.id.get == uid)) =>
          BasicContact.fromRichContact(richContact)
      }

      (userResults, emailResults)
    }
  }

  private def suggestionsToResults(suggestions: (Seq[Id[User]], Seq[EmailAddress])): (Seq[(Id[User], BasicUser)], Seq[BasicContact]) = {
    val usersById = db.readOnlyMaster { implicit session => basicUserRepo.loadAll(suggestions._1.toSet) }
    val users = suggestions._1.map(id => id -> usersById(id))
    val contacts = suggestions._2.map(BasicContact(_))

    (users, contacts)
  }

  // Users and emails
  def searchForContacts(userId: Id[User], query: String, limit: Option[Int], includeSelf: Boolean): Future[(Seq[(Id[User], BasicUser)], Seq[BasicContact])] = {
    query.trim match {
      case q if q.isEmpty =>
        Future.successful(suggestionsToResults(interactionCommander.suggestFriendsAndContacts(userId, limit)))
      case q =>
        val friends = searchFriendsAndContacts(userId, q, includeSelf = includeSelf, limit)
        val (userOrder, contactOrder) = suggestionsToResults(interactionCommander.suggestFriendsAndContacts(userId, None)) |> {
          case (users, contacts) =>
            val userOrder = users.zipWithIndex.map(u => u._1._1 -> u._2).toMap
            val contactOrder = contacts.zipWithIndex.toMap

            (userOrder.withDefaultValue(limit.getOrElse(500)), contactOrder.withDefaultValue(limit.getOrElse(500)))
        }
        val rankedFriends = friends.imap {
          case (users, contacts) =>
            val sortedUsers = users.sortBy(u => userOrder(u._1))
            val sortedContacts = contacts.sortBy(c => contactOrder(c))
            (sortedUsers, sortedContacts)
        }
        rankedFriends
    }
  }

  def searchForContactResults(userId: Id[User], query: String, limit: Option[Int], includeSelf: Boolean): Future[Seq[TypeaheadSearchResult]] = {
    val friendsAndContactsF = searchForContacts(userId, query, limit, includeSelf = includeSelf)
    for {
      (users, contacts) <- friendsAndContactsF
    } yield {
      val userResults = users.map { case (_, bu) => UserContactResult(name = bu.fullName, id = bu.externalId, pictureName = Some(bu.pictureName), username = bu.username, firstName = bu.firstName, lastName = bu.lastName) }
      val emailResults = contacts.map { contact => EmailContactResult(name = contact.name, email = contact.email) }
      userResults ++ emailResults
    }
  }

  def searchForKeepRecipients(userId: Id[User], query: String, limitOpt: Option[Int]): Future[Seq[TypeaheadSearchResult]] = {
    // Users, emails, and libraries
    val limit = Some(limitOpt.map(Math.min(_, 20)).getOrElse(20))
    query.trim match {
      case q if q.isEmpty =>
        Future.successful(suggestResults(userId))
      case q =>
        val friendsF = searchFriendsAndContacts(userId, q, includeSelf = true, limit)
        val librariesF = libraryTypeahead.topN(userId, q, limit)(TypeaheadHit.defaultOrdering[Library])

        val (userScore, emailScore, libScore) = {
          val interactions = interactionCommander.getRecentInteractions(userId).zipWithIndex
          val userScore = interactions.collect {
            case (InteractionInfo(UserInteractionRecipient(u), score), idx) => u -> idx
          }.toMap.withDefaultValue(UserInteraction.maximumInteractions)
          val emailScore = interactions.collect {
            case (InteractionInfo(EmailInteractionRecipient(e), score), idx) => e -> idx
          }.toMap.withDefaultValue(UserInteraction.maximumInteractions)
          val libScore = interactions.collect {
            case (InteractionInfo(LibraryInteraction(l), score), idx) => l -> idx
          }.toMap.withDefaultValue(UserInteraction.maximumInteractions)
          (userScore, emailScore, libScore)
        }

        for {
          (users, contacts) <- friendsF
          libraryHits <- librariesF
        } yield {
          val libraries = libraryHits.map(_.info)
          // (interactionIdx, typeaheadIdx, value). Lower scores are better for both.
          val userRes = users.zipWithIndex.map {
            case ((id, bu), idx) =>
              (userScore(id), idx, UserContactResult(name = bu.fullName, id = bu.externalId, pictureName = Some(bu.pictureName), username = bu.username, firstName = bu.firstName, lastName = bu.lastName))
          }
          val emailRes = contacts.zipWithIndex.map {
            case (contact, idx) =>
              (emailScore(contact.email), idx + limit.get, EmailContactResult(email = contact.email, name = contact.name))
          }
          val libRes = libToResult(userId, libraries.map(_.id.get)).zipWithIndex.map {
            case ((id, lib), idx) =>
              (libScore(id), idx, lib)
          }
          (userRes ++ emailRes ++ libRes).sortBy(d => (d._1, d._2)).map { case (_, _, res) => res }
        }
    }
  }

  private def suggestResults(userId: Id[User]): Seq[TypeaheadSearchResult] = {
    val interactions = interactionCommander.getRecentInteractions(userId)

    val usersById = {
      val userIds = interactions.collect { case InteractionInfo(UserInteractionRecipient(u), _) => u }
      db.readOnlyMaster { implicit session =>
        basicUserRepo.loadAll(userIds.toSet).map {
          case (id, bu) =>
            id -> UserContactResult(name = bu.fullName, id = bu.externalId, pictureName = Some(bu.pictureName), username = bu.username, firstName = bu.firstName, lastName = bu.lastName)
        }
      }
    }
    val libsById = {
      val libIds = interactions.collect { case InteractionInfo(LibraryInteraction(u), _) => u }
      libToResult(userId, libIds).toMap
    }

    interactions.flatMap {
      case InteractionInfo(UserInteractionRecipient(u), _) => usersById.get(u)
      case InteractionInfo(EmailInteractionRecipient(e), _) => Some(EmailContactResult(name = None, email = e))
      case InteractionInfo(LibraryInteraction(l), _) => libsById.get(l)
    }
  }

  private def libToResult(userId: Id[User], libIds: Seq[Id[Library]]): Seq[(Id[Library], LibraryResult)] = {
    val idSet = libIds.toSet
    val (libs, collaborators, memberships, permissions, basicUserById, orgAvatarsById) = db.readOnlyReplica { implicit session =>
      val libs = libraryRepo.getActiveByIds(idSet).values.toVector
      val collaborators = libraryMembershipRepo.getCollaboratorsByLibrary(idSet)
      val memberships = libraryMembershipRepo.getWithLibraryIdsAndUserId(idSet, userId)
      val permissions = libs.map { lib =>
        lib.id.get -> permissionCommander.get.getLibraryPermissions(lib.id.get, Some(userId))
      }.toMap
      val basicUserById = basicUserRepo.loadAll(collaborators.values.flatten.toSet)
      val orgAvatarsById = organizationAvatarCommander.get.getBestImagesByOrgIds(libs.flatMap(_.organizationId).toSet, ProcessedImageSize.Medium.idealSize)

      (libs, collaborators, memberships, permissions, basicUserById, orgAvatarsById)
    }
    libs.map { lib =>
      val collabs = (collaborators.getOrElse(lib.id.get, Set.empty) - userId).map(basicUserById(_)).toSeq
      val orgAvatarPath = lib.organizationId.flatMap { orgId => orgAvatarsById.get(orgId).map(_.imagePath) }
      val membershipInfo = memberships.get(lib.id.get).map { mem =>
        LibraryMembershipInfo(mem.access, mem.listed, mem.subscribedToUpdates, permissions.getOrElse(lib.id.get, Set.empty))
      }

      lib.id.get -> LibraryResult(
        id = Library.publicId(lib.id.get),
        name = lib.name,
        color = lib.color,
        visibility = lib.visibility,
        path = pathCommander.getPathForLibrary(lib),
        hasCollaborators = collabs.nonEmpty,
        collaborators = collabs,
        orgAvatar = orgAvatarPath,
        membership = membershipInfo
      )
    }
  }

  def hideEmailFromUser(userId: Id[User], email: EmailAddress): Future[Boolean] = {
    abookServiceClient.hideEmailFromUser(userId, email)
  }

}

@json case class ConnectionWithInviteStatus(label: String, score: Int, networkType: String, image: Option[String], value: String, status: String, email: Option[String] = None, inviteLastSentAt: Option[DateTime] = None)

sealed trait TypeaheadSearchResult
@json case class UserContactResult(name: String, id: ExternalId[User], pictureName: Option[String], username: Username, firstName: String, lastName: String) extends TypeaheadSearchResult
@json case class EmailContactResult(name: Option[String], email: EmailAddress) extends TypeaheadSearchResult
@json case class LibraryResult(id: PublicId[Library], name: String, color: Option[LibraryColor], visibility: LibraryVisibility,
  path: String, hasCollaborators: Boolean, collaborators: Seq[BasicUser], orgAvatar: Option[ImagePath],
  membership: Option[LibraryMembershipInfo]) extends TypeaheadSearchResult // Same as LibraryData. Duck typing would rock.

sealed abstract class ContactType(val value: String)
object ContactType {
  case object SOCIAL extends ContactType("social") // FB or LN
  case object EMAIL extends ContactType("email")
  case object KIFI_FRIEND extends ContactType("kifi_friend")
  case object KIFI_NON_FRIEND extends ContactType("kifi_non_friend")

  def getAll(): Set[ContactType] = {
    Set(SOCIAL, EMAIL, KIFI_FRIEND, KIFI_NON_FRIEND)
  }
}
