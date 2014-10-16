package com.keepit.controllers.ext

import com.google.inject.Inject
import com.keepit.commanders.{ KeepData, KeepsCommander, LibraryAddRequest, LibraryCommander, LibraryData, RawBookmarkRepresentation, _ }
import com.keepit.common.akka.SafeFuture
import com.keepit.common.controller.{ UserActions, UserActionsHelper, ShoeboxServiceController, _ }
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.slick.Database
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.healthcheck.{ AirbrakeNotifier, AirbrakeError }
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.store.ImageSize
import com.keepit.heimdal.HeimdalContextBuilderFactory
import com.keepit.model._
import com.keepit.shoebox.controllers.LibraryAccessActions

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json._
import play.api.mvc.Result

import scala.concurrent.Future
import scala.util.{ Failure, Success, Try }
import com.keepit.common.json.TupleFormat
import com.keepit.common.json

class ExtLibraryController @Inject() (
  db: Database,
  val libraryCommander: LibraryCommander,
  keepsCommander: KeepsCommander,
  basicUserRepo: BasicUserRepo,
  libraryMembershipRepo: LibraryMembershipRepo,
  heimdalContextBuilder: HeimdalContextBuilderFactory,
  keepImageRequestRepo: KeepImageRequestRepo,
  keepImageCommander: KeepImageCommander,
  val userActionsHelper: UserActionsHelper,
  keepRepo: KeepRepo,
  collectionRepo: CollectionRepo,
  airbrake: AirbrakeNotifier,
  keepInterner: KeepInterner,
  rawKeepFactory: RawKeepFactory,
  implicit val publicIdConfig: PublicIdConfiguration)
    extends UserActions with LibraryAccessActions with ShoeboxServiceController {

  def getLibraries() = UserAction { request =>
    val datas = libraryCommander.getLibrariesUserCanKeepTo(request.userId) map { lib =>
      val owner = db.readOnlyMaster { implicit s => basicUserRepo.load(lib.ownerId) }
      LibraryData(
        id = Library.publicId(lib.id.get),
        name = lib.name,
        visibility = lib.visibility,
        path = Library.formatLibraryPath(owner.username, owner.externalId, lib.slug))
    }
    Ok(Json.obj("libraries" -> datas))
  }

  def createLibrary() = UserAction(parse.tolerantJson) { request =>
    val body = request.body.as[JsObject]
    val name = (body \ "name").as[String]
    val visibility = (body \ "visibility").as[LibraryVisibility]
    val slug = LibrarySlug.generateFromName(name)
    val addRequest = LibraryAddRequest(name, visibility, description = None, slug, collaborators = None, followers = None)
    libraryCommander.addLibrary(addRequest, request.userId) match {
      case Left(fail) => BadRequest(Json.obj("error" -> fail.message))
      case Right(lib) =>
        Ok(Json.toJson(LibraryData(
          id = Library.publicId(lib.id.get),
          name = lib.name,
          visibility = lib.visibility,
          path = Library.formatLibraryPath(request.user.username, request.user.externalId, lib.slug))))
    }
  }

  def getLibrary(libraryPubId: PublicId[Library]) = UserAction { request =>
    decode(libraryPubId) { libraryId =>
      libraryCommander.getLibraryWithOwnerAndCounts(libraryId, request.userId) match {
        case Left((status, message)) => Status(status)(Json.obj("error" -> message))
        case Right((library, owner, keepCount, followerCount)) => Ok(Json.obj(
          "name" -> library.name,
          "slug" -> library.slug,
          "visibility" -> library.visibility,
          "owner" -> owner,
          "keeps" -> keepCount,
          "followers" -> followerCount
        ))
      }
    }
  }

  def deleteLibrary(libraryPubId: PublicId[Library]) = UserAction { request =>
    decode(libraryPubId) { libraryId =>
      implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.keeper).build
      libraryCommander.removeLibrary(libraryId, request.userId) match {
        case Some((status, message)) => Status(status)(Json.obj("error" -> message))
        case _ => NoContent
      }
    }
  }

  def addKeep(libraryPubId: PublicId[Library]) = UserAction(parse.tolerantJson) { request =>
    decode(libraryPubId) { libraryId =>
      db.readOnlyMaster { implicit s =>
        libraryMembershipRepo.getOpt(request.userId, libraryId)
      } match {
        case Some(mem) if mem.access != LibraryAccess.READ_ONLY =>
          val info = request.body.as[JsObject]
          val source = KeepSource.keeper
          val hcb = heimdalContextBuilder.withRequestInfoAndSource(request, source)
          if ((info \ "guided").asOpt[Boolean].getOrElse(false)) {
            hcb += ("guided", true)
          }
          implicit val context = hcb.build

          val rawBookmark = info.as[RawBookmarkRepresentation]
          val keepInfo = keepsCommander.keepOne(rawBookmark, request.userId, libraryId, request.kifiInstallationId, source)

          // Determine image choice.
          val imageStatus = (info \ "image") match {
            case JsNull => // user purposely wants no image
              Json.obj()
            case JsString(imageUrl) if imageUrl.startsWith("http") =>
              val (keep, keepImageRequest) = db.readWrite { implicit session =>
                val keep = keepRepo.getOpt(keepInfo.id.get).get // Weird pattern, but this should always exist.
                val keepImageRequest = keepImageRequestRepo.save(KeepImageRequest(keepId = keep.id.get, source = KeepImageSource.UserPicked))
                (keep, keepImageRequest)
              }
              keepImageCommander.setKeepImageFromUrl(imageUrl, keep.id.get, KeepImageSource.UserPicked, Some(keepImageRequest.id.get))
              Json.obj("imageStatusPath" -> routes.ExtKeepImageController.checkImageStatus(libraryPubId, keep.externalId, keepImageRequest.token).url)
            case _ =>
              val keep = db.readOnlyMaster { implicit session =>
                keepRepo.getOpt(keepInfo.id.get).get // Weird pattern, but this should always exist.
              }
              keepImageCommander.autoSetKeepImage(keep.id.get, localOnly = false, overwriteExistingChoice = false)
              Json.obj()
          }

          Ok(Json.toJson(KeepData(
            keepInfo.id.get,
            mine = true, // TODO: stop assuming keep is mine and removable
            removable = true,
            secret = keepInfo.isPrivate,
            libraryId = Library.publicId(libraryId))).as[JsObject] ++ imageStatus)
        case _ =>
          Forbidden(Json.obj("error" -> "invalid_access"))
      }
    }
  }

  // imgSize is of format "<w>x<h>", such as "300x500"
  def getKeep(libraryPubId: PublicId[Library], keepExtId: ExternalId[Keep], imgSize: Option[String]) = UserAction { request =>
    decode(libraryPubId) { libraryId =>
      keepsCommander.getKeep(libraryId, keepExtId, request.userId) match {
        case Left((status, code)) => Status(status)(Json.obj("error" -> code))
        case Right(keep) =>
          val idealSize = imgSize.flatMap { s => Try(ImageSize(s)).toOption }.getOrElse(ExtLibraryController.defaultImageSize)
          val keepImageUrl = keepImageCommander.getBestImageForKeep(keep.id.get, idealSize).map(keepImageCommander.getUrl)
          val tags = db.readOnlyReplica { implicit s =>
            collectionRepo.getTagsByKeepId(keep.id.get)
          }
          Ok(Json.toJson(MoarKeepData(keep.title, keepImageUrl, tags.map(_.tag).toSeq)))
      }
    }
  }

  def removeKeep(libraryPubId: PublicId[Library], keepExtId: ExternalId[Keep]) = UserAction { request =>
    decode(libraryPubId) { libraryId =>
      implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.keeper).build
      keepsCommander.unkeepOneFromLibrary(keepExtId, libraryId, request.userId) match {
        case Left(failMsg) => BadRequest(Json.obj("error" -> failMsg))
        case Right(info) => NoContent
      }
    }
  }

  // Maintainers: Let's keep this endpoint simple, quick and reliable. Complex updates deserve their own endpoints.
  def updateKeep(libraryPubId: PublicId[Library], keepExtId: ExternalId[Keep]) = UserAction(parse.tolerantJson) { request =>
    decode(libraryPubId) { libraryId =>
      val body = request.body.as[JsObject]
      val title = (body \ "title").asOpt[String]
      implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.keeper).build
      keepsCommander.updateKeepInLibrary(keepExtId, libraryId, request.userId, title) match {
        case Left((status, code)) => Status(status)(Json.obj("error" -> code))
        case Right(keep) => NoContent
      }
    }
  }

  def tagKeep(libraryPubId: PublicId[Library], keepExtId: ExternalId[Keep], tag: String) = UserAction { request =>
    decode(libraryPubId) { libraryId =>
      db.readOnlyMaster { implicit session =>
        libraryMembershipRepo.getWithLibraryIdAndUserId(libraryId, request.userId)
      } match {
        case Some(mem) if mem.isOwner => // TODO: change to .hasWriteAccess
          keepsCommander.getKeep(libraryId, keepExtId, request.userId) match {
            case Right(keep) =>
              implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.keeper).build
              val coll = keepsCommander.getOrCreateTag(request.userId, tag) // TODO: library ID, not user ID
              keepsCommander.addToCollection(coll.id.get, Seq(keep))
              Ok(Json.obj("tag" -> coll.name))
            case Left((status, code)) =>
              Status(status)(Json.obj("error" -> code))
          }
        case _ =>
          Forbidden(Json.obj("error" -> "permission_denied"))
      }
    }
  }

  def untagKeep(libraryPubId: PublicId[Library], keepExtId: ExternalId[Keep], tag: String) = UserAction { request =>
    decode(libraryPubId) { libraryId =>
      db.readOnlyMaster { implicit session =>
        libraryMembershipRepo.getWithLibraryIdAndUserId(libraryId, request.userId)
      } match {
        case Some(mem) if mem.isOwner => // TODO: change to .hasWriteAccess
          keepsCommander.getKeep(libraryId, keepExtId, request.userId) match {
            case Right(keep) =>
              db.readOnlyReplica { implicit s =>
                collectionRepo.getByUserAndName(request.userId, Hashtag(tag)) // TODO: library ID, not user ID
              } foreach { coll =>
                implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.keeper).build
                keepsCommander.removeFromCollection(coll, Seq(keep))
              }
              NoContent
            case Left((status, code)) =>
              Status(status)(Json.obj("error" -> code))
          }
        case _ =>
          Forbidden(Json.obj("error" -> "permission_denied"))
      }
    }
  }

  def deprecatedSearchTags(libraryPubId: PublicId[Library], query: String, limit: Option[Int]) = doSuggestTags(libraryPubId, None, query, limit)

  def suggestTags(libraryPubId: PublicId[Library], keepId: ExternalId[Keep], query: String, limit: Option[Int]) = doSuggestTags(libraryPubId, Some(keepId), query, limit)

  private def doSuggestTags(libraryPubId: PublicId[Library], keepId: Option[ExternalId[Keep]], query: String, limit: Option[Int]) = UserAction.async { request =>
    Library.decodePublicId(libraryPubId).toOption map { libraryId =>
      db.readOnlyMaster { implicit session =>
        libraryMembershipRepo.getWithLibraryIdAndUserId(libraryId, request.userId)
      } map { _ =>
        if (query.trim.isEmpty && keepId.isDefined) {
          val keep = db.readOnlyMaster { implicit session => keepRepo.get(keepId.get) }
          keepsCommander.suggestTags(request.userId, libraryId, keep.uriId, limit).map { suggestedTags =>
            val result = JsArray(suggestedTags.map { tag => Json.obj("tag" -> tag) })
            Ok(result)
          }
        } else {
          keepsCommander.searchTags(request.userId, query, limit) map { hits =>
            implicit val matchesWrites = TupleFormat.tuple2Writes[Int, Int]
            val result = JsArray(hits.map { hit => json.minify(Json.obj("tag" -> hit.tag, "matches" -> hit.matches)) })
            Ok(result)
          }
        }
      } getOrElse {
        Future.successful(Forbidden(Json.obj("error" -> "permission_denied")))
      }
    } getOrElse {
      Future.successful(BadRequest(Json.obj("error" -> "invalid_library_id")))
    }
  }

  private val MaxBookmarkJsonSize = 2 * 1024 * 1024 // = 2MB, about 14.5K bookmarks
  def importBrowserBookmarks(id: PublicId[Library]) = (UserAction andThen LibraryWriteAction(id))(parse.tolerantJson(maxLength = MaxBookmarkJsonSize)) { request =>
    val userId = request.userId
    val installationId = request.kifiInstallationId
    val json = request.body

    SafeFuture {
      log.debug(s"adding bookmarks import of user $userId")

      implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.bookmarkImport).build
      val libraryId = Library.decodePublicId(id).get
      keepInterner.persistRawKeeps(rawKeepFactory.toRawKeep(userId, KeepSource.bookmarkImport, json, installationId = installationId, libraryId = Some(libraryId)))
    }
    Status(ACCEPTED)(JsNumber(0))
  }

  private def decode(publicId: PublicId[Library])(action: Id[Library] => Result): Result = {
    Library.decodePublicId(publicId) match {
      case Failure(_) => BadRequest(Json.obj("error" -> "invalid_library_id"))
      case Success(id) => action(id)
    }
  }
}

object ExtLibraryController {
  val defaultImageSize = ImageSize(600, 480)
}
