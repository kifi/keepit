package com.keepit.controllers.ext

import com.google.inject.Inject
import com.keepit.common.KestrelCombinator
import com.keepit.common.akka.SafeFuture
import com.keepit.common.controller.{BrowserExtensionController, ActionAuthenticator}
import com.keepit.common.db._
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.db.slick._
import com.keepit.common.healthcheck.HealthcheckPlugin
import com.keepit.common.time._
import com.keepit.controllers.core.BookmarkInterner
import com.keepit.heimdal.{HeimdalServiceClient, UserEventContextBuilder, UserEvent, UserEventType}
import com.keepit.model._
import com.keepit.search.SearchServiceClient
import com.keepit.shoebox.BrowsingHistoryTracker

import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.json._

private case class SendableBookmark(
  id: ExternalId[Bookmark],
  title: Option[String],
  url: String,
  isPrivate: Boolean,
  state: State[Bookmark]
)

private object SendableBookmark {
  private implicit val externalIdFormat = ExternalId.format[Bookmark]
  private implicit val stateFormat = State.format[Bookmark]
  implicit val writesSendableBookmark = Json.writes[SendableBookmark]

  def fromBookmark(b: Bookmark): SendableBookmark =
    SendableBookmark(b.externalId, b.title, b.url, b.isPrivate, b.state)
}

class ExtBookmarksController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  db: Database,
  bookmarkManager: BookmarkInterner,
  bookmarkRepo: BookmarkRepo,
  uriRepo: NormalizedURIRepo,
  userRepo: UserRepo,
  collectionRepo: CollectionRepo,
  keepToCollectionRepo: KeepToCollectionRepo,
  searchClient: SearchServiceClient,
  browsingHistoryTracker: BrowsingHistoryTracker,
  healthcheck: HealthcheckPlugin,
  heimdal: HeimdalServiceClient)
    extends BrowserExtensionController(actionAuthenticator) {

  def removeTag(id: ExternalId[Collection]) = AuthenticatedJsonToJsonAction { request =>
    val url = (request.body \ "url").as[String]
    db.readWrite { implicit s =>
      for {
        uri <- uriRepo.getByUri(url)
        bookmark <- bookmarkRepo.getByUriAndUser(uri.id.get, request.userId)
        collection <- collectionRepo.getOpt(id)
      } {
        keepToCollectionRepo.remove(bookmarkId = bookmark.id.get, collectionId = collection.id.get)
      }
    }
    Ok(Json.obj())
  }

  def createTag() = AuthenticatedJsonToJsonAction { request =>
    val name = (request.body \ "name").as[String]
    db.readWrite { implicit s =>
      val tag = getOrCreateTag(request.userId, name)
      Ok(Json.toJson(SendableTag from tag))
    }
  }

  def addTag(id: ExternalId[Collection]) = AuthenticatedJsonToJsonAction { request =>
    val url = (request.body \ "url").as[String]
    db.readWrite { implicit s =>
      collectionRepo.getOpt(id).map(_.id.get) map { tagId =>
        addTagToUrl(request.user, request.experiments, url, tagId)
        Ok(Json.obj())
      } getOrElse {
        BadRequest(Json.obj("error" -> "noSuchTag"))
      }
    }
  }

  def addToUrl() = AuthenticatedJsonToJsonAction { request =>
    val url = (request.body \ "url").as[String]
    val name = (request.body \ "name").as[String]
    val tag = db.readWrite { implicit s =>
      getOrCreateTag(request.userId, name) tap { tag =>
        addTagToUrl(request.user, request.experiments, url, tag.id.get)
      }
    }
    Ok(Json.toJson(SendableTag from tag))
  }

  def clearTags() = AuthenticatedJsonToJsonAction { request =>
    val url = (request.body \ "url").as[String]
    db.readWrite { implicit s =>
      for {
        u <- uriRepo.getByUri(url).toSeq
        b <- bookmarkRepo.getByUri(u.id.get).toSeq
        ktc <- keepToCollectionRepo.getByBookmark(b.id.get)
      } {
        keepToCollectionRepo.save(ktc.copy(state = KeepToCollectionStates.INACTIVE))
      }
    }
    Ok(Json.obj())
  }

  def tags() = AuthenticatedJsonAction { request =>
    val tags = db.readOnly { implicit s =>
      collectionRepo.getByUser(request.userId)
    }
    Ok(Json.toJson(tags.map(SendableTag.from)))
  }

  def tagsByUrl() = AuthenticatedJsonToJsonAction { request =>
    val url = (request.body \ "url").as[String]
    val tags = db.readOnly { implicit s =>
      for {
        uri <- uriRepo.getByUri(url).toSeq
        bookmark <- bookmarkRepo.getByUriAndUser(uri.id.get, request.userId).toSeq
        collectionId <- keepToCollectionRepo.getCollectionsForBookmark(bookmark.id.get)
      } yield {
        collectionRepo.get(collectionId)
      }
    }
    Ok(Json.toJson(tags.map(SendableTag.from)))
  }

  def remove() = AuthenticatedJsonToJsonAction { request =>
    val url = (request.body \ "url").as[String]
    val bookmark = db.readWrite { implicit s =>
      // TODO: use uriRepo.internByUri(url, NormalizationCandidate(json):_*) to utilize "canonical" & "og"
      uriRepo.getByUri(url).flatMap { uri =>
        bookmarkRepo.getByUriAndUser(uri.id.get, request.userId).map { b =>
          bookmarkRepo.save(b.withActive(false))
        }
      }
    }
    searchClient.updateURIGraph()
    bookmark match {
      case Some(bookmark) => Ok(Json.toJson(SendableBookmark fromBookmark bookmark))
      case None => NotFound
    }
  }

  def updatePrivacy() = AuthenticatedJsonToJsonAction { request =>
    val json = request.body
    val (url, priv) = ((json \ "url").as[String], (json \ "private").as[Boolean])
    db.readWrite { implicit s =>
      // TODO: use uriRepo.internByUri(url, NormalizationCandidate(json):_*) to utilize "canonical" & "og"
      uriRepo.getByUri(url).flatMap { uri =>
        bookmarkRepo.getByUriAndUser(uri.id.get, request.userId).filter(_.isPrivate != priv).map {b =>
          bookmarkRepo.save(b.withPrivate(priv))
        }
      }
    } match {
      case Some(bookmark) =>
        searchClient.updateURIGraph()
        Ok(Json.toJson(SendableBookmark fromBookmark bookmark))
      case None => NotFound
    }
  }

  def addBookmarks() = AuthenticatedJsonToJsonAction { request =>
    val tStart = currentDateTime
    val userId = request.userId
    val installationId = request.kifiInstallationId
    val json = request.body

    val bookmarkSource = (json \ "source").asOpt[String]
    bookmarkSource match {
      case Some("PLUGIN_START") => Forbidden
      case _ =>
        SafeFuture {
          log.info("adding bookmarks of user %s".format(userId))
          val experiments = request.experiments
          val user = db.readOnly { implicit s => userRepo.get(userId) }
          val bookmarks = bookmarkManager.internBookmarks(json \ "bookmarks", user, experiments, BookmarkSource(bookmarkSource.getOrElse("UNKNOWN")), installationId)
          browsingHistoryTracker.add(userId, bookmarks.map(_.uriId))
          searchClient.updateURIGraph()

          //Analytics
          SafeFuture{ bookmarks.foreach { bookmark =>
            val contextBuilder = new UserEventContextBuilder()

            contextBuilder += ("isPrivate", bookmark.isPrivate)
            request.experiments.foreach{ experiment =>
              contextBuilder += ("experiment", experiment.toString)
            }
            contextBuilder += ("remoteAddress", request.headers.get("X-Forwarded-For").getOrElse(request.remoteAddress))
            contextBuilder += ("userAgent",request.headers.get("User-Agent").getOrElse(""))
            contextBuilder += ("requestScheme", request.headers.get("X-Scheme").getOrElse(""))
            contextBuilder += ("url", bookmark.url)
            contextBuilder += ("source", bookmarkSource.getOrElse("UNKNOWN"))
            contextBuilder += ("hasTitle", bookmark.title.isDefined)

            heimdal.trackEvent(UserEvent(userId.id, contextBuilder.build, UserEventType("keep"), tStart))
          }}

        }
        Status(202)(JsNumber(0))
    }
  }

  def getNumMutualKeeps(id: ExternalId[User]) = AuthenticatedJsonAction { request =>
    val n: Int = db.readOnly { implicit s =>
      bookmarkRepo.getNumMutual(request.userId, userRepo.get(id).id.get)
    }
    Ok(Json.obj("n" -> n))
  }

  private def getOrCreateTag(userId: Id[User], name: String)(implicit s: RWSession): Collection = {
    val normalizedName = name.trim.replaceAll("""\s+""", " ").take(Collection.MaxNameLength)
    collectionRepo.getByUserAndName(userId, normalizedName, excludeState = None) match {
      case Some(t) if t.isActive => t
      case Some(t) => collectionRepo.save(t.copy(state = CollectionStates.ACTIVE))
      case None => collectionRepo.save(Collection(userId = userId, name = normalizedName))
    }
  }

  private def addTagToUrl(user: User, experiments: Set[ExperimentType],
      url: String, tagId: Id[Collection])(implicit s: RWSession): KeepToCollection = {
    val bookmarkIdOpt = for {
      uri <- uriRepo.getByUri(url)
      bookmark <- bookmarkRepo.getByUriAndUser(uri.id.get, user.id.get)
    } yield bookmark.id.get
    val bookmarkId = bookmarkIdOpt getOrElse {
      bookmarkManager.internBookmarks(
        Json.obj("url" -> url), user, experiments, BookmarkSource("TAGGED")
      ).head.id.get
    }
    keepToCollectionRepo.getOpt(bookmarkId, tagId) match {
      case Some(ktc) if ktc.state == KeepToCollectionStates.ACTIVE =>
        ktc
      case Some(ktc) =>
        keepToCollectionRepo.save(ktc.copy(state = KeepToCollectionStates.ACTIVE))
      case None =>
        keepToCollectionRepo.save(KeepToCollection(bookmarkId = bookmarkId, collectionId = tagId))
    }
  }
}
