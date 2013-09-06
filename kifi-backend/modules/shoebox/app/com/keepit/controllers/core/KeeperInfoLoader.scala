package com.keepit.controllers.core

import scala.concurrent.Await
import scala.concurrent.duration._

import org.joda.time.DateTime

import com.google.inject.{Inject, Singleton}
import com.keepit.classify.{Domain, DomainClassifier, DomainRepo}
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.social._
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.search.SearchServiceClient

import play.api.libs.functional.syntax._
import play.api.libs.json._
import com.keepit.social.{ThreadInfo, CommentWithBasicUser, BasicUser}
import com.keepit.common.net.URI

case class KeeperInfo1(  // information needed immediately when a page is visited
    kept: Option[String],
    position: Option[JsObject],
    neverOnSite: Boolean,
    sensitive: Boolean)

object KeeperInfo1 {
  implicit val writesKeeperInfo1 = (
    (__ \ 'kept).writeNullable[String] and
    (__ \ 'position).writeNullable[JsObject] and
    (__ \ 'neverOnSite).writeNullable[Boolean].contramap[Boolean](Some(_).filter(identity)) and
    (__ \ 'sensitive).writeNullable[Boolean].contramap[Boolean](Some(_).filter(identity))
  )(unlift(KeeperInfo1.unapply))
}

case class KeeperInfo2(  // supplemental information
  shown: Boolean,
  keepers: Seq[BasicUser],
  keeps: Int
)

object KeeperInfo2 {
  implicit val writesKeeperInfo2 = new Writes[KeeperInfo2] {  // TODO: rewrite fancy :D
    def writes(o: KeeperInfo2): JsValue =
      JsObject(Seq[Option[(String, JsValue)]](
        if (o.shown) Some("shown" -> JsBoolean(true)) else None,
        if (o.keepers.nonEmpty) Some("keepers" -> Json.toJson(o.keepers)) else None,
        if (o.keeps > 0) Some("keeps" -> JsNumber(o.keeps)) else None)
      .flatten)
  }
}

@Singleton
class KeeperInfoLoader @Inject() (
    db: Database,
    normalizedURIRepo: NormalizedURIRepo,
    domainRepo: DomainRepo,
    userToDomainRepo: UserToDomainRepo,
    userRepo: UserRepo,
    followRepo: FollowRepo,
    bookmarkRepo: BookmarkRepo,
    commentReadRepo: CommentReadRepo,
    commentWithBasicUserRepo: CommentWithBasicUserRepo,
    domainClassifier: DomainClassifier,
    basicUserRepo: BasicUserRepo,
    userExperimentRepo: UserExperimentRepo,
    historyTracker: SliderHistoryTracker,
    searchClient: SearchServiceClient) {

  def load1(userId: Id[User], normalizedUri: String): KeeperInfo1 = {
    val (domain, bookmark, position, neverOnSite, host) = db.readOnly { implicit session =>
      val bookmark: Option[Bookmark] = normalizedURIRepo.getByUri(normalizedUri).flatMap { uri =>
        bookmarkRepo.getByUriAndUser(uri.id.get, userId)
      }
      val host: Option[String] = URI.parse(normalizedUri).get.host.map(_.name)
      val domain: Option[Domain] = host.flatMap(domainRepo.get(_))
      val (position, neverOnSite): (Option[JsObject], Boolean) = domain.map { dom =>
        (userToDomainRepo.get(userId, dom.id.get, UserToDomainKinds.KEEPER_POSITION).map(_.value.get.as[JsObject]),
         userToDomainRepo.exists(userId, dom.id.get, UserToDomainKinds.NEVER_SHOW))
      }.getOrElse((None, false))
      (domain, bookmark, position, neverOnSite, host)
    }

    val userIsSensitive = db.readOnly { implicit s =>
      !userExperimentRepo.hasExperiment(userId, ExperimentTypes.NOT_SENSITIVE)
    }
    val sensitive = userIsSensitive &&
      (domain.flatMap(_.sensitive) orElse host.flatMap(domainClassifier.isSensitive(_).right.toOption) getOrElse false)
    KeeperInfo1(bookmark.map { b => if (b.isPrivate) "private" else "public" }, position, neverOnSite, sensitive)
  }

  def load2(userId: Id[User], normalizedUri: String): KeeperInfo2 = {
    val (nUri, shown) = {
      val nUri = db.readOnly { implicit s => normalizedURIRepo.getByUri(normalizedUri) }
      nUri match {
        case Some(uri) =>
          val shown = historyTracker.getMultiHashFilter(userId).mayContain(uri.id.get.id)
          (nUri, shown)
        case None =>
          (None, false)
      }
    }
    val (keepers, keeps) = nUri map { uri =>
      val sharingUserInfo = Await.result(searchClient.sharingUserInfo(userId, uri.id.get), Duration.Inf)
      val keepers = db.readOnly { implicit session =>
        sharingUserInfo.sharingUserIds.map(basicUserRepo.load).toSeq
      }
      (keepers, sharingUserInfo.keepersEdgeSetSize)
    } getOrElse (Nil, 0)

    KeeperInfo2(shown, keepers, keeps)
  }
}
