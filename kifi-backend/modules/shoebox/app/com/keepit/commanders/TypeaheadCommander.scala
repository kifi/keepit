package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.common.healthcheck.{SystemAdminMailSender, AirbrakeNotifier}
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.{LogPrefix, Logging}
import com.keepit.model._
import com.keepit.abook.ABookServiceClient
import com.keepit.typeahead.socialusers.{KifiUserTypeahead, SocialUserTypeahead}
import com.keepit.common.db.{ExternalId, Id}
import com.keepit.social.{BasicUser, SocialNetworkType, SocialNetworks}
import scala.concurrent.Future
import play.api.libs.json._
import com.keepit.typeahead.TypeaheadHit
import play.api.libs.functional.syntax._
import play.api.libs.json.JsString
import scala.Some
import com.keepit.model.SocialUserConnectionsKey
import play.api.libs.json.JsObject
import com.keepit.common.concurrent.ExecutionContext
import com.keepit.common.akka.SafeFuture
import com.keepit.typeahead.abook.EContactTypeahead
import com.keepit.search.SearchServiceClient
import com.keepit.common.mail.{EmailAddresses, ElectronicMail}
import Logging.LoggerWithPrefix
import scala.collection.mutable.ArrayBuffer

case class ConnectionWithInviteStatus(label:String, score:Int, networkType:String, image:Option[String], value:String, status:String)

object ConnectionWithInviteStatus {
  implicit val format = (
      (__ \ 'label).format[String] and
      (__ \ 'score).format[Int] and
      (__ \ 'networkType).format[String] and
      (__ \ 'image).formatNullable[String] and
      (__ \ 'value).format[String] and
      (__ \ 'status).format[String]
    )(ConnectionWithInviteStatus.apply _, unlift(ConnectionWithInviteStatus.unapply))
}

class TypeaheadCommander @Inject()(
  db: Database,
  airbrake: AirbrakeNotifier,
  socialUserConnectionsCache: SocialUserConnectionsCache,
  socialConnectionRepo: SocialConnectionRepo,
  socialUserInfoRepo: SocialUserInfoRepo,
  invitationRepo: InvitationRepo,
  abookServiceClient: ABookServiceClient,
  socialUserTypeahead: SocialUserTypeahead,
  kifiUserTypeahead: KifiUserTypeahead,
  econtactTypeahead: EContactTypeahead,
  searchClient: SearchServiceClient,
  systemAdminMailSender:SystemAdminMailSender
) extends Logging {

  implicit val fj = ExecutionContext.fj

  private def emailId(email:String) = s"email/$email"
  private def socialId(sci: SocialUserBasicInfo) = s"${sci.networkType}/${sci.socialId.id}"

  def queryContacts(userId:Id[User], search: Option[String], limit: Int):Future[Seq[(EContact, String)]] = {
    abookServiceClient.prefixQuery(userId, limit, search, None) map { paged =>
      val allEmailInvites = db.readOnly { implicit ro =>
        invitationRepo.getEmailInvitesBySenderId(userId)
      }
      val invitesMap = allEmailInvites.map{ inv => inv.recipientEContactId.get -> inv }.toMap // overhead
      val withStatus = paged map { e =>
        val status = invitesMap.get(e.id.get) map { inv =>
          if (inv.state != InvitationStates.INACTIVE) "invited" else ""
        } getOrElse ""
        (e, status)
      }
      withStatus.take(limit)
    }
  }

  def queryContactsInviteStatus(userId:Id[User], search: Option[String], limit: Int):Future[Seq[ConnectionWithInviteStatus]] = {
    queryContacts(userId, search, limit) map { res =>
      res map { case (e, s) => ConnectionWithInviteStatus(e.name.getOrElse(""), -1, SocialNetworks.EMAIL.name, None, emailId(e.email), s) }
    }
  }

  def querySocial(userId:Id[User], search:Option[String], network:Option[String], limit:Int):Seq[(SocialUserBasicInfo, String)] = {
    val filtered = search match {
      case Some(query) if query.trim.length > 0 => {
        implicit val hitOrdering = TypeaheadHit.defaultOrdering[SocialUserBasicInfo]
        val infos = socialUserTypeahead.search(userId, query) getOrElse Seq.empty[SocialUserBasicInfo]
        val res = network match {
          case Some(networkType) => infos.filter(info => info.networkType.name == networkType)
          case None => infos.filter(info => info.networkType.name != SocialNetworks.FORTYTWO) // backward compatibility
        }
        log.info(s"[querySocialConnections($userId,$search,$network,$limit)] res=${res.mkString(",")}")
        res
      }
      case None => {
        val infos = db.readOnly { implicit s =>
          socialConnectionRepo.getSocialConnectionInfosByUser(userId).filterKeys(networkType => network.forall(_ == networkType.name))
        }
        infos.values.flatten.toVector
      }
    }
    log.info(s"[queryConnections($userId,$search,$network,$limit)] filteredConns(len=${filtered.length});${filtered.take(20).mkString(",")}")

    val paged = filtered.take(limit)

    db.readOnly { implicit ro =>
      val allInvites = invitationRepo.getSocialInvitesBySenderId(userId)
      val invitesMap = allInvites.map{ inv => inv.recipientSocialUserId.get -> inv }.toMap // overhead
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

  def querySocialInviteStatus(userId:Id[User], search:Option[String], network:Option[String], limit:Int, pictureUrl:Boolean):Seq[ConnectionWithInviteStatus] = {
    querySocial(userId, search, network, limit) map { case (c, s) =>
      ConnectionWithInviteStatus(c.fullName, -1, c.networkType.name, if (pictureUrl) c.getPictureUrl(75, 75) else None, socialId(c), s)
    }
  }

  def queryAll(userId:Id[User], search: Option[String], network: Option[String], limit: Int, pictureUrl: Boolean):Future[Seq[ConnectionWithInviteStatus]] = {
    val abookF = {
      if (network.isEmpty || network.exists(_ == "email")) queryContactsInviteStatus(userId, search, limit) // deviate from UserCommander.getAllConnections
      else Future.successful(Seq.empty[ConnectionWithInviteStatus])
    }

    val socialF = {
      if (network.isEmpty || network.exists(_ != "email")) {
        SafeFuture {
          querySocialInviteStatus(userId, search, network, limit, pictureUrl)
        }
      } else Future.successful(Seq.empty[ConnectionWithInviteStatus])
    }

    for {
      socialRes <- socialF
      abookRes  <- abookF
    } yield {
      (socialRes ++ abookRes)
    }
  }

  val snMap:Map[SocialNetworkType, Int] = Map(SocialNetworks.FACEBOOK -> 0, SocialNetworks.LINKEDIN -> 1, SocialNetworks.FORTYTWO -> 2, SocialNetworks.EMAIL -> 3, SocialNetworks.FORTYTWO_NF -> 4)

  val snOrd = new Ordering[SocialNetworkType] {
    def compare(x: SocialNetworkType, y: SocialNetworkType) = if (x == y) 0 else snMap(x) compare snMap(y)
  }

  //  val genericOrdering = TypeaheadHit.defaultOrdering[_]
  def genericOrdering[_] = new Ordering[TypeaheadHit[_]] {
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

  val hitOrd = new Ordering[(SocialNetworkType, TypeaheadHit[_])] {
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

  private def includeHit(hit:TypeaheadHit[SocialUserBasicInfo], filterJoinedUsers:Boolean):Boolean = {
    if (!filterJoinedUsers) true else hit.info.networkType match {
      case SocialNetworks.FACEBOOK | SocialNetworks.LINKEDIN => hit.info.userId.isEmpty
      case SocialNetworks.FORTYTWO => false // see KifiUserTypeahead!
      case _ => true
    }
  }

  private def fetchAll(socialF: Future[Option[Seq[TypeaheadHit[SocialUserBasicInfo]]]],
                       kifiF: Future[Option[Seq[TypeaheadHit[User]]]],
                       abookF: Future[Option[Seq[TypeaheadHit[EContact]]]],
                       nfUsersF: Future[Seq[TypeaheadHit[BasicUser]]]) = {
    for {
      socialHitsOpt <- socialF
      kifiHitsOpt <- kifiF
      abookHitsOpt <- abookF
      nfUserHits <- nfUsersF
    } yield {
      val socialHitsTup = socialHitsOpt.getOrElse(Seq.empty).map(h => (h.info.networkType, h))
      val kifiHitsTup = kifiHitsOpt.getOrElse(Seq.empty).map(h => (SocialNetworks.FORTYTWO, h))
      val abookHitsTup = abookHitsOpt.getOrElse(Seq.empty).map(h => (SocialNetworks.EMAIL, h))
      val nfUserHitsTup = nfUserHits.map(h => (SocialNetworks.FORTYTWO_NF, h))
      log.infoP(s"social.len=${socialHitsTup.length} kifi.len=${kifiHitsTup.length} abook.len=${abookHitsTup.length} nf.len=${nfUserHits.length}")
      val sorted = (socialHitsTup ++ kifiHitsTup ++ abookHitsTup ++ nfUserHitsTup).sorted(hitOrd)
      log.infoP(s"all.sorted(len=${sorted.length}):${sorted.take(10).mkString(",")}")
      Some(sorted)
    }
  }

  private def aggregate(userId: Id[User], q: String, limit: Option[Int], filterJoinedUsers: Boolean): Future[Option[Seq[(SocialNetworkType, TypeaheadHit[_])]]] = {
    implicit val prefix = LogPrefix(s"aggregate($userId,$q,$limit)")
    val socialF = socialUserTypeahead.asyncTopN(userId, q, limit map (_ * 3))(TypeaheadHit.defaultOrdering[SocialUserBasicInfo]) map { resOpt =>
      resOpt map { res =>
        res.collect {
          case hit if includeHit(hit, filterJoinedUsers) => hit
        }
      }
    }
    val kifiF = kifiUserTypeahead.asyncTopN(userId, q, limit)(TypeaheadHit.defaultOrdering[User])
    val abookF = econtactTypeahead.asyncTopN(userId, q, limit)(TypeaheadHit.defaultOrdering[EContact])
    val nfUsersF = if (q.length < 3) Future.successful(Seq.empty) else searchClient.userTypeahead(userId, q, limit.getOrElse(100), filter = "nf")

    limit match {
      case None => fetchAll(socialF, kifiF, abookF, nfUsersF)
      case Some(n) =>
        val zHits = new ArrayBuffer[(SocialNetworkType, TypeaheadHit[_])] // can use minHeap
        socialF flatMap { socialHitsOpt =>
          val socialHits = socialHitsOpt.getOrElse(Seq.empty).map(h => (h.info.networkType, h)).sorted(hitOrd)
          zHits ++= socialHits.takeWhile(t => t._2.score == 0)
          val topF = if (zHits.length >= n) {
            val res = zHits.take(n)
            log.infoP(s"short-circuit (social) res=${res.mkString(",")}")
            Future.successful(Option(res))
          } else {
            val future = kifiF flatMap { kifiHitsOpt =>
              val kifiHits = kifiHitsOpt.getOrElse(Seq.empty).map(h => (SocialNetworks.FORTYTWO, h)).sorted(hitOrd)
              zHits ++= kifiHits.takeWhile(t => t._2.score == 0)
              if (zHits.length >= n) {
                val res = zHits.take(n)
                log.infoP(s"short-circuit (social+kifi) res=${res.mkString(",")}")
                Future.successful(Option(res))
              } else {
                abookF flatMap { abookHitsOpt =>
                  val abookHits = abookHitsOpt.getOrElse(Seq.empty).map(h => (SocialNetworks.EMAIL, h)).sorted(hitOrd)
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
            future
          }
          topF
        }
    }
  }



  def searchWithInviteStatus(userId:Id[User], query:String, limit:Option[Int], pictureUrl:Boolean, filterJoinedUsers:Boolean):Future[Seq[ConnectionWithInviteStatus]] = {
    implicit val prefix = LogPrefix(s"searchWIS($userId,$query,$limit)")

    val socialInvitesF = db.readOnlyAsync { implicit ro =>
      invitationRepo.getSocialInvitesBySenderId(userId) // not cached
    }
    val emailInvitesF = db.readOnlyAsync { implicit ro =>
      invitationRepo.getEmailInvitesBySenderId(userId)
    }

    val q = query.trim
    if (q.length == 0) Future.successful(Seq.empty[ConnectionWithInviteStatus])
    else {
      val topF: Future[Option[Seq[(SocialNetworkType, TypeaheadHit[_])]]] = aggregate(userId, q, limit, filterJoinedUsers)
      topF flatMap { topOpt =>
        topOpt match {
          case None => Future.successful(Seq.empty[ConnectionWithInviteStatus])
          case Some(top) => {
            for {
              socialInvites <- socialInvitesF
              emailInvites  <- emailInvitesF
            } yield {
              val socialInvitesMap = socialInvites.map{ inv => inv.recipientSocialUserId.get -> inv }.toMap // overhead
              val emailInvitesMap  = emailInvites.map{ inv => inv.recipientEContactId.get -> inv }.toMap
              val resWithStatus = top map { case(snType, hit) =>
                snType match {
                  case SocialNetworks.EMAIL =>
                    val e = hit.info.asInstanceOf[EContact]
                    val status = emailInvitesMap.get(e.id.get) map { inv =>
                      inv.state match {
                        case InvitationStates.ACCEPTED | InvitationStates.JOINED => "joined" // check db
                        case InvitationStates.INACTIVE => ""
                        case _ => "invited"
                      }
                    } getOrElse ""
                    ConnectionWithInviteStatus(e.name.getOrElse(""), hit.score, SocialNetworks.EMAIL.name, None, emailId(e.email), status)
                  case SocialNetworks.FACEBOOK | SocialNetworks.LINKEDIN =>
                    val sci = hit.info.asInstanceOf[SocialUserBasicInfo]
                    val status = socialInvitesMap.get(sci.id) map { inv =>
                      inv.state match {
                        case InvitationStates.ACCEPTED | InvitationStates.JOINED => // consider airbrake
                          val msg = s"Invitation Inconsistency for invite=${inv} info=${sci}"
                          systemAdminMailSender.sendMail(ElectronicMail(from = EmailAddresses.RAY,
                            to = Seq(EmailAddresses.RAY),
                            category = NotificationCategory.System.ADMIN, subject = msg, htmlBody = msg))
                          "joined"
                        case InvitationStates.INACTIVE => ""
                        case _ => "invited"
                      }
                    } getOrElse ""
                    ConnectionWithInviteStatus(sci.fullName, hit.score, sci.networkType.name, if (pictureUrl) sci.getPictureUrl(75, 75) else None, socialId(sci), status)
                  case SocialNetworks.FORTYTWO =>
                    val u = hit.info.asInstanceOf[User]
                    val picUrl = if (pictureUrl) u.pictureName else None
                    ConnectionWithInviteStatus(u.fullName, hit.score, SocialNetworks.FORTYTWO.name, picUrl, s"fortytwo/${u.externalId}", "joined")
                  case SocialNetworks.FORTYTWO_NF =>
                    val bu = hit.info.asInstanceOf[BasicUser]
                    val name = s"${bu.firstName} ${bu.lastName}".trim // if not good enough, lookup User
                    val picUrl = if (pictureUrl) Some(bu.pictureName) else None
                    ConnectionWithInviteStatus(name, hit.score, snType.name, picUrl, s"fortytwo/${bu.externalId}", "joined")
                }
              }
              val res = limit.map{ n =>
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

}
