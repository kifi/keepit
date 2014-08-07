package com.keepit.eliza.commanders

import com.google.inject.Inject
import com.keepit.common.store.ImageSize
import com.keepit.eliza.model.UserThreadRepo.RawNotification
import com.keepit.model.{ ImageType, URISummary, URISummaryRequest }
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.social.{ BasicUserLikeEntity, BasicUser }

import play.api.libs.json.{ JsValue, Json, JsObject }
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.concurrent.Future

private[commanders] case class NotificationJson(obj: JsObject) extends AnyVal

/** Makes `NotificationJson` from `RawNotification` */
private[commanders] class NotificationJsonMaker @Inject() (
    shoebox: ShoeboxServiceClient) {

  def makeOne(rawNotification: RawNotification, includeUriSummary: Boolean = false): Future[NotificationJson] = {
    makeOpt(rawNotification, includeUriSummary).get
  }

  def make(rawNotifications: Seq[RawNotification], includeUriSummary: Boolean = false): Future[Seq[NotificationJson]] = {
    Future.sequence(rawNotifications.map { n => makeOpt(n, includeUriSummary) }.flatten)
  }

  // including URI summaries is optional because it's currently slow and only used by the canary extension (new design)
  private def makeOpt(raw: RawNotification, includeUriSummary: Boolean): Option[Future[NotificationJson]] = {
    raw._1 match {
      case o: JsObject =>
        val authorFut = author(o \ "author")
        val participantsFut = participants(o \ "participants")
        val uriSummaryFut = if (includeUriSummary) uriSummary(o \ "url") else Future.successful(None)
        val jsonFut = for {
          author <- authorFut
          participants <- participantsFut
          uriSummary <- uriSummaryFut
        } yield NotificationJson(
          o ++ unread(o, raw._2)
            ++ Json.obj("author" -> author)
            ++ Json.obj("participants" -> participants)
            ++ (if (includeUriSummary) Json.obj("uriSummary" -> uriSummary) else Json.obj())
        )
        Some(jsonFut)
      case _ =>
        None
    }
  }

  private def unread(o: JsObject, unread: Boolean): JsObject = {
    if (unread) {
      Json.obj(
        "unread" -> true,
        "unreadMessages" -> math.max(1, (o \ "unreadMessages").asOpt[Int].getOrElse(0)),
        "unreadAuthors" -> math.max(1, (o \ "unreadAuthors").asOpt[Int].getOrElse(0)))
    } else {
      Json.obj(
        "unread" -> false,
        "unreadMessages" -> 0,
        "unreadAuthors" -> 0)
    }
  }

  private def author(value: JsValue): Future[Option[BasicUserLikeEntity]] = {
    value.asOpt[BasicUserLikeEntity] match {
      case Some(bu: BasicUser) => updateBasicUser(bu) map Some.apply
      case x => Future.successful(x)
    }
  }

  private def participants(value: JsValue): Future[Seq[BasicUserLikeEntity]] = {
    value.asOpt[Seq[BasicUserLikeEntity]] map { participants =>
      Future.sequence(participants.map { participant =>
        participant match {
          case p: BasicUser => updateBasicUser(p)
          case p => Future.successful(p)
        }
      })
    } getOrElse {
      Future.successful(Seq.empty)
    }
  }

  private def uriSummary(value: JsValue): Future[Option[URISummary]] = {
    value.asOpt[String] map { url =>
      // TODO: utilize memcache, which will probably require dropping min size requirement here.
      // We'll also need the Id[NormalizedURI], since arbitrary URLs are too long to be memcache keys.
      shoebox.getUriSummary(
        URISummaryRequest(
          url = url,
          imageType = ImageType.ANY,
          minSize = ImageSize(65, 95),
          withDescription = false,
          waiting = true,
          silent = false)) map Some.apply
    } getOrElse {
      Future.successful(None)
    }
  }

  private def updateBasicUser(basicUser: BasicUser): Future[BasicUser] = {
    shoebox.getUserOpt(basicUser.externalId) map { userOpt =>
      userOpt.map(BasicUser.fromUser).getOrElse(basicUser)
    } recover {
      case _ =>
        basicUser
    }
  }

}
