package com.keepit.controllers.ext

import com.google.inject.{Inject, Singleton}
import com.keepit.classify.{DomainClassifier, DomainRepo}
import com.keepit.common.controller.{BrowserExtensionController, ActionAuthenticator}
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.healthcheck.HealthcheckPlugin
import com.keepit.controllers.core.BookmarkInterner
import com.keepit.model._
import com.keepit.search.SearchServiceClient
import com.keepit.serializer.BasicUserSerializer
import com.keepit.serializer.BookmarkSerializer
import play.api.libs.json._

@Singleton
class ExtBookmarksController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  db: Database,
  bookmarkManager: BookmarkInterner,
  bookmarkRepo: BookmarkRepo,
  uriRepo: NormalizedURIRepo,
  userRepo: UserRepo,
  searchClient: SearchServiceClient,
  healthcheck: HealthcheckPlugin)
    extends BrowserExtensionController(actionAuthenticator) {

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
      case Some(bookmark) =>
        searchClient.updateURIGraph()
        Ok(BookmarkSerializer.bookmarkSerializer writes bookmark)
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
