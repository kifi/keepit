package com.keepit.controllers.ext

import com.google.inject.{Inject, Singleton}
import com.keepit.classify.{DomainClassifier, DomainRepo}
import com.keepit.common.controller.{BrowserExtensionController, ActionAuthenticator}
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.healthcheck.HealthcheckPlugin
import com.keepit.controllers.core.BookmarkInterner
import com.keepit.controllers.core.SliderInfoLoader
import com.keepit.model._
import com.keepit.search.SearchServiceClient
import com.keepit.serializer.BookmarkSerializer
import com.keepit.serializer.UserWithSocialSerializer._
import play.api.libs.json._

@Singleton
class ExtBookmarksController @Inject() (
    actionAuthenticator: ActionAuthenticator,
    db: Database,
    bookmarkManager: BookmarkInterner,
    bookmarkRepo: BookmarkRepo,
    uriRepo: NormalizedURIRepo,
    userRepo: UserRepo,
    urlPatternRepo: URLPatternRepo,
    domainRepo: DomainRepo,
    userToDomainRepo: UserToDomainRepo,
    sliderRuleRepo: SliderRuleRepo,
    socialConnectionRepo: SocialConnectionRepo,
    commentReadRepo: CommentReadRepo,
    experimentRepo: UserExperimentRepo,
    searchClient: SearchServiceClient,
    healthcheck: HealthcheckPlugin,
    classifier: DomainClassifier,
    historyTracker: SliderHistoryTracker,
    sliderInfoLoader: SliderInfoLoader
  ) extends BrowserExtensionController(actionAuthenticator) {

  def checkIfExists(uri: String, ver: String) = AuthenticatedJsonAction { request =>
    val userId = request.userId

    val sliderInfo = sliderInfoLoader.initialLoad(userId, uri, ver)

    type wrapped = Option[(String, JsValue)]
    val result: Seq[wrapped] = Seq(
      Some("kept" -> JsBoolean(sliderInfo.bookmark.isDefined)),
      sliderInfo.bookmark.map(_.isPrivate) match { case Some(true) => Some("private" -> JsBoolean(true)); case _ => None },
      sliderInfo.socialUsers match { case Nil => None; case _ => Some("keptByAnyFriends" -> JsBoolean(true)) }, // TODO: remove
      sliderInfo.socialUsers match { case Nil => None; case u => Some("keepers" -> userWithSocialSerializer.writes(u)) },
      sliderInfo.numKeeps match { case 0 => None; case n => Some("keeps" -> JsNumber(n)) },
      sliderInfo.numComments match { case 0 => None; case n => Some("numComments" -> JsNumber(n)) },
      sliderInfo.numUnreadComments match { case 0 => None; case n => Some("unreadComments" -> JsNumber(n)) },
      sliderInfo.numMessages match { case 0 => None; case n => Some("numMessages" -> JsNumber(n)) },
      sliderInfo.numUnreadMessages match { case 0 => None; case n => Some("unreadMessages" -> JsNumber(n)) },
      sliderInfo.sensitive.flatMap { s => if (s) Some("sensitive" -> JsBoolean(true)) else None },
      sliderInfo.neverOnSite.map { _ => "neverOnSite" -> JsBoolean(true) },
      sliderInfo.locator.map { s => "locator" -> JsString(s.value) },
      sliderInfo.shown.map { "shown" -> JsBoolean(_) },
      sliderInfo.ruleGroup.map { "rules" -> _.compactJson },
      sliderInfo.patterns.map { p => "patterns" -> JsArray(p.map(JsString)) })

    Ok(JsObject(result.flatten))
  }

  def remove() = AuthenticatedJsonToJsonAction { request =>
    val url = (request.body \ "url").as[String]
    val bookmark = db.readWrite { implicit s =>
      uriRepo.getByNormalizedUrl(url).flatMap { uri =>
        bookmarkRepo.getByUriAndUser(uri.id.get, request.userId).map { b =>
          bookmarkRepo.save(b.withActive(false))
        }
      }
    }
    searchClient.updateURIGraph()
    bookmark match {
      case Some(bookmark) => Ok(BookmarkSerializer.bookmarkSerializer writes bookmark)
      case None => NotFound
    }
  }

  def updatePrivacy() = AuthenticatedJsonToJsonAction { request =>
    val json = request.body
    val (url, priv) = ((json \ "url").as[String], (json \ "private").as[Boolean])
    db.readWrite { implicit s =>
      uriRepo.getByNormalizedUrl(url).flatMap { uri =>
        bookmarkRepo.getByUriAndUser(uri.id.get, request.userId).filter(_.isPrivate != priv).map {b =>
          bookmarkRepo.save(b.withPrivate(priv))
        }
      }
    } match {
      case Some(bookmark) => Ok(BookmarkSerializer.bookmarkSerializer writes bookmark)
      case None => NotFound
    }
  }

  def addBookmarks() = AuthenticatedJsonToJsonAction { request =>
    val userId = request.userId
    val installationId = request.kifiInstallationId
    val json = request.body

    val bookmarkSource = (json \ "source").asOpt[String]
    bookmarkSource match {
      case Some("PLUGIN_START") => Forbidden
      case _ =>
        log.info("adding bookmarks of user %s".format(userId))
        val experiments = request.experimants
        val user = db.readOnly { implicit s => userRepo.get(userId) }
        bookmarkManager.internBookmarks(json \ "bookmarks", user, experiments, BookmarkSource(bookmarkSource.getOrElse("UNKNOWN")), installationId)
        searchClient.updateURIGraph()
        Ok(JsObject(Seq()))
    }
  }

  def getNumMutualKeeps(id: ExternalId[User]) = AuthenticatedJsonAction { request =>
    val n: Int = db.readOnly { implicit s =>
      bookmarkRepo.getNumMutual(request.userId, userRepo.get(id).id.get)
    }
    Ok(Json.obj("n" -> n))
  }
}
