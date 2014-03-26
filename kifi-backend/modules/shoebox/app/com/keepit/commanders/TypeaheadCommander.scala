package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.common.healthcheck.{SystemAdminMailSender, AirbrakeNotifier}
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.model._
import com.keepit.abook.ABookServiceClient
import com.keepit.typeahead.socialusers.SocialUserTypeahead
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

case class ConnectionWithInviteStatus(label:String, score:Int, networkType:String, image:Option[String], value:String, status:String, externalId:Option[ExternalId[User]] = None)

object ConnectionWithInviteStatus {
  implicit val format = (
      (__ \ 'label).format[String] and
      (__ \ 'score).format[Int] and
      (__ \ 'networkType).format[String] and
      (__ \ 'image).formatNullable[String] and
      (__ \ 'value).format[String] and
      (__ \ 'status).format[String] and
      (__ \ 'externalId).formatNullable(ExternalId.format[User])
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

  val snMap:Map[SocialNetworkType, Int] = Map(SocialNetworks.FACEBOOK -> 0, SocialNetworks.LINKEDIN -> 1, SocialNetworks.FORTYTWO -> 2, SocialNetworks.FORTYTWO_NF -> 3, SocialNetworks.EMAIL -> 4)

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
      case SocialNetworks.FORTYTWO => false // for now, we use search API instead
      case _ => true
    }
  }

  def searchWithInviteStatus(userId:Id[User], query:String, limit:Option[Int], pictureUrl:Boolean, filterJoinedUsers:Boolean, addNFUsers:Boolean):Future[Seq[ConnectionWithInviteStatus]] = {
    val socialInvitesF = db.readOnlyAsync { implicit ro =>
      invitationRepo.getSocialInvitesBySenderId(userId) // not cached
    }
    val emailInvitesF = db.readOnlyAsync { implicit ro =>
      invitationRepo.getEmailInvitesBySenderId(userId)
    }

    val q = query.trim
    if (q.length == 0) Future.successful(Seq.empty[ConnectionWithInviteStatus])
    else {
      val socialF = socialUserTypeahead.asyncTopN(userId, q, limit map(_ * 3))(TypeaheadHit.defaultOrdering[SocialUserBasicInfo]) map { resOpt =>
        resOpt map { res => res.collect { case hit if includeHit(hit, filterJoinedUsers) => hit } }
      }
      val usersF = searchClient.userTypeahead(userId, q, limit.getOrElse(5), filter = "f")
      val nfUsersF = searchClient.userTypeahead(userId, q, limit.getOrElse(5), filter = "nf")
      val abookF  = econtactTypeahead.asyncTopN(userId, q, limit)(TypeaheadHit.defaultOrdering[EContact])
      val topF = socialF flatMap { socialHitsOpt =>
        if (limit.exists(n => (socialHitsOpt.exists(res => (res.length > n && res.last.score == 0))))) {
          socialF map { socialHitsOpt =>
            socialHitsOpt map { hits =>
              val res = hits.map(h => (h.info.networkType, h)).sorted(hitOrd)
              log.info(s"[searchWIS($userId,$query,$limit)] short-circuit res(len=${res.length}):${res.mkString(",")}")
              res
            }
          }
        } else {
          for { // simple but not efficient -- profiling needed
            userHits <- usersF
            nfUserHits <- nfUsersF
            abookHitsOpt <- abookF
          } yield {
            val socialHits    = socialHitsOpt.getOrElse(Seq.empty)
            val socialHitsTup = socialHits.map(h => (h.info.networkType, h))
            val userHitsTup   = userHits.map(h => (SocialNetworks.FORTYTWO, h))
            val nfUserHitsTup = nfUserHits.map(h => (SocialNetworks.FORTYTWO_NF, h))
            val abookHits     = abookHitsOpt.getOrElse(Seq.empty)
            val abookHitsTup  = abookHits.map(h => (SocialNetworks.EMAIL, h))
            log.info(s"[searchWIS($userId,$query,$limit)] social(len=${socialHits.length}):${socialHits.mkString(",")} users(len=${userHits.length}):${userHits.mkString(",")} nf(len=${nfUserHits.length}):${nfUserHits.mkString(",")} abook(len=${abookHits.length}):${abookHits.mkString(",")}")
            val combined = (socialHitsTup ++ userHitsTup ++ nfUserHitsTup ++ abookHitsTup)
            log.info(s"[searchWIS($userId,$query,$limit)] combined(len=${combined.length}):${combined.mkString(",")}")
            val sorted = combined.sorted(hitOrd)
            log.info(s"[searchWIS($userId,$query,$limit)] sorted(len=${sorted.length}):${sorted.mkString(",")}")
            Some(sorted)
          }
        }
      }


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
                  case SocialNetworks.FORTYTWO | SocialNetworks.FORTYTWO_NF =>
                    val bu = hit.info.asInstanceOf[BasicUser]
                    val name = s"${bu.firstName} ${bu.lastName}".trim // if not good enough, lookup User
                    val picUrl = if (pictureUrl) {
                      Some(bu.pictureName) // can get full url (tbd)
                    } else None
                    ConnectionWithInviteStatus(name, hit.score, SocialNetworks.FORTYTWO_NF.name, picUrl, s"fortytwo/${bu.externalId}", "joined", Some(bu.externalId))
                }
              }
              val filtered = if (filterJoinedUsers) resWithStatus.filter(cis => !(cis.status == "joined" && cis.networkType != SocialNetworks.FORTYTWO.name)) else resWithStatus
              val res = limit.map{ n =>
                filtered.take(n)
              }.getOrElse(filtered)
              log.info(s"[searchWIS($userId,$query,$limit)] res=${res.mkString(",")}")
              res
            }
          }
        }
      }
    }
  }

}
