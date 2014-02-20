package com.keepit.controllers.website

import com.google.inject.Inject
import com.keepit.common.db.slick.Database
import com.keepit.typeahead.socialusers.SocialUserTypeahead
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.controller.{ShoeboxServiceController, ActionAuthenticator, WebsiteController}
import com.keepit.common.db.Id
import com.keepit.model._
import scala.concurrent.{Await, Future}
import com.keepit.typeahead.{PrefixFilter, TypeaheadHit}
import com.keepit.common.db.slick.DBSession.RSession
import play.api.libs.json._
import play.api.libs.functional.syntax._
import com.keepit.abook.ABookServiceClient
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.concurrent.duration.Duration

case class InviteStatus(label:String, image:Option[String], value:String, status:String)

object InviteStatus {
  implicit val format = (
      (__ \ 'label).format[String] and
      (__ \ 'image).formatNullable[String] and
      (__ \ 'value).format[String] and
      (__ \ 'status).format[String]
    )(InviteStatus.apply _, unlift(InviteStatus.unapply))
}

class TypeaheadController @Inject() (
  db: Database,
  airbrake: AirbrakeNotifier,
  actionAuthenticator: ActionAuthenticator,
  socialUserConnectionsCache: SocialUserConnectionsCache,
  socialConnectionRepo: SocialConnectionRepo,
  socialUserInfoRepo: SocialUserInfoRepo,
  invitationRepo: InvitationRepo,
  abookServiceClient: ABookServiceClient,
  socialUserTypeahead:SocialUserTypeahead
) extends WebsiteController(actionAuthenticator) with ShoeboxServiceController with Logging {

  def queryContacts(userId:Id[User], search: Option[String], after:Option[String], limit: Int):Future[Seq[JsObject]] = { // TODO: optimize
    @inline def mkId(email:String) = s"email/$email"
    @inline def getEInviteStatus(contactIdOpt:Option[Id[EContact]]):String = { // todo: batch
      contactIdOpt flatMap { contactId =>
        db.readOnly { implicit s =>
          invitationRepo.getBySenderIdAndRecipientEContactId(userId, contactId) map { inv =>
            if (inv.state != InvitationStates.INACTIVE) "invited" else ""
          }
        }
      } getOrElse ""
    }

    abookServiceClient.prefixQuery(userId, limit, search, after) map { paged =>
      val objs = paged.take(limit).map { e =>
        Json.obj("label" -> JsString(e.name.getOrElse("")), "value" -> mkId(e.email), "status" -> getEInviteStatus(e.id))
      }
      log.info(s"[queryContacts(id=$userId)] res(len=${objs.length}):${objs.mkString.take(200)}")
      objs
    }
  }

  implicit val hitOrdering = TypeaheadHit.defaultOrdering[SocialUserBasicInfo]

  def querySocialConnections(userId:Id[User], search:Option[String], network:Option[String], after:Option[String], limit:Int):Seq[(SocialUserBasicInfo, String)] = {
    @inline def socialIdString(sci: SocialUserBasicInfo) = s"${sci.networkType}/${sci.socialId.id}"

    def getWithInviteStatus(sci: SocialUserBasicInfo)(implicit s: RSession): (SocialUserBasicInfo, String) = {
      sci -> sci.userId.map(_ => "joined").getOrElse {
        invitationRepo.getBySenderIdAndRecipientSocialUserId(userId, sci.id) collect {
          case inv if inv.state == InvitationStates.ACCEPTED || inv.state == InvitationStates.JOINED => {
            // This is a hint that that cache may be stale as userId should be set
            socialUserInfoRepo.getByUser(userId).foreach { socialUser =>
              socialUserConnectionsCache.remove(SocialUserConnectionsKey(socialUser.id.get))
            }
            "joined"
          }
          case inv if inv.state != InvitationStates.INACTIVE => "invited"
        } getOrElse ""
      }
    }

    val connections = {
      val filteredConnections = search match {
        case Some(query) if query.trim.length > 0 => {
          val prefixFilter = socialUserTypeahead.getPrefixFilter(userId) match {
            case Some(filter) => filter
            case None =>
              log.warn(s"[querySocialConnections($userId,$search,$network,$after,$limit)] NO FILTER. Build")
              Await.result(socialUserTypeahead.build(userId), Duration.Inf)
          }
          // socialUserTypeahead.search(userId, query) getOrElse Seq.empty[SocialUserBasicInfo] // todo(ray): revisit
          val ids = prefixFilter.filterBy(PrefixFilter.tokenizeNormalizedName(PrefixFilter.normalize(query)))
          val res = db.readOnly { implicit ro => socialUserInfoRepo.getSocialUserBasicInfos(ids).valuesIterator.toVector }
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
      log.info(s"[queryConnections($userId,$search,$network,$after,$limit)] filteredConns=${filteredConnections.mkString(",")}")

      val paged = (after match {
        case Some(id) => filteredConnections.dropWhile(socialIdString(_) != id) match {
          case hd +: tl => tl
          case tl => tl
        }
        case None => filteredConnections
      }).take(limit)

      db.readOnly { implicit ro =>
        paged.map(getWithInviteStatus)
      }
    }

    connections
  }

  def queryConnections(userId:Id[User], search: Option[String], network: Option[String], after: Option[String], limit: Int):Future[Seq[JsObject]] = { // todo: convert to objects
  val contactsF = if (network.isDefined && network.get == "email") { // todo: revisit
      queryContacts(userId, search, after, limit)
    } else Future.successful(Seq.empty[JsObject])
    @inline def socialIdString(sci: SocialUserBasicInfo) = s"${sci.networkType}/${sci.socialId.id}"

    def getWithInviteStatus(sci: SocialUserBasicInfo)(implicit s: RSession): (SocialUserBasicInfo, String) = {
      sci -> sci.userId.map(_ => "joined").getOrElse {
        invitationRepo.getBySenderIdAndRecipientSocialUserId(userId, sci.id) collect {
          case inv if inv.state == InvitationStates.ACCEPTED || inv.state == InvitationStates.JOINED => {
            // This is a hint that that cache may be stale as userId should be set
            socialUserInfoRepo.getByUser(userId).foreach { socialUser =>
              socialUserConnectionsCache.remove(SocialUserConnectionsKey(socialUser.id.get))
            }
            "joined"
          }
          case inv if inv.state != InvitationStates.INACTIVE => "invited"
        } getOrElse ""
      }
    }

    val connections = {
      val filteredConnections = search match {
        case Some(query) if query.trim.length > 0 => {
          val prefixFilter = socialUserTypeahead.getPrefixFilter(userId) match {
            case Some(filter) => filter
            case None => socialUserTypeahead.build(userId)
          }
          socialUserTypeahead.search(userId, query) getOrElse Seq.empty[SocialUserBasicInfo]
        }
        case None => {
          val infos = db.readOnly { implicit s =>
            socialConnectionRepo.getSocialConnectionInfosByUser(userId).filterKeys(networkType => network.forall(_ == networkType.name))
          }
          infos.values.flatten.toSeq
        }
      }
      log.info(s"[queryConnections($userId,$search,$network,$after,$limit)] filteredConns=${filteredConnections.mkString(",")}")

      val paged = (after match {
        case Some(id) => filteredConnections.dropWhile(socialIdString(_) != id) match {
          case hd +: tl => tl
          case tl => tl
        }
        case None => filteredConnections
      }).take(limit)

      db.readOnly { implicit ro =>
        paged.map(getWithInviteStatus)
      }
    }

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


  def getAllConnections(search: Option[String], network: Option[String], after: Option[String], limit: Int) = JsonAction.authenticatedAsync {  request =>
    queryConnections(request.userId, search, network, after, limit) map { res =>
      Ok(Json.toJson(res))
    }
  }

}
