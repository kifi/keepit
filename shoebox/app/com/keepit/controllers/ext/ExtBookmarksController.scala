package com.keepit.controllers.ext

import com.keepit.classify.{Domain, DomainClassifier, DomainRepo}
import com.keepit.common.analytics.EventFamilies
import com.keepit.common.analytics.Events
import com.keepit.common.async._
import com.keepit.common.controller.BrowserExtensionController
import com.keepit.common.db._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.db.slick._
import com.keepit.common.healthcheck.{Healthcheck, HealthcheckPlugin, HealthcheckError}
import com.keepit.common.net._
import com.keepit.common.social._
import com.keepit.model._
import com.keepit.scraper.ScraperPlugin
import com.keepit.search.graph.URIGraph
import com.keepit.search.graph.URIGraphPlugin
import com.keepit.serializer.BookmarkSerializer
import com.keepit.controllers.core.BookmarkInterner
import scala.concurrent.Await
import play.api.libs.concurrent.Execution.Implicits._
import play.api.Play.current
import play.api.libs.json._
import scala.concurrent.duration._
import com.google.inject.{Inject, Singleton}
import play.api.libs.json.Json.JsValueWrapper
import views.html.admin.bookmark
import com.keepit.controllers.core.SliderInfoLoader
import com.keepit.serializer.UserWithSocialSerializer._

@Singleton
class ExtBookmarksController @Inject() (db: Database, bookmarkManager: BookmarkInterner,
  bookmarkRepo: BookmarkRepo, uriRepo: NormalizedURIRepo, userRepo: UserRepo, urlPatternRepo: URLPatternRepo,
  domainRepo: DomainRepo, userToDomainRepo: UserToDomainRepo,
  sliderRuleRepo: SliderRuleRepo, socialConnectionRepo: SocialConnectionRepo, commentReadRepo: CommentReadRepo, experimentRepo: UserExperimentRepo,
  uriGraphPlugin: URIGraphPlugin, healthcheck: HealthcheckPlugin,
  classifier: DomainClassifier, historyTracker: SliderHistoryTracker, uriGraph: URIGraph, sliderInfoLoader: SliderInfoLoader)
    extends BrowserExtensionController {

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
      sliderInfo.numUnreadComments match { case 0 => None; case n => Some("unreadComments" -> JsNumber(n)) },
      sliderInfo.numUnreadMessages match { case 0 => None; case n => Some("unreadMessages" -> JsNumber(n)) },
      sliderInfo.sensitive.flatMap { s => if (s) Some("sensitive" -> JsBoolean(true)) else None },
      sliderInfo.neverOnSite.map { _ => "neverOnSite" -> JsBoolean(true) },
      sliderInfo.locator.map { s => "locator" -> JsString(s.value) },
      sliderInfo.shown.map { "shown" -> JsBoolean(_) },
      sliderInfo.ruleGroup.map { "rules" -> _.compactJson },
      sliderInfo.patterns.map { p => "patterns" -> JsArray(p.map(JsString)) })

    Ok(JsObject(result.flatten))
  }

  def remove() = AuthenticatedJsonAction { request =>
    val url = (request.body.asJson.get \ "url").as[String]
    val bookmark = db.readWrite { implicit s =>
      uriRepo.getByNormalizedUrl(url).flatMap { uri =>
        bookmarkRepo.getByUriAndUser(uri.id.get, request.userId).map { b =>
          bookmarkRepo.save(b.withActive(false))
        }
      }
    }
    uriGraphPlugin.update()
    bookmark match {
      case Some(bookmark) => Ok(BookmarkSerializer.bookmarkSerializer writes bookmark)
      case None => NotFound
    }
  }

  def updatePrivacy() = AuthenticatedJsonAction { request =>
    val (url, priv) = request.body.asJson.map{o => ((o \ "url").as[String], (o \ "private").as[Boolean])}.get
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

  def addBookmarks() = AuthenticatedJsonAction { request =>
    val userId = request.userId
    val installationId = request.kifiInstallationId
    request.body.asJson match {
      case Some(json) =>
        val bookmarkSource = (json \ "source").asOpt[String]
        bookmarkSource match {
          case Some("PLUGIN_START") => Forbidden
          case _ =>
            log.info("adding bookmarks of user %s".format(userId))
            val experiments = request.experimants
            val user = db.readOnly { implicit s => userRepo.get(userId) }
            bookmarkManager.internBookmarks(json \ "bookmarks", user, experiments, BookmarkSource(bookmarkSource.getOrElse("UNKNOWN")), installationId)
            uriGraphPlugin.update()
            Ok(JsObject(Seq()))
        }
      case None =>
        val (user, experiments, installation) = db.readOnly{ implicit session =>
          (userRepo.get(userId),
           experimentRepo.getByUser(userId) map (_.experimentType),
           installationId.map(_.id).getOrElse(""))
        }
        val msg = "Unsupported operation for user %s with old installation".format(userId)
        val metaData = JsObject(Seq("message" -> JsString(msg)))
        val event = Events.userEvent(EventFamilies.ACCOUNT, "deprecated_add_bookmarks", user, experiments, installation, metaData)
        dispatch ({
           event.persistToS3().persistToMongo()
        }, { e =>
          healthcheck.addError(HealthcheckError(error = Some(e), callType = Healthcheck.API,
              errorMessage = Some("Can't persist event %s".format(event))))
        })
        BadRequest(msg)
    }
  }

  def getNumMutualKeeps(id: ExternalId[User]) = AuthenticatedJsonAction { request =>
    val n: Int = db.readOnly { implicit s =>
      bookmarkRepo.getNumMutual(request.userId, userRepo.get(id).id.get)
    }
    Ok(Json.obj("n" -> n))
  }
}
