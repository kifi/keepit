package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.abook.ABookServiceClient
import com.keepit.abook.model.RichContact
import com.keepit.common.concurrent.FutureHelpers
import com.keepit.common.core._
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.{ AirbrakeNotifier, SystemAdminMailSender }
import com.keepit.common.logging.Logging.LoggerWithPrefix
import com.keepit.common.logging.{ LogPrefix, Logging }
import com.keepit.common.mail.{ BasicContact, EmailAddress }
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.time.DateTimeJsonFormat
import com.keepit.model.{ SocialUserConnectionsKey, _ }
import com.keepit.search.SearchServiceClient
import com.keepit.social.{ BasicUser, SocialNetworkType, SocialNetworks, TypeaheadUserHit }
import com.keepit.typeahead.TypeaheadHit
import com.keepit.typeahead.{ KifiUserTypeahead, SocialUserTypeahead }
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
    emailAddressRepo: UserEmailAddressRepo,
    userRepo: UserRepo,
    userExpRepo: UserExperimentRepo,
    basicUserRepo: BasicUserRepo,
    friendRequestRepo: FriendRequestRepo,
    abookServiceClient: ABookServiceClient,
    socialUserTypeahead: SocialUserTypeahead,
    kifiUserTypeahead: KifiUserTypeahead,
    searchClient: SearchServiceClient,
    interactionCommander: UserInteractionCommander,
    systemAdminMailSender: SystemAdminMailSender,
    libraryMembershipRepo: LibraryMembershipRepo,
    libraryRepo: LibraryRepo,
    implicit val config: PublicIdConfiguration) extends Logging {

  type NetworkTypeAndHit = (SocialNetworkType, TypeaheadHit[_])

  private def emailId(email: EmailAddress) = s"email/${email.address}"
  private def socialId(sci: SocialUserBasicInfo) = s"${sci.networkType}/${sci.socialId.id}"

  private def queryContacts(userId: Id[User], search: Option[String], limit: Int): Future[Seq[RichContact]] = {
    val futureContacts = search match {
      case Some(query) => abookServiceClient.prefixQuery(userId, query, Some(limit)).map { hits => hits.map(_.info) }
      case None => abookServiceClient.getContactsByUser(userId, pageSize = Some(limit))
    }
    futureContacts map { items => dedupBy(items)(_.email.address.toLowerCase) }
  }

  def queryNonUserContacts(userId: Id[User], query: String, limit: Int): Future[Seq[RichContact]] = {
    // TODO(jared,ray): filter in the abook service instead for efficiency and correctness
    queryContacts(userId, Some(query), 2 * limit).map { contacts => contacts.filter(_.userId.isEmpty).take(limit) }
  }

  private def queryContactsWithInviteStatus(userId: Id[User], search: Option[String], limit: Int): Future[Seq[(RichContact, Boolean)]] = {
    queryContacts(userId, search, limit) map { contacts =>
      val allEmailInvites = db.readOnlyMaster { implicit ro =>
        invitationRepo.getEmailInvitesBySenderId(userId)
      }
      val invitesMap = allEmailInvites.map { inv => inv.recipientEmailAddress.get -> inv }.toMap // overhead
      contacts map { c =>
        val invited = invitesMap.get(c.email) map { _.state != InvitationStates.INACTIVE } getOrElse false
        (c, invited)
      }
    }
  }

  private def queryContactsInviteStatus(userId: Id[User], search: Option[String], limit: Int): Future[Seq[ConnectionWithInviteStatus]] = {
    queryContactsWithInviteStatus(userId, search, limit) map { contacts =>
      contacts.map {
        case (c, invited) =>
          ConnectionWithInviteStatus(c.name.getOrElse(""), -1, SocialNetworks.EMAIL.name, None, emailId(c.email), if (invited) "invited" else "")
      }
    }
  }

  private def querySocial(userId: Id[User], search: Option[String], network: Option[String], limit: Int): Future[Seq[(SocialUserBasicInfo, String)]] = {
    val filteredF = search match {
      case Some(query) if query.trim.length > 0 => {
        implicit val hitOrdering = TypeaheadHit.defaultOrdering[SocialUserBasicInfo]
        socialUserTypeahead.topN(userId, query, None) map { hits => // todo(ray): use limit
          val infos = hits map { _.info }
          val res = network match {
            case Some(networkType) => infos.filter(info => info.networkType.name == networkType)
            case None => infos.filter(info => info.networkType.name != SocialNetworks.FORTYTWO) // backward compatibility
          }
          log.info(s"[querySocialConnections($userId,$search,$network,$limit)] res=${res.mkString(",")}")
          res
        }
      }
      case None => {
        db.readOnlyMasterAsync { implicit s =>
          socialConnectionRepo.getSocialConnectionInfosByUser(userId).filterKeys(networkType => network.forall(_ == networkType.name))
        } map { infos =>
          infos.values.flatten.toVector
        }

      }
    }
    filteredF map { filtered =>
      log.info(s"[queryConnections($userId,$search,$network,$limit)] filteredConns(len=${filtered.length});${filtered.take(20).mkString(",")}")

      val paged = filtered.take(limit)

      db.readOnlyMaster { implicit ro =>
        val allInvites = invitationRepo.getSocialInvitesBySenderId(userId)
        val invitesMap = allInvites.map { inv => inv.recipientSocialUserId.get -> inv }.toMap // overhead
        val resWithStatus = paged map { sci =>
          val status = sci.userId match {
            case Some(userId) => "joined"
            case None => invitesMap.get(sci.id) collect {
              case inv if inv.state == InvitationStates.ACCEPTED || inv.state == InvitationStates.JOINED =>
                // This is a hint that that cache may be stale as userId should be set
                socialUserInfoRepo.getByUser(userId).foreach { socialUser =>
                  socialUserConnectionsCache.remove(SocialUserConnectionsKey(socialUser.id.get))
                }
                "joined"
              case inv if inv.state != InvitationStates.INACTIVE => "invited"
            } getOrElse ""
          }
          (sci, status)
        }
        resWithStatus
      }
    }
  }

  private def querySocialInviteStatus(userId: Id[User], search: Option[String], network: Option[String], limit: Int, pictureUrl: Boolean): Future[Seq[ConnectionWithInviteStatus]] = {
    querySocial(userId, search, network, limit) map { infos =>
      infos.map {
        case (c, s) =>
          ConnectionWithInviteStatus(c.fullName, -1, c.networkType.name, if (pictureUrl) c.getPictureUrl(75, 75) else None, socialId(c), s)
      }
    }
  }

  def queryAll(userId: Id[User], search: Option[String], network: Option[String], limit: Int, pictureUrl: Boolean): Future[Seq[ConnectionWithInviteStatus]] = {
    val abookF = {
      if (network.isEmpty || network.exists(_ == "email")) queryContactsInviteStatus(userId, search, limit) // deviate from UserCommander.getAllConnections
      else Future.successful(Seq.empty[ConnectionWithInviteStatus])
    }

    val socialF = {
      if (network.isEmpty || network.exists(_ != "email")) {
        querySocialInviteStatus(userId, search, network, limit, pictureUrl)
      } else Future.successful(Seq.empty[ConnectionWithInviteStatus])
    }

    for {
      socialRes <- socialF
      abookRes <- abookF
    } yield {
      (socialRes ++ abookRes)
    }
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

  private def aggregate(userId: Id[User], q: String, limitOpt: Option[Int], dedupEmail: Boolean, contacts: Set[ContactType]): Future[Seq[NetworkTypeAndHit]] = {
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
          val fb = hitsMap.get(SocialNetworks.FACEBOOK) getOrElse Seq.empty
          val lnkd = hitsMap.get(SocialNetworks.LINKEDIN) getOrElse Seq.empty
          // twtr is protected by experiment; once this goes away we can make this generic
          val hasTwtrExp = db.readOnlyMaster { implicit ro => userExpRepo.hasExperiment(userId, ExperimentType.TWITTER_BETA) }
          val twtr = if (!hasTwtrExp) Seq.empty else hitsMap.get(SocialNetworks.TWITTER) getOrElse Seq.empty
          (fb ++ lnkd ++ twtr).map(hit => (hit.info.networkType, hit))
        }
        val kifi: Future[Seq[NetworkTypeAndHit]] = kifiF.map { hits => hits.map(hit => (SocialNetworks.FORTYTWO, hit)) }
        val abook: Future[Seq[NetworkTypeAndHit]] = abookF.map { hits =>
          val nonUsers = hits.filter(_.info.userId.isEmpty)
          val distinctEmails = dedupBy(nonUsers)(_.info.email.address.toLowerCase)
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
        case e: RichContact =>
          val (status, lastSentAt) = emailInvitesMap.get(e.email) map { inv => inviteStatus(inv) } getOrElse ("", None)
          Some(ConnectionWithInviteStatus(e.name.getOrElse(""), hit.score, SocialNetworks.EMAIL.name, None, emailId(e.email), status, None, lastSentAt))

        case sci: SocialUserBasicInfo =>
          val (status, lastSentAt) = socialInvitesMap.get(sci.id) map { inv => inviteStatus(inv) } getOrElse ("", None)
          Some(ConnectionWithInviteStatus(sci.fullName, hit.score, sci.networkType.name, if (pictureUrl) sci.getPictureUrl(75, 75) else None, socialId(sci), status, None, lastSentAt))

        case u: User =>
          Some(ConnectionWithInviteStatus(u.fullName, hit.score, SocialNetworks.FORTYTWO.name, if (pictureUrl) u.pictureName.map(_ + ".jpg") else None, s"fortytwo/${u.externalId}", "joined"))

        case bu: TypeaheadUserHit => // todo(Ray): uptake User API from search
          val name = s"${bu.firstName} ${bu.lastName}".trim // if not good enough, lookup User
          val picUrl = if (pictureUrl) Some(bu.pictureName) else None
          val frOpt = frMap.get(bu.userId)
          Some(ConnectionWithInviteStatus(name, hit.score, snType.name, picUrl, s"fortytwo/${bu.externalId}", frOpt.map(_ => "requested").getOrElse("joined"), None, frOpt.map(_.createdAt)))

        case _ =>
          airbrake.notify(new IllegalArgumentException(s"Unknown hit type: $hit"))
          None
      }
    }
  }

  def searchWithInviteStatus(userId: Id[User], query: String, limit: Option[Int], pictureUrl: Boolean, dedupEmail: Boolean): Future[Seq[ConnectionWithInviteStatus]] = {
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
      aggregate(userId, q, limit, dedupEmail, ContactType.getAll()) flatMap { top =>
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

  def suggestFriendsAndContacts(userId: Id[User], limit: Option[Int]): (Seq[(Id[User], BasicUser)], Seq[BasicContact]) = {
    val allRecentInteractions = interactionCommander.getRecentInteractions(userId)
    val relevantInteractions = limit.map(allRecentInteractions.take(_)) getOrElse allRecentInteractions
    val (userIds, emailAddresses) = relevantInteractions.foldLeft((Seq.empty[Id[User]], Seq.empty[EmailAddress])) {
      case ((userIds, emailAddresses), InteractionInfo(UserRecipient(userId), _)) => (userIds :+ userId, emailAddresses)
      case ((userIds, emailAddresses), InteractionInfo(EmailRecipient(emailAddress), _)) => (userIds, emailAddresses :+ emailAddress)
    }

    val usersById = db.readOnlyMaster { implicit session => basicUserRepo.loadAll(userIds.toSet) }
    val users = userIds.map(id => id -> usersById(id))
    val contacts = emailAddresses.map(BasicContact(_)) // TODO(Aaron): include contact name if address is in user's address book
    (users, contacts)
  }

  def searchWritableLibraries(userId: Id[User], query: String): Seq[AliasContactResult] = Seq.empty // todo: Make this work :)

  def searchFriendsAndContacts(userId: Id[User], query: String, limit: Option[Int]): Future[(Seq[(Id[User], BasicUser)], Seq[BasicContact])] = {
    aggregate(userId, query, limit, true, Set(ContactType.KIFI_FRIEND, ContactType.EMAIL)).map { hits =>
      val (users, contacts) = hits.map(_._2.info).foldLeft((Seq.empty[User], Seq.empty[RichContact])) {
        case ((users, contacts), nextContact: RichContact) => (users, contacts :+ nextContact)
        case ((users, contacts), nextUser: User) => (users :+ nextUser, contacts)
        case ((users, contacts), nextHit) =>
          airbrake.notify(new IllegalArgumentException(s"Unknown hit type: $nextHit"))
          (users, contacts)
      }
      (users.map(user => user.id.get -> BasicUser.fromUser(user)), contacts.map(BasicContact.fromRichContact))
    }
  }

  def searchForContacts(userId: Id[User], query: String, limit: Option[Int]): Future[Seq[ContactSearchResult]] = {
    val (friendsAndContactsF, aliasF) = query.trim match {
      case q if q.isEmpty =>
        (Future.successful(suggestFriendsAndContacts(userId, limit)), Future.successful(Seq.empty))
      case q =>
        // Start futures
        val friends = searchFriendsAndContacts(userId, q, limit)
        val aliases = Future.successful(searchWritableLibraries(userId, query))

        val startTime = System.currentTimeMillis()

        val (userOrder, contactOrder) = suggestFriendsAndContacts(userId, None) |> {
          case (users, contacts) =>
            val userOrder = users.zipWithIndex.map(u => u._1._1 -> u._2).toMap
            val contactOrder = contacts.zipWithIndex.toMap
            (userOrder.withDefaultValue(limit.getOrElse(500)), contactOrder.withDefaultValue(limit.getOrElse(500)))
        }

        log.info(s"[searchForContacts] Ordering results for $userId took ${System.currentTimeMillis() - startTime}ms")

        val rankedFriends = friends.imap {
          case (users, contacts) =>
            val sortedUsers = users.sortBy(u => userOrder(u._1))
            val sortedContacts = contacts.sortBy(c => contactOrder(c))
            (sortedUsers, sortedContacts)
        }
        (rankedFriends, aliases)
    }

    for {
      alias <- aliasF
      (users, contacts) <- friendsAndContactsF
    } yield {
      val userResults = users.map { case (_, basicUser) => UserContactResult(name = basicUser.fullName, id = basicUser.externalId, pictureName = Some(basicUser.pictureName)) }
      val emailResults = contacts.map { contact => EmailContactResult(name = contact.name, email = contact.email) }
      userResults ++ emailResults ++ alias
    }
  }

  def hideEmailFromUser(userId: Id[User], email: EmailAddress): Future[Boolean] = {
    abookServiceClient.hideEmailFromUser(userId, email)
  }

}

@json case class ConnectionWithInviteStatus(label: String, score: Int, networkType: String, image: Option[String], value: String, status: String, email: Option[String] = None, inviteLastSentAt: Option[DateTime] = None)

sealed trait ContactSearchResult
@json case class UserContactResult(name: String, id: ExternalId[User], pictureName: Option[String]) extends ContactSearchResult
@json case class EmailContactResult(name: Option[String], email: EmailAddress) extends ContactSearchResult
@json case class AliasContactResult(name: String, id: String, kind: String) extends ContactSearchResult

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
