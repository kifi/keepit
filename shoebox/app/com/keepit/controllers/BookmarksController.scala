package com.keepit.controllers

import com.keepit.classify.{Domain, DomainClassifier, DomainRepo}
import com.keepit.common.analytics.EventFamilies
import com.keepit.common.analytics.Events
import com.keepit.common.async._
import com.keepit.common.controller.FortyTwoController
import com.keepit.common.db._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.db.slick._
import com.keepit.common.healthcheck.HealthcheckError
import com.keepit.common.healthcheck.{Healthcheck, HealthcheckPlugin}
import com.keepit.common.net._
import com.keepit.common.social._
import com.keepit.inject._
import com.keepit.model._
import com.keepit.scraper.ScraperPlugin
import com.keepit.search.graph.URIGraph
import com.keepit.search.graph.URIGraphPlugin
import com.keepit.serializer.BookmarkSerializer

import scala.concurrent.Await
import play.api.libs.concurrent.Execution.Implicits._
import play.api.Play.current
import play.api.libs.json._
import scala.concurrent.duration._

import com.keepit.common.analytics.ActivityStream

import com.google.inject.{Inject, Singleton}

@Singleton
class BookmarksController @Inject() (db: DBConnection, 
  bookmarkRepo: BookmarkRepo, uriRepo: NormalizedURIRepo, socialRepo: UserWithSocialRepo, userRepo: UserRepo, urlPatternRepo: URLPatternRepo,
  scrapeRepo: ScrapeInfoRepo, domainRepo: DomainRepo, userToDomainRepo: UserToDomainRepo, urlRepo: URLRepo,
  sliderRuleRepo: SliderRuleRepo, socialConnectionRepo: SocialConnectionRepo, commentReadRepo: CommentReadRepo
  scraper: ScraperPlugin, uriGraph: URIGraphPlugin, healthcheck: HealthcheckPlugin,
  classifier: DomainClassifier) 
    extends FortyTwoController {

  def edit(id: Id[Bookmark]) = AdminHtmlAction { request =>
    db.readOnly { implicit session =>
      val bookmark = bookmarkRepo.get(id)
      val uri = uriRepo.get(bookmark.uriId)
      val user = socialRepo.toUserWithSocial(userRepo.get(bookmark.userId))
      val scrapeInfo = scrapeRepo.getByUri(bookmark.uriId)
      Ok(views.html.bookmark(user, bookmark, uri, scrapeInfo))
    }
  }

  def rescrape = AuthenticatedJsonAction { request =>
    val id = Id[Bookmark]((request.body.asJson.get \ "id").as[Int])
    db.readOnly { implicit session =>
      val bookmark = bookmarkRepo.get(id)
      val uri = uriRepo.get(bookmark.uriId)
      Await.result(scraper.asyncScrape(uri), 1 minutes)
      Ok(JsObject(Seq("status" -> JsString("ok"))))
    }
  }

  //post request with a list of private/public and active/inactive
  def updateBookmarks() = AdminHtmlAction { request =>
    def toBoolean(str: String) = str.trim.toInt == 1

    def setIsPrivate(id: Id[Bookmark], isPrivate: Boolean)(implicit session: RWSession): Id[User] = {
      val bookmark = bookmarkRepo.get(id)
      log.info("updating bookmark %s with private = %s".format(bookmark, isPrivate))
      bookmarkRepo.save(bookmark.withPrivate(isPrivate))
      log.info("updated bookmark %s".format(bookmark))
      bookmark.userId
    }

    def setIsActive(id: Id[Bookmark], isActive: Boolean)(implicit session: RWSession): Id[User] = {
      val bookmark = bookmarkRepo.get(id)
      log.info("updating bookmark %s with active = %s".format(bookmark, isActive))
      bookmarkRepo.save(bookmark.withActive(isActive))
      log.info("updated bookmark %s".format(bookmark))
      bookmark.userId
    }

    val uniqueUsers = db.readWrite { implicit s =>
      val modifiedUserIds = request.body.asFormUrlEncoded.get map { case (key, values) =>
        key.split("_") match {
          case Array("private", id) => setIsPrivate(Id[Bookmark](id.toInt), toBoolean(values.last))
          case Array("active", id) => setIsActive(Id[Bookmark](id.toInt), toBoolean(values.last))
        }
      }
      Set(modifiedUserIds.toSeq: _*)
    }
    uniqueUsers foreach { userId =>
      log.info("updating user %s".format(userId))
      uriGraph.update(userId)
    }
    Redirect(request.request.referer)
  }

  //this is an admin only task!!!
  def delete(id: Id[Bookmark]) = AdminHtmlAction { request =>
    db.readWrite { implicit s =>
      val bookmark = bookmarkRepo.get(id)
      bookmarkRepo.delete(id)
      uriGraph.update(bookmark.userId)
      Redirect(com.keepit.controllers.routes.BookmarksController.bookmarksView(0))
    }
  }

  def all = AdminHtmlAction { request =>
    val bookmarks = db.readOnly(implicit session => bookmarkRepo.all)
    Ok(JsArray(bookmarks map BookmarkSerializer.bookmarkSerializer.writes _))
  }

  def bookmarksView(page: Int = 0) = AdminHtmlAction { request =>
    val PAGE_SIZE = 200
    val (count, bookmarksAndUsers) = db.readOnly { implicit s =>
      val bookmarks = bookmarkRepo.page(page, PAGE_SIZE)
      val users = bookmarks map (_.userId) map userRepo.get map socialRepo.toUserWithSocial
      val uris = bookmarks map (_.uriId) map uriRepo.get map (bookmarkRepo.uriStats)
      val scrapes = bookmarks map (_.uriId) map scrapeRepo.getByUri
      val count = bookmarkRepo.count(s)
      (count, (users, (bookmarks, uris, scrapes).zipped.toList.seq).zipped.toList.seq)
    }
    val pageCount: Int = count / PAGE_SIZE + 1
    Ok(views.html.bookmarks(bookmarksAndUsers, page, count, pageCount))
  }

  // TODO: require ver parameter after all installations >= 2.1.51
  def checkIfExists(uri: String, ver: Option[String]) = AuthenticatedJsonAction { request =>
    val userId = request.userId
    // TODO: Optimize by not checking sensitivity and keptByAnyFriends if kept by user.
    val (uriId, bookmark, sensitive, neverOnSite, friendIds, ruleGroup, patterns, locator, shown) = db.readOnly { implicit s =>
      val nUri: Option[NormalizedURI] = uriRepo.getByNormalizedUrl(uri)
      val uriId: Option[Id[NormalizedURI]] = nUri.flatMap(_.id)

      val host: String = nUri.flatMap(_.domain).getOrElse(URI.parse(uri).get.host.get.name)
      val domain: Option[Domain] = domainRepo.get(host)
      val neverOnSite: Option[UserToDomain] = domain.flatMap { dom =>
        userToDomainRepo.get(userId, dom.id.get, UserToDomainKinds.NEVER_SHOW)
      }
      val sensitive: Option[Boolean] = domain.flatMap(_.sensitive).orElse(classifier.isSensitive(host).right.getOrElse(None))

      val bookmark: Option[Bookmark] = uriId.flatMap { uriId =>
        bookmarkRepo.getByUriAndUser(uriId, userId)
      }
      val friendIds = socialConnectionRepo.getFortyTwoUserConnections(userId)
      val ruleGroup: Option[SliderRuleGroup] = ver.flatMap { v =>
        val group = sliderRuleRepo.getGroup("default")
        if (v == group.version) None else Some(group)
      }
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
        inject[SliderHistoryTracker].getMultiHashFilter(userId).mayContain(uriId.id)
      }

      (uriId, bookmark, sensitive, neverOnSite, friendIds, ruleGroup, patterns, locator, shown)
    }

    val keptByAnyFriends = uriId.map { uriId =>
      val searcher = inject[URIGraph].getURIGraphSearcher
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
    uriGraph.update(request.userId)
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
            internBookmarks(json \ "bookmarks", user, experiments, BookmarkSource(bookmarkSource.getOrElse("UNKNOWN")), installationId)
            uriGraph.update(userId)
            Ok(JsObject(Seq()))
        }
      case None =>
        val (user, experiments, installation) = db.readOnly{ implicit session =>
          (userRepo.get(userId),
           inject[UserExperimentRepo].getByUser(userId) map (_.experimentType),
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

  private def internBookmarks(value: JsValue, user: User, experiments: Seq[State[ExperimentType]], source: BookmarkSource, installationId: Option[ExternalId[KifiInstallation]] = None): List[Bookmark] = value match {
    case JsArray(elements) => (elements map {e => internBookmarks(e, user, experiments, source, installationId)} flatten).toList
    case json: JsObject if(json.keys.contains("children")) => internBookmarks(json \ "children" , user, experiments, source)
    case json: JsObject => List(internBookmark(json, user, experiments, source)).flatten
    case e: Throwable => throw new Exception("can't figure what to do with %s".format(e))
  }

  private def internBookmark(json: JsObject, user: User, experiments: Seq[State[ExperimentType]], source: BookmarkSource, installationId: Option[ExternalId[KifiInstallation]] = None): Option[Bookmark] = {
    val title = (json \ "title").as[String]
    val url = (json \ "url").as[String]
    val isPrivate = (json \ "isPrivate").asOpt[Boolean].getOrElse(true)

    if (!url.toLowerCase.startsWith("javascript:")) {
      log.debug("interning bookmark %s with title [%s]".format(json, title))
      val (uri, isNewURI) = db.readWrite { implicit s =>
        uriRepo.getByNormalizedUrl(url) match {
          case Some(uri) => (uri, false)
          case None => (createNewURI(title, url), true)
        }
      }
      if (isNewURI) scraper.asyncScrape(uri)
      val bookmark = db.readWrite { implicit s =>
        bookmarkRepo.getByUriAndUser(uri.id.get, user.id.get) match {
          case Some(bookmark) if bookmark.isActive => Some(bookmark) // TODO: verify isPrivate?
          case Some(bookmark) => Some(bookmarkRepo.save(bookmark.withActive(true).withPrivate(isPrivate)))
          case None =>
            Events.userEvent(EventFamilies.SLIDER, "newKeep", user, experiments, installationId.map(_.id).getOrElse(""), JsObject(Seq("source" -> JsString(source.value))))
            val urlObj = urlRepo.get(url).getOrElse(urlRepo.save(URLFactory(url = url, normalizedUriId = uri.id.get)))
            Some(bookmarkRepo.save(BookmarkFactory(uri, user.id.get, title, urlObj, source, isPrivate, installationId)))
        }
      }
      if(bookmark.isDefined) addToActivityStream(user, bookmark.get)

      bookmark
    } else {
      None
    }
  }

  private def createNewURI(title: String, url: String)(implicit session: RWSession) =
    uriRepo.save(NormalizedURIFactory(title = title, url = url))

  private def addToActivityStream(user: User, bookmark: Bookmark) = {
    val social = db.readOnly { implicit session =>
      inject[SocialUserInfoRepo].getByUser(user.id.get).headOption.map(_.socialId.id).getOrElse("")
    }

    val json = Json.obj(
      "user" -> Json.obj(
        "id" -> user.id.get.id,
        "name" -> s"${user.firstName} ${user.lastName}",
        "avatar" -> s"https://graph.facebook.com/${social}/picture?height=150&width=150"),
      "bookmark" -> Json.obj(
        "id" -> bookmark.id.get.id,
        "isPrivate" -> bookmark.isPrivate,
        "title" -> bookmark.title,
        "uri" -> bookmark.url,
        "source" -> bookmark.source.value)
    )

    inject[ActivityStream].streamActivity("bookmark", json)
  }
}
