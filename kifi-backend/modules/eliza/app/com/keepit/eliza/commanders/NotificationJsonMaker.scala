package com.keepit.eliza.commanders

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.store.{ S3ImageConfig, ImageSize }
import com.keepit.eliza.model.UserThreadRepo.RawNotification
import com.keepit.model.{ NormalizedURI }
import com.keepit.rover.RoverServiceClient
import com.keepit.rover.model.RoverUriSummary
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.social.{ BasicUserLikeEntity, BasicUser }
import com.keepit.common.db.Id

import play.api.libs.json._
import scala.concurrent.{ ExecutionContext, Future }
import com.keepit.common.core._

case class NotificationJson(obj: JsObject) extends AnyVal

/** Makes `NotificationJson` from `RawNotification` */
@Singleton
private[commanders] class NotificationJsonMaker @Inject() (
    shoebox: ShoeboxServiceClient,
    rover: RoverServiceClient,
    implicit val imageConfig: S3ImageConfig,
    implicit val executionContext: ExecutionContext) {

  private val idealImageSize = ImageSize(65, 95)

  def makeOne(rawNotification: RawNotification, includeUriSummary: Boolean = false): Future[NotificationJson] = {
    make(Seq(rawNotification), includeUriSummary).imap(_.head)
  }

  def make(rawNotifications: Seq[RawNotification], includeUriSummary: Boolean = false): Future[Seq[NotificationJson]] = {
    val futureSummariesByUriId: Future[Map[Id[NormalizedURI], RoverUriSummary]] = {
      if (includeUriSummary) rover.getUriSummaryByUris(rawNotifications.flatMap(_._3).toSet) // todo(???): if title and description are not used, switch to getImagesByUris
      else Future.successful(Map.empty)
    }
    Future.sequence(rawNotifications.map { n =>
      val futureUriSummary = n._3.map(uriId => futureSummariesByUriId.map(_.get(uriId))) getOrElse Future.successful(None)
      makeOpt(n, futureUriSummary)
    }.flatten)
  }

  // including URI summaries is optional because it's currently slow and only used by the canary extension (new design)
  private def makeOpt(raw: RawNotification, futureUriSummary: Future[Option[RoverUriSummary]]): Option[Future[NotificationJson]] = {
    raw._1 match {
      case o: JsObject =>
        val authorFut = author(o \ "author")
        val participantsFut = participants(o \ "participants")
        val futureUriSummaryJson = futureUriSummary.imap(_.map { summary =>
          val image = summary.images.get(idealImageSize)
          Json.obj(
            "title" -> summary.article.title,
            "description" -> summary.article.description,
            "imageUrl" -> image.map(_.path.getUrl),
            "imageWidth" -> image.map(_.size.width),
            "imageHeight" -> image.map(_.size.height)
          )
        })
        val jsonFut = for {
          author <- authorFut
          participants <- participantsFut
          uriSummary <- futureUriSummaryJson
        } yield NotificationJson(
          o ++ unread(o, raw._2)
            ++ Json.obj("author" -> author)
            ++ (if (!participants.isEmpty) Json.obj("participants" -> participants) else Json.obj())
            ++ (if (uriSummary.isDefined) Json.obj("uriSummary" -> uriSummary) else Json.obj())
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

  private def updateBasicUser(basicUser: BasicUser): Future[BasicUser] = {
    shoebox.getUserOpt(basicUser.externalId) map { userOpt =>
      userOpt.map(BasicUser.fromUser).getOrElse(basicUser)
    } recover {
      case _ =>
        basicUser
    }
  }

}
