package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.common.healthcheck.{ SystemAdminMailSender, AirbrakeNotifier }
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.{ LogPrefix, Logging }
import com.keepit.common.performance.timing
import com.keepit.model._
import com.keepit.abook.{ RichContact, ABookServiceClient }
import com.keepit.typeahead.socialusers.{ KifiUserTypeahead, SocialUserTypeahead }
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.social.{ BasicUserWithUserId, SocialNetworkType, SocialNetworks }
import scala.concurrent.Future
import play.api.libs.json._
import com.keepit.typeahead.TypeaheadHit
import play.api.libs.functional.syntax._
import com.keepit.model.SocialUserConnectionsKey
import com.keepit.common.concurrent.ExecutionContext
import com.keepit.common.akka.SafeFuture
import com.keepit.search.SearchServiceClient
import com.keepit.common.mail.EmailAddress
import Logging.LoggerWithPrefix
import scala.collection.mutable.{ TreeSet, ArrayBuffer }
import org.joda.time.DateTime

case class ConnectionWithInviteStatus(label: String, score: Int, networkType: String, image: Option[String], value: String, status: String, email: Option[String] = None, inviteLastSentAt: Option[DateTime] = None)

object ConnectionWithInviteStatus {
  implicit val format = (
    (__ \ 'label).format[String] and
    (__ \ 'score).format[Int] and
    (__ \ 'networkType).format[String] and
    (__ \ 'image).formatNullable[String] and
    (__ \ 'value).format[String] and
    (__ \ 'status).format[String] and
    (__ \ 'email).formatNullable[String] and
    (__ \ 'inviteLastSentAt).formatNullable[DateTime]
  )(ConnectionWithInviteStatus.apply _, unlift(ConnectionWithInviteStatus.unapply))
}

class TypeaheadCommander @Inject() (
    db: Database,
    airbrake: AirbrakeNotifier,
    socialUserConnectionsCache: SocialUserConnectionsCache,
    socialConnectionRepo: SocialConnectionRepo,
    socialUserInfoRepo: SocialUserInfoRepo,
    invitationRepo: InvitationRepo,
    emailAddressRepo: UserEmailAddressRepo,
    userRepo: UserRepo,
    friendRequestRepo: FriendRequestRepo,
    abookServiceClient: ABookServiceClient,
    socialUserTypeahead: SocialUserTypeahead,
    kifiUserTypeahead: KifiUserTypeahead,
    searchClient: SearchServiceClient,
    systemAdminMailSender: SystemAdminMailSender) extends Logging {

  implicit val fj = ExecutionContext.fj

  private def emailId(email: EmailAddress) = s"email/${email.address}"
  private def socialId(sci: SocialUserBasicInfo) = s"${sci.networkType}/${sci.socialId.id}"

  private def queryContacts(userId: Id[User], search: Option[String], limit: Int): Future[Seq[RichContact]] = {
    search match {
      case Some(query) => abookServiceClient.prefixQuery(userId, query, Some(limit)).map { hits => hits.map(_.info) }
      case None => abookServiceClient.getContactsByUser(userId, pageSize = Some(limit))
    }
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

  private val snMap: Map[SocialNetworkType, Int] = Map(SocialNetworks.FACEBOOK -> 0, SocialNetworks.LINKEDIN -> 1, SocialNetworks.FORTYTWO -> 2, SocialNetworks.EMAIL -> 3, SocialNetworks.FORTYTWO_NF -> 4)

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

  private val hitOrd = new Ordering[(SocialNetworkType, TypeaheadHit[_])] {
    val genOrd = genericOrdering
    def compare(x: (SocialNetworkType, TypeaheadHit[_]), y: (SocialNetworkType, TypeaheadHit[_])): Int = {
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
      case SocialNetworks.FACEBOOK | SocialNetworks.LINKEDIN => hit.info.userId.isEmpty
      case SocialNetworks.FORTYTWO => false // see KifiUserTypeahead!
      case _ => true
    }
  }

  private def fetchAll(socialF: Future[Seq[TypeaheadHit[SocialUserBasicInfo]]],
    kifiF: Future[Seq[TypeaheadHit[User]]],
    abookF: Future[Seq[TypeaheadHit[RichContact]]],
    nfUsersF: Future[Seq[TypeaheadHit[BasicUserWithUserId]]]) = {
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
      Some(sorted)
    }
  }

  private def aggregate(userId: Id[User], q: String, limit: Option[Int], dedupEmail: Boolean): Future[Option[Seq[(SocialNetworkType, TypeaheadHit[_])]]] = {
    implicit val prefix = LogPrefix(s"aggregate($userId,$q,$limit)")
    val socialF = socialUserTypeahead.topN(userId, q, limit map (_ * 3))(TypeaheadHit.defaultOrdering[SocialUserBasicInfo]) map { res =>
      res.collect {
        case hit if includeHit(hit) => hit
      }
    }
    val kifiF = kifiUserTypeahead.topN(userId, q, limit)(TypeaheadHit.defaultOrdering[User])
    val abookF = if (q.length < 2) Future.successful(Seq.empty) else abookServiceClient.prefixQuery(userId, q, limit)
    val nfUsersF = if (q.length < 2) Future.successful(Seq.empty) else searchClient.userTypeaheadWithUserId(userId, q, limit.getOrElse(100), filter = "nf")

    limit match {
      case None => fetchAll(socialF, kifiF, abookF, nfUsersF)
      case Some(n) =>
        val zHits = new ArrayBuffer[(SocialNetworkType, TypeaheadHit[_])] // can use minHeap
        socialF flatMap { socialRes =>
          val socialHits = socialRes.map(h => (h.info.networkType, h)).sorted(hitOrd)
          zHits ++= socialHits.takeWhile(t => t._2.score == 0)
          val topF = if (zHits.length >= n) {
            val res = zHits.take(n)
            log.infoP(s"short-circuit (social) res=${res.mkString(",")}")
            Future.successful(Option(res))
          } else {
            kifiF flatMap { kifiRes =>
              val kifiHits = kifiRes.map(h => (SocialNetworks.FORTYTWO, h)).sorted(hitOrd)
              zHits ++= kifiHits.takeWhile(t => t._2.score == 0)
              if (zHits.length >= n) {
                val res = zHits.take(n)
                log.infoP(s"short-circuit (social+kifi) res=${res.mkString(",")}")
                Future.successful(Option(res))
              } else {
                abookF flatMap { abookRes =>
                  val filteredABookHits = if (!dedupEmail) abookRes else {
                    val kifiUsers = kifiRes.map(h => h.info.id.get -> h).toMap
                    var uniqueLowerCasedAddresses = Set.empty[String]
                    abookRes.filterNot { h =>
                      val lowerCasedAddress = h.info.email.address.toLowerCase
                      val duplicateEmail = uniqueLowerCasedAddresses.contains(lowerCasedAddress)
                      uniqueLowerCasedAddresses += lowerCasedAddress
                      duplicateEmail || h.info.userId.exists { uId => // todo: confirm this field is updated properly
                        kifiUsers.get(uId).exists { userHit =>
                          if (userHit.score <= h.score) {
                            log.infoP(s"DUP econtact (${h.info.email}) discarded; userHit=${userHit.info} econtactHit=${h.info}")
                            true // todo: transform to User
                          } else {
                            log.warnP(s"DUP econtact ${h.info} has better score than user ${userHit}")
                            false
                          }
                        }
                      }
                    }
                  }
                  val abookHits = filteredABookHits.map(h => (SocialNetworks.EMAIL, h)).sorted(hitOrd)
                  zHits ++= abookHits.takeWhile(t => t._2.score == 0)
                  if (zHits.length >= n) {
                    val res = zHits.take(n)
                    log.infoP(s"short-circuit (social+kifi+abook) res=${res.mkString(",")}")
                    Future.successful(Option(res))
                  } else {
                    nfUsersF map { nfUserRes =>
                      val nfUserHits = nfUserRes.map(h => (SocialNetworks.FORTYTWO_NF, h)).sorted(hitOrd)
                      zHits ++= nfUserHits.takeWhile(t => t._2.score == 0)
                      if (zHits.length >= n) {
                        Option(zHits.take(n))
                      } else { // combine all & sort
                        Some((socialHits ++ kifiHits ++ abookHits ++ nfUserHits).sorted(hitOrd).take(n))
                      }
                    }
                  }
                }
              }
            }
          }
          topF
        }
    }
  }

  private def inviteStatus(inv: Invitation): (String, Option[DateTime]) = {
    inv.state match {
      case InvitationStates.ACCEPTED | InvitationStates.JOINED => ("joined", None) // check db
      case InvitationStates.INACTIVE => ("", None)
      case _ => ("invited", inv.lastSentAt.orElse(Some(inv.updatedAt)))
    }
  }

  private def joinWithInviteStatus(userId: Id[User], top: Seq[(SocialNetworkType, TypeaheadHit[_])], emailInvitesMap: Map[EmailAddress, Invitation], socialInvitesMap: Map[Id[SocialUserInfo], Invitation], pictureUrl: Boolean): Seq[ConnectionWithInviteStatus] = {
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
          val picUrl = if (pictureUrl) {
            u.pictureName.map { pn => s"$pn.jpg" } // old bug
          } else None
          Some(ConnectionWithInviteStatus(u.fullName, hit.score, SocialNetworks.FORTYTWO.name, picUrl, s"fortytwo/${u.externalId}", "joined"))

        case bu: BasicUserWithUserId => // todo(Ray): uptake User API from search
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
      val topF: Future[Option[Seq[(SocialNetworkType, TypeaheadHit[_])]]] = aggregate(userId, q, limit, dedupEmail)
      topF flatMap { topOpt =>
        topOpt match {
          case None => Future.successful(Seq.empty[ConnectionWithInviteStatus])
          case Some(top) => {
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
    }
  }

  def hideEmailFromUser(userId: Id[User], email: EmailAddress): Future[Boolean] = {
    abookServiceClient.hideEmailFromUser(userId, email)
  }

}
