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
import play.api.libs.json.JsString
import scala.Some
import com.keepit.model.SocialUserConnectionsKey
import play.api.libs.json.JsObject
import play.api.libs.functional.syntax._
import play.api.libs.json.JsString
import scala.Some
import com.keepit.model.SocialUserConnectionsKey
import play.api.libs.json.JsObject

case class InviteStatus(label:String, image:Option[String], value:String, status:String)

object InviteStatus {
  implicit val format = (
    (__ \ 'label).format[String] and
      (__ \ 'image).formatNullable[String] and
      (__ \ 'value).format[String] and
      (__ \ 'status).format[String]
    )(InviteStatus.apply _, unlift(InviteStatus.unapply))
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

  implicit val fj = com.keepit.common.concurrent.ExecutionContext.fj

  def queryContacts(userId:Id[User], search: Option[String], after:Option[String], limit: Int):Future[Seq[JsObject]] = {
  @inline def mkId(email:String) = s"email/$email"
    abookServiceClient.prefixQuery(userId, limit, search, after) map { paged =>
      val allEmailInvites = db.readOnly { implicit ro =>
        invitationRepo.getEmailInvitesBySenderId(userId)
      }
      val invitesMap = new collection.mutable.HashMap[Id[EContact], Invitation]
      allEmailInvites.foreach { invite =>
        invitesMap += (invite.recipientEContactId.get -> invite)
      }
      val withStatus = paged map { e =>
        val status = invitesMap.get(e.id.get) map { inv =>
          if (inv.state != InvitationStates.INACTIVE) "invited" else ""
        } getOrElse ""
        (e, status)
      }

      val objs = withStatus.take(limit).map { case (e, status) =>
        Json.obj("label" -> JsString(e.name.getOrElse("")), "value" -> mkId(e.email), "status" -> status)
      }
      log.info(s"[queryContacts(id=$userId)] res(len=${objs.length}):${objs.mkString.take(200)}")
      objs
    }
  }

  implicit val hitOrdering = TypeaheadHit.defaultOrdering[SocialUserBasicInfo]
  def querySocialConnections(userId:Id[User], search:Option[String], network:Option[String], after:Option[String], limit:Int):Seq[(SocialUserBasicInfo, String)] = {
    @inline def socialIdString(sci: SocialUserBasicInfo) = s"${sci.networkType}/${sci.socialId.id}"
    val connections = {
      val filteredConnections = search match {
        case Some(query) if query.trim.length > 0 => {
          val infos = socialUserTypeahead.search(userId, query) getOrElse Seq.empty[SocialUserBasicInfo]
          val res = network match {
            case Some(networkType) => infos.filter(info => info.networkType.name == networkType)
            case None => infos.filter(info => info.networkType.name != SocialNetworks.FORTYTWO) // backward compatibility
          }
          log.info(s"[querySocialConnections($userId,$search,$network,$after,$limit)] res=${res.mkString(",")}")
          res
        }
        case None => {
          val infos = db.readOnly { implicit s =>
            socialConnectionRepo.getSocialConnectionInfosByUser(userId).filterKeys(networkType => network.forall(_ == networkType.name))
          }
          infos.values.flatten.toVector
        }
      }
      log.info(s"[queryConnections($userId,$search,$network,$after,$limit)] filteredConns(len=${filteredConnections.length});${filteredConnections.take(20).mkString(",")}")

      val paged = (after match {
        case Some(id) => filteredConnections.dropWhile(socialIdString(_) != id) match {
          case hd +: tl => tl
          case tl => tl
        }
        case None => filteredConnections
      }).take(limit)

      db.readOnly { implicit ro =>
        val allInvites = invitationRepo.getSocialInvitesBySenderId(userId)
        val invitesMap = new collection.mutable.HashMap[Id[SocialUserInfo], Invitation]
        allInvites.foreach { invite =>
          invitesMap += (invite.recipientSocialUserId.get -> invite)
        }
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

    connections
  }

  def queryConnections(userId:Id[User], search: Option[String], network: Option[String], after: Option[String], limit: Int):Future[Seq[JsObject]] = { // todo: convert to objects
  val contactsF = if (network.isDefined && network.get == "email") { // todo: revisit
      queryContacts(userId, search, after, limit)
    } else Future.successful(Seq.empty[JsObject])
    @inline def socialIdString(sci: SocialUserBasicInfo) = s"${sci.networkType}/${sci.socialId.id}"
    val connections = querySocialConnections(userId, search, network, after, limit)
    val jsConns: Seq[JsObject] = connections.map { conn =>
    //      val status = InviteStatus(conn._1.fullName, conn._1.getPictureUrl(75, 75), socialIdString(conn._1), conn._2) // todo
    //      Json.toJson[InviteStatus](status)
      Json.obj(
        "label" -> conn._1.fullName,
        "image" -> Json.toJson(conn._1.getPictureUrl(75, 75)),
        "value" -> socialIdString(conn._1),
        "status" -> conn._2
      )
    }
    contactsF map { jsContacts =>
      val jsCombined = jsConns ++ jsContacts
      log.info(s"[queryConnections(${userId})] jsContacts(sz=${jsContacts.size}) jsConns(sz=${jsConns.size})")
      jsCombined
    }
  }


}
