package com.keepit.controllers.ext

import com.google.inject.Inject
import com.keepit.commanders.{ KeepsCommander, LibraryCommander, LibraryData, RawBookmarkRepresentation, _ }
import com.keepit.common.akka.SafeFuture
import com.keepit.common.controller.{ UserActions, UserActionsHelper, ShoeboxServiceController, _ }
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.slick.Database
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.healthcheck.{ AirbrakeNotifier }
import com.keepit.common.json
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.store.ImageSize
import com.keepit.heimdal.HeimdalContextBuilderFactory
import com.keepit.model._
import com.keepit.shoebox.controllers.LibraryAccessActions

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json._
import play.api.mvc.Result
import com.keepit.common.core._

import scala.concurrent.Future
import scala.util.{ Failure, Success, Try }
import com.keepit.common.json.TupleFormat

class ExtLibraryController @Inject() (
  db: Database,
  val libraryCommander: LibraryCommander,
  libraryImageCommander: LibraryImageCommander,
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
    val datas = libraryCommander.getLibrariesUserCanKeepTo(request.userId) map {
      case (lib, hasCollaborators, subscribedToUpdates) =>
        val owner = db.readOnlyMaster { implicit s => basicUserRepo.load(lib.ownerId) }
        LibraryData(
          id = Library.publicId(lib.id.get),
          name = lib.name,
          color = lib.color,
          visibility = lib.visibility,
          path = Library.formatLibraryPath(owner.username, lib.slug),
          hasCollaborators = hasCollaborators,
          subscribedToUpdates = subscribedToUpdates
        )
    }
    Ok(Json.obj("libraries" -> datas))
  }

  def createLibrary() = UserAction(parse.tolerantJson) { request =>
    val body = request.body.as[JsObject]
    val name = (body \ "name").as[String]
    val visibility = (body \ "visibility").as[LibraryVisibility]
    val slug = LibrarySlug.generateFromName(name)
    val addRequest = LibraryAddRequest(name = name, visibility = visibility, slug = slug)
    implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.keeper).build
    libraryCommander.addLibrary(addRequest, request.userId) match {
      case Left(fail) => Status(fail.status)(Json.obj("error" -> fail.message))
      case Right(lib) =>
        Ok(Json.toJson(LibraryData(
          id = Library.publicId(lib.id.get),
          name = lib.name,
          color = lib.color,
          visibility = lib.visibility,
          path = Library.formatLibraryPath(request.user.username, lib.slug),
          hasCollaborators = false,
          subscribedToUpdates = false)))
    }
  }

  def getLibrary(libraryPubId: PublicId[Library]) = UserAction { request =>
    decode(libraryPubId) { libraryId =>
      libraryCommander.getLibraryWithOwnerAndCounts(libraryId, request.userId) match {
        case Left(fail) =>
          Status(fail.status)(Json.obj("error" -> fail.message))
        case Right((library, owner, followerCount, following, subscribedToUpdates)) =>
          val imageOpt = libraryImageCommander.getBestImageForLibrary(libraryId, ExtLibraryController.defaultImageSize)
          Ok(Json.obj(
            "name" -> library.name,
            "slug" -> library.slug,
            "visibility" -> library.visibility,
            "color" -> library.color,
            "image" -> imageOpt.map(LibraryImageInfo.createInfo),
            "owner" -> owner,
            "keeps" -> library.keepCount,
            "followers" -> followerCount,
            "following" -> following,
            "subscribedToUpdates" -> subscribedToUpdates))
      }
    }
  }

  def deleteLibrary(libraryPubId: PublicId[Library]) = UserAction { request =>
    decode(libraryPubId) { libraryId =>
      implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.keeper).build
      libraryCommander.removeLibrary(libraryId, request.userId) match {
        case Some(fail) => Status(fail.status)(Json.obj("error" -> fail.message))
        case _ => NoContent
      }
    }
  }

  def joinLibrary(libraryPubId: PublicId[Library]) = UserAction { request =>
    decode(libraryPubId) { libraryId =>
      implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.keeper).build
      libraryCommander.joinLibrary(request.userId, libraryId) match {
        case Left(fail) => Status(fail.status)(Json.obj("error" -> fail.message))
        case Right(lib) => NoContent
      }
    }
  }

  def leaveLibrary(libraryPubId: PublicId[Library]) = UserAction { request =>
    decode(libraryPubId) { libraryId =>
      implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.keeper).build
      libraryCommander.leaveLibrary(libraryId, request.userId) match {
        case Left(fail) => Status(fail.status)(Json.obj("error" -> fail.message))
        case Right(_) => NoContent
      }
    }
  }

  def setSubscribedToUpdates(pubId: PublicId[Library], newSubscripedToUpdate: Boolean) = UserAction { request =>
    val libraryId = Library.decodePublicId(pubId).get
    libraryCommander.updatedLibraryUpdateSubscription(request.userId, libraryId, newSubscripedToUpdate) match {
      case Right(mem) => Ok
      case Left(fail) => Status(fail.status)(Json.obj("error" -> fail.message))
    }
  }

  def addKeep(libraryPubId: PublicId[Library]) = UserAction(parse.tolerantJson) { request =>
    decode(libraryPubId) { libraryId =>
      db.readOnlyMaster { implicit s =>
        libraryMembershipRepo.getWithLibraryIdAndUserId(libraryId, request.userId)
      } match {
        case Some(mem) if mem.canWrite => // TODO: also allow keep if mem.canInsert and keep is not already in library
          val body = request.body
          val source = KeepSource.keeper
          val hcb = heimdalContextBuilder.withRequestInfoAndSource(request, source)
          if ((body \ "guided").asOpt[Boolean].getOrElse(false)) {
            hcb += ("guided", true)
          }
          (body \ "how").asOpt[String] foreach { how =>
            hcb += ("subsource", how)
          }
          implicit val context = hcb.build

          val (keep, isNewKeep) = keepsCommander.keepOne(body.as[RawBookmarkRepresentation], request.userId, libraryId, request.kifiInstallationId, source, SocialShare(body))
          val (tags, image) = if (isNewKeep) {
            val existingImageUri = db.readOnlyReplica { implicit session =>
              keepImageCommander.getExistingImageUrlForKeepUri(keep.uriId)
            }
            (Seq.empty, existingImageUri) // optimizing the common case
          } else {
            val tags = db.readOnlyReplica { implicit s =>
              collectionRepo.getHashtagsByKeepId(keep.id.get)
            }
            val image = keepImageCommander.getBestImageForKeep(keep.id.get, ScaleImageRequest(ExtLibraryController.defaultImageSize)).flatten.map(keepImageCommander.getUrl)
            (tags, image)
          }

          val keepData = KeepData(
            keep.externalId,
            mine = keep.userId == request.userId,
            removable = mem.canWrite,
            secret = keep.visibility == LibraryVisibility.SECRET,
            libraryId = libraryPubId)
          val moarKeepData = MoarKeepData(
            title = keep.title,
            image = image,
            note = keep.note,
            tags = tags.map(_.tag).toSeq)
          Ok(Json.toJson(keepData).as[JsObject] ++ Json.toJson(moarKeepData).as[JsObject])
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
          val keepImageUrl = keepImageCommander.getBestImageForKeep(keep.id.get, ScaleImageRequest(idealSize)).flatten.map(keepImageCommander.getUrl)
          val tags = db.readOnlyReplica { implicit s =>
            collectionRepo.getHashtagsByKeepId(keep.id.get)
          }
          Ok(Json.toJson(MoarKeepData(keep.title, keepImageUrl, keep.note, tags.map(_.tag).toSeq)))
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

  def editKeepNote(libraryPubId: PublicId[Library], keepExtId: ExternalId[Keep]) = UserAction(parse.tolerantJson) { request =>
    decode(libraryPubId) { libraryId =>
      db.readOnlyMaster { implicit s =>
        keepRepo.getOpt(keepExtId)
      } match {
        case None =>
          NotFound(Json.obj("error" -> "keep_id_not_found"))
        case Some(keep) =>
          val body = request.body.as[JsObject]
          val newNote = (body \ "note").as[String]
          implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.keeper).build
          keepsCommander.updateKeepNote(request.userId, keep, newNote)
          NoContent
      }
    }
  }

  def tagKeep(libraryPubId: PublicId[Library], keepExtId: ExternalId[Keep], tag: String) = UserAction { request =>
    decode(libraryPubId) { libraryId =>
      db.readOnlyMaster { implicit session =>
        libraryMembershipRepo.getWithLibraryIdAndUserId(libraryId, request.userId)
      } match {
        case Some(mem) if mem.isOwner => // TODO: change to .canWrite
          keepsCommander.getKeep(libraryId, keepExtId, request.userId) match {
            case Right(keep) if (tag == "Add a tag") =>
              log.warn(s"user ${request.userId} attempted to apply 'Add a tag'") // airbrake instead once these have stopped?
              BadRequest(Json.obj("error" -> "disallowed_tag_name"))
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
        case Some(mem) if mem.isOwner => // TODO: change to .canWrite
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

  def suggestTags(pubId: PublicId[Library], keepId: ExternalId[Keep], query: Option[String], limit: Int) = (UserAction andThen LibraryWriteAction(pubId)).async { request =>
    keepsCommander.suggestTags(request.userId, keepId, query, limit).imap { tagsAndMatches =>
      implicit val matchesWrites = TupleFormat.tuple2Writes[Int, Int]
      val result = JsArray(tagsAndMatches.map { case (tag, matches) => json.minify(Json.obj("tag" -> tag, "matches" -> matches)) })
      Ok(result)
    }
  }

  def deprecatedSearchTags(libraryPubId: PublicId[Library], query: String, limit: Option[Int]) = UserAction.async { request =>
    Library.decodePublicId(libraryPubId).toOption map { libraryId =>
      db.readOnlyMaster { implicit session =>
        libraryMembershipRepo.getWithLibraryIdAndUserId(libraryId, request.userId)
      } map { _ =>
        keepsCommander.searchTags(request.userId, query, limit) map { hits =>
          implicit val matchesWrites = TupleFormat.tuple2Writes[Int, Int]
          val result = JsArray(hits.map { hit => json.minify(Json.obj("tag" -> hit.tag, "matches" -> hit.matches)) })
          Ok(result)
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
