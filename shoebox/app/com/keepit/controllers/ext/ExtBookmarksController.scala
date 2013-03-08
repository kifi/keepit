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

@Singleton
class ExtBookmarksController @Inject() (db: Database, bookmarkManager: BookmarkInterner,
  bookmarkRepo: BookmarkRepo, uriRepo: NormalizedURIRepo, userRepo: UserRepo, urlPatternRepo: URLPatternRepo,
  domainRepo: DomainRepo, userToDomainRepo: UserToDomainRepo,
  sliderRuleRepo: SliderRuleRepo, socialConnectionRepo: SocialConnectionRepo, commentReadRepo: CommentReadRepo, experimentRepo: UserExperimentRepo,
  uriGraphPlugin: URIGraphPlugin, healthcheck: HealthcheckPlugin,
  classifier: DomainClassifier, historyTracker: SliderHistoryTracker, uriGraph: URIGraph)
    extends BrowserExtensionController {

  def checkIfExists(uri: String, ver: String) = AuthenticatedJsonAction { request =>
    val userId = request.userId
    // TODO: Optimize by not checking sensitivity and keptByAnyFriends if kept by user.
    val (uriId, bookmark, sensitive, neverOnSite, friendIds, ruleGroup, patterns, locator, shown) = db.readOnly { implicit s =>
      val nUri: Option[NormalizedURI] = uriRepo.getByNormalizedUrl(uri)
      val uriId: Option[Id[NormalizedURI]] = nUri.flatMap(_.id)

      val host: Option[String] = URI.parse(uri).get.host.map(_.name)
      val domain: Option[Domain] = host.flatMap(domainRepo.get(_))
      val neverOnSite: Option[UserToDomain] = domain.flatMap { dom =>
        userToDomainRepo.get(userId, dom.id.get, UserToDomainKinds.NEVER_SHOW)
      }
      val sensitive: Option[Boolean] =
        domain.flatMap(_.sensitive).orElse(host.flatMap(classifier.isSensitive(_).right.toOption))

      val bookmark: Option[Bookmark] = uriId.flatMap { uriId =>
        bookmarkRepo.getByUriAndUser(uriId, userId)
      }
      val friendIds = socialConnectionRepo.getFortyTwoUserConnections(userId)
      val ruleGroup: Option[SliderRuleGroup] = Option(sliderRuleRepo.getGroup("default")).filter(_.version != ver)
      val patterns: Option[Seq[String]] = ruleGroup.map(_ => urlPatternRepo.getActivePatterns)

      val locator: Option[DeepLocator] = uriId.flatMap { uriId =>
        val messages = commentReadRepo.getParentsOfUnreadMessages(userId, uriId)
        messages.size match {
          case 0 => if (commentReadRepo.hasUnreadComments(userId, uriId)) Some(DeepLocator.ofCommentList) else None
          case 1 => Some(DeepLocator.ofMessageThread(messages.head))
          case _ => Some(DeepLocator.ofMessageThreadList)
        }
      }

      val shown: Option[Boolean] = if (locator.isDefined) None else uriId.map { uriId =>
        historyTracker.getMultiHashFilter(userId).mayContain(uriId.id)
      }

      (uriId, bookmark, sensitive, neverOnSite, friendIds, ruleGroup, patterns, locator, shown)
    }

    val keptByAnyFriends = uriId.map { uriId =>
      val searcher = uriGraph.getURIGraphSearcher
      searcher.intersectAny(
        searcher.getUserToUserEdgeSet(userId, friendIds),
        searcher.getUriToUserEdgeSet(uriId))
    }.getOrElse(false)

    Ok(JsObject(Seq(
      "user_has_bookmark" -> JsBoolean(bookmark.isDefined), // TODO: remove this key after all installations >= 2.1.49
      "kept" -> JsBoolean(bookmark.isDefined),
      "keptByAnyFriends" -> JsBoolean(keptByAnyFriends),
      "sensitive" -> JsBoolean(sensitive.getOrElse(false))) ++
      neverOnSite.map { _ => Seq(
        "neverOnSite" -> JsBoolean(true))
      }.getOrElse(Nil) ++
      locator.map { l => Seq(
        "locator" -> JsString(l.value))
      }.getOrElse(Nil) ++
      shown.map { s => Seq(
        "shown" -> JsBoolean(s))
      }.getOrElse(Nil) ++
      ruleGroup.map { g => Seq(
        "rules" -> g.compactJson,
        "patterns" -> JsArray(patterns.get.map(JsString)))
      }.getOrElse(Nil)))
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
    uriGraphPlugin.update(request.userId)
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
            uriGraphPlugin.update(userId)
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
