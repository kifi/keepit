package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.model._
import com.keepit.abook.ABookServiceClient
import com.keepit.typeahead.socialusers.SocialUserTypeahead
import com.keepit.common.db.Id
import com.keepit.social.SocialNetworks
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

case class ConnectionWithInviteStatus(label:String, image:Option[String], value:String, status:String)

object ConnectionWithInviteStatus {
  implicit val format = (
      (__ \ 'label).format[String] and
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
  socialUserTypeahead:SocialUserTypeahead
) extends Logging {

  implicit val fj = ExecutionContext.fj

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
    @inline def emailId(email:String) = s"email/$email"
    queryContacts(userId, search, limit) map { res =>
      res map { case (e, s) => ConnectionWithInviteStatus(e.name.getOrElse(""), None, emailId(e.email), s) }
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
    @inline def socialId(sci: SocialUserBasicInfo) = s"${sci.networkType}/${sci.socialId.id}"
    querySocial(userId, search, network, limit) map { case (c, s) =>
      ConnectionWithInviteStatus(c.fullName, if (pictureUrl) c.getPictureUrl(75, 75) else None, socialId(c), s)
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

}
