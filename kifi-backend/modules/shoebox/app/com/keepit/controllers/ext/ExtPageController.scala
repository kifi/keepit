package com.keepit.controllers.ext

import com.google.inject.Inject

import com.keepit.classify.{Domain, DomainClassifier, DomainRepo}
import com.keepit.common.controller.{ShoeboxServiceController, BrowserExtensionController, ActionAuthenticator}
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.net.URI
import com.keepit.common.social._
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.normalizer.NormalizationService
import com.keepit.search.SearchServiceClient
import com.keepit.social.BasicUser

import play.api.Play.current
import play.api.libs.functional.syntax._
import play.api.libs.json._

import scala.concurrent.Await
import scala.concurrent.duration._

class ExtPageController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  db: Database,
  normalizedURIRepo: NormalizedURIRepo,
  normalizationService: NormalizationService,
  domainRepo: DomainRepo,
  userToDomainRepo: UserToDomainRepo,
  bookmarkRepo: BookmarkRepo,
  keepToCollectionRepo: KeepToCollectionRepo,
  collectionRepo: CollectionRepo,
  domainClassifier: DomainClassifier,
  basicUserRepo: BasicUserRepo,
  historyTracker: SliderHistoryTracker,
  searchClient: SearchServiceClient)
  extends BrowserExtensionController(actionAuthenticator) with ShoeboxServiceController {

  case class KeeperInfo(
    normalized: String,
    kept: Option[String],
    tags: Seq[SendableTag],
    position: Option[JsObject],
    neverOnSite: Boolean,
    sensitive: Boolean,
    shown: Boolean,
    keepers: Seq[BasicUser],
    keeps: Int)

  object KeeperInfo {
    implicit val writesKeeperInfo = (
      (__ \ 'normalized).write[String] and
      (__ \ 'kept).writeNullable[String] and
      (__ \ 'tags).writeNullable[Seq[SendableTag]].contramap[Seq[SendableTag]](Some(_).filter(_.nonEmpty)) and
      (__ \ 'position).writeNullable[JsObject] and
      (__ \ 'neverOnSite).writeNullable[Boolean].contramap[Boolean](Some(_).filter(identity)) and
      (__ \ 'sensitive).writeNullable[Boolean].contramap[Boolean](Some(_).filter(identity)) and
      (__ \ 'shown).writeNullable[Boolean].contramap[Boolean](Some(_).filter(identity)) and
      (__ \ 'keepers).writeNullable[Seq[BasicUser]].contramap[Seq[BasicUser]](Some(_).filter(_.nonEmpty)) and
      (__ \ 'keeps).writeNullable[Int].contramap[Int](Some(_).filter(_ > 0))
    )(unlift(KeeperInfo.unapply))
  }

  def getPageDetails() = AuthenticatedJsonToJsonAction { request =>
    val userId = request.userId
    val url = (request.body \ "url").as[String]

    val (nUriStr, nUri, domain, bookmark, tags, position, neverOnSite, host) = db.readOnly { implicit session =>
      val (nUriStr, nUri) = normalizedURIRepo.getByUriOrPrenormalize(url) match {
        case Left(nUri) => (nUri.url, Some(nUri))
        case Right(pUri) => (pUri, None)
      }
      val bookmark: Option[Bookmark] = nUri.flatMap { uri =>
        bookmarkRepo.getByUriAndUser(uri.id.get, userId)
      }
      val tags: Seq[Collection] = bookmark.map { bm =>
        keepToCollectionRepo.getCollectionsForBookmark(bm.id.get).map { collId =>
          collectionRepo.get(collId)
        }
      }.getOrElse(Seq())

      val host: Option[String] = URI.parse(nUriStr).get.host.map(_.name)
      val domain: Option[Domain] = host.flatMap(domainRepo.get(_))
      val (position, neverOnSite): (Option[JsObject], Boolean) = domain.map { dom =>
        (userToDomainRepo.get(userId, dom.id.get, UserToDomainKinds.KEEPER_POSITION).map(_.value.get.as[JsObject]),
         userToDomainRepo.exists(userId, dom.id.get, UserToDomainKinds.NEVER_SHOW))
      }.getOrElse((None, false))
      (nUriStr, nUri, domain, bookmark, tags, position, neverOnSite, host)
    }

    val sensitive: Boolean = !request.experiments.contains(ExperimentType.NOT_SENSITIVE) &&
      (domain.flatMap(_.sensitive) orElse host.flatMap(domainClassifier.isSensitive(_).right.toOption) getOrElse false)

    val shown = nUri.map { uri => historyTracker.getMultiHashFilter(userId).mayContain(uri.id.get.id) } getOrElse false

    val (keepers, keeps) = nUri map { uri =>
      val sharingUserInfo = Await.result(searchClient.sharingUserInfo(userId, uri.id.get), Duration.Inf)
      val keepers: Seq[BasicUser] = db.readOnly { implicit session =>
        sharingUserInfo.sharingUserIds.map(basicUserRepo.load).toSeq
      }
      (keepers, sharingUserInfo.keepersEdgeSetSize)
    } getOrElse (Nil, 0)

    Ok(Json.toJson(KeeperInfo(
      nUriStr, bookmark.map { b => if (b.isPrivate) "private" else "public" }, tags.map(SendableTag.from),
      position, neverOnSite, sensitive, shown, keepers, keeps)))
  }

}
