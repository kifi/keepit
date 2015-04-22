package com.keepit.eliza.commanders

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.store.ImageSize
import com.keepit.eliza.model.UserThreadRepo.RawNotification
import com.keepit.model.{ ImageType, URISummary, URISummaryRequest, NormalizedURI }
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.social.{ BasicUserLikeEntity, BasicUser }
import com.keepit.common.cache.CacheStatistics
import com.keepit.common.db.Id
import com.keepit.common.logging.AccessLog
import com.keepit.common.cache.{ JsonCacheImpl, FortyTwoCachePlugin, Key }
import com.keepit.common.concurrent.ReactiveLock
import com.keepit.common.akka.SafeFuture
import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess

import play.api.libs.json.{ JsValue, Json, JsObject }
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.concurrent.Future
import scala.concurrent.duration.Duration

case class NotificationJson(obj: JsObject) extends AnyVal

/** Makes `NotificationJson` from `RawNotification` */
@Singleton
private[commanders] class NotificationJsonMaker @Inject() (
    shoebox: ShoeboxServiceClient,
    summaryCache: InboxUriSummaryCache) {

  private val uriSummaryRequestLimiter = new ReactiveLock(2)

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
        val uriSum = if (includeUriSummary) uriSummary(o \ "url", raw._3) else None
        val jsonFut = for {
          author <- authorFut
          participants <- participantsFut
        } yield NotificationJson(
          o ++ unread(o, raw._2)
            ++ Json.obj("author" -> author)
            ++ (if (!participants.isEmpty) Json.obj("participants" -> participants) else Json.obj())
            ++ (if (includeUriSummary) Json.obj("uriSummary" -> uriSum) else Json.obj())
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

  private def uriSummary(value: JsValue, uriIdOpt: Option[Id[NormalizedURI]]): Option[URISummary] = {
    value.asOpt[String].flatMap { url =>
      uriIdOpt.flatMap { uriId =>
        val resultFut = summaryCache.getOrElseFuture(InboxUriSummaryCacheKey(uriId)) {
          new SafeFuture(fetchUriSummary(uriId, url), Some(s"Fetching URI summary ($uriId -> $url) for extension inbox"))
        }
        resultFut.value.flatMap(_.toOption)
      }
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

  private def fetchUriSummary(uriId: Id[NormalizedURI], url: String): Future[URISummary] = uriSummaryRequestLimiter.withLockFuture {
    shoebox.getUriSummary(
      URISummaryRequest(
        uriId = uriId,
        imageType = ImageType.IMAGE,
        minSize = ImageSize(65, 95),
        withDescription = false,
        waiting = true,
        silent = false
      )
    )
  }

}

case class InboxUriSummaryCacheKey(uriId: Id[NormalizedURI]) extends Key[URISummary] {
  override val version = 1
  val namespace = "inbox_uri_summary"
  def toKey(): String = uriId.id.toString
}

class InboxUriSummaryCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[InboxUriSummaryCacheKey, URISummary](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

