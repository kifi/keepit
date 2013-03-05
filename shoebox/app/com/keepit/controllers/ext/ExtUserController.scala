package com.keepit.controllers.ext

import com.keepit.classify.{Domain, DomainClassifier, DomainRepo, DomainStates}
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.net.URI
import com.keepit.model._
import com.keepit.serializer.UserWithSocialSerializer._
import com.keepit.serializer.BasicUserSerializer
import com.keepit.common.social._
import com.keepit.common.controller.BrowserExtensionController
import com.keepit.search.graph.URIGraph
import com.keepit.search.Lang
import com.keepit.search.MainSearcherFactory
import com.keepit.common.mail._

import scala.concurrent.Await
import play.api.libs.concurrent.Execution.Implicits._
import play.api.Play.current
import play.api.libs.json.{JsArray, JsBoolean, JsNumber, JsObject, JsString}
import scala.concurrent.duration._
import views.html

import com.google.inject.{Inject, Singleton}

@Singleton
class ExtUserController @Inject() (db: Database,
  normalizedURIRepo: NormalizedURIRepo, domainRepo: DomainRepo, userToDomainRepo: UserToDomainRepo, socialConnectionRepo: SocialConnectionRepo, userRepo: UserRepo,
  basicUserRepo: BasicUserRepo, followRepo: FollowRepo, bookmarkRepo: BookmarkRepo, commentRepo: CommentRepo,
  domainClassifier: DomainClassifier, uriGraph: URIGraph, userWithSocialRepo: UserWithSocialRepo)
    extends BrowserExtensionController {

  def getSliderInfo(url: String) = AuthenticatedJsonAction { request =>
    val userId = request.userId
    val (bookmark, following, socialUsers, numComments, numMessages, neverOnSite, sensitive) = db.readOnly {implicit s =>
      val nUri = normalizedURIRepo.getByNormalizedUrl(url)
      val host: Option[String] = URI.parse(url).get.host.map(_.name)
      val domain: Option[Domain] = host.flatMap(domainRepo.get(_))
      val neverOnSite: Option[UserToDomain] = domain.flatMap { domain =>
        userToDomainRepo.get(userId, domain.id.get, UserToDomainKinds.NEVER_SHOW)
      }
      val sensitive: Option[Boolean] =
        domain.flatMap(_.sensitive).orElse(host.flatMap(domainClassifier.isSensitive(_).right.toOption))

      nUri match {
        case Some(uri) =>
          val bookmark = bookmarkRepo.getByUriAndUser(uri.id.get, userId)
          val following = followRepo.get(userId, uri.id.get).isDefined

          val friendIds = socialConnectionRepo.getFortyTwoUserConnections(userId)
          val searcher = uriGraph.getURIGraphSearcher
          val friendEdgeSet = searcher.getUserToUserEdgeSet(userId, friendIds)
          val sharingUserIds = searcher.intersect(friendEdgeSet, searcher.getUriToUserEdgeSet(uri.id.get)).destIdSet - userId
          val socialUsers = sharingUserIds.map(u => userWithSocialRepo.toUserWithSocial(userRepo.get(u))).toSeq

          val numComments = commentRepo.getPublicCount(uri.id.get)
          val numMessages = commentRepo.getMessages(uri.id.get, userId).size

          (bookmark, following, socialUsers, numComments, numMessages, neverOnSite, sensitive)
        case None =>
          (None, false, Nil, 0, 0, neverOnSite, sensitive)
      }
    }

    Ok(JsObject(Seq(
        "kept" -> JsBoolean(bookmark.isDefined),
        "private" -> JsBoolean(bookmark.map(_.isPrivate).getOrElse(false)),
        "following" -> JsBoolean(following),
        "friends" -> userWithSocialSerializer.writes(socialUsers),
        "numComments" -> JsNumber(numComments),
        "numMessages" -> JsNumber(numMessages),
        "neverOnSite" -> JsBoolean(neverOnSite.isDefined),
        "sensitive" -> JsBoolean(sensitive.getOrElse(false)))))
  }

  def suppressSliderForSite() = AuthenticatedJsonAction { request =>
    val json = request.body.asJson.get
    val host: String = URI.parse((json \ "url").as[String]).get.host.get.name
    val suppress: Boolean = (json \ "suppress").as[Boolean]
    val utd = db.readWrite {implicit s =>
      val domain = domainRepo.get(host, excludeState = None) match {
        case Some(d) if d.isActive => d
        case Some(d) => domainRepo.save(d.withState(DomainStates.ACTIVE))
        case None => domainRepo.save(Domain(hostname = host))
      }
      userToDomainRepo.get(request.userId, domain.id.get, UserToDomainKinds.NEVER_SHOW, excludeState = None) match {
        case Some(utd) if (utd.isActive != suppress) =>
          userToDomainRepo.save(utd.withState(if (suppress) UserToDomainStates.ACTIVE else UserToDomainStates.INACTIVE))
        case Some(utd) => utd
        case None =>
          userToDomainRepo.save(UserToDomain(None, request.userId, domain.id.get, UserToDomainKinds.NEVER_SHOW))
      }
    }

    Ok(JsObject(Seq("host" -> JsString(host), "suppressed" -> JsBoolean(suppress))))
  }

  def getSocialConnections() = AuthenticatedJsonAction { authRequest =>
    val socialConnections = db.readOnly {implicit s =>
      socialConnectionRepo.getFortyTwoUserConnections(authRequest.userId).map(uid => basicUserRepo.load(userRepo.get(uid))).toSeq
    }

    Ok(JsObject(Seq(
      ("friends" -> JsArray(socialConnections.map(sc => BasicUserSerializer.basicUserSerializer.writes(sc))))
    )))
  }
}
