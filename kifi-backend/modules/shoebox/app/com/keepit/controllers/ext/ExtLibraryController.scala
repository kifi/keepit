package com.keepit.controllers.ext

import com.google.inject.Inject
import com.keepit.commanders._
import com.keepit.common.akka.SafeFuture
import com.keepit.common.controller.{ UserActions, UserActionsHelper, ShoeboxServiceController, _ }
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.slick.Database
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.healthcheck.{ AirbrakeNotifier }
import com.keepit.common.json
import com.keepit.common.core.optionExtensionOps
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.store.{ S3ImageConfig, ImageSize }
import com.keepit.heimdal.HeimdalContextBuilderFactory
import com.keepit.model.ExternalLibrarySpace.{ ExternalOrganizationSpace, ExternalUserSpace }
import com.keepit.model._
import com.keepit.rover.RoverServiceClient
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
  val libraryInfoCommander: LibraryInfoCommander,
  val libraryAccessCommander: LibraryAccessCommander,
  libraryMembershipCommander: LibraryMembershipCommander,
  libraryImageCommander: LibraryImageCommander,
  keepsCommander: KeepCommander,
  basicUserRepo: BasicUserRepo,
  libraryMembershipRepo: LibraryMembershipRepo,
  libPathCommander: PathCommander,
  heimdalContextBuilder: HeimdalContextBuilderFactory,
  keepImageCommander: KeepImageCommander,
  organizationAvatarCommander: OrganizationAvatarCommander,
  val userActionsHelper: UserActionsHelper,
  userRepo: UserRepo,
  keepRepo: KeepRepo,
  collectionRepo: CollectionRepo,
  airbrake: AirbrakeNotifier,
  rawKeepInterner: RawKeepInterner,
  rawKeepFactory: RawKeepFactory,
  rover: RoverServiceClient,
  implicit val imageConfig: S3ImageConfig,
  implicit val publicIdConfig: PublicIdConfiguration)
    extends UserActions with LibraryAccessActions with ShoeboxServiceController {

  val defaultLibraryImageSize = ProcessedImageSize.Medium.idealSize

  def getLibraries(allowOpenCollab: Boolean) = UserAction { request =>
    val librariesWithMembershipAndCollaborators = libraryInfoCommander.getLibrariesUserCanKeepTo(request.userId, allowOpenCollab)
    val (basicUserById, orgAvatarsById) = {
      val allUserIds = librariesWithMembershipAndCollaborators.flatMap(_._3).toSet
      val orgIds = librariesWithMembershipAndCollaborators.map(_._1).flatMap(_.organizationId)
      db.readOnlyMaster { implicit s =>
        val basicUserById = basicUserRepo.loadAll(allUserIds)
        val orgAvatarsById = organizationAvatarCommander.getBestImagesByOrgIds(orgIds.toSet, defaultLibraryImageSize)
        (basicUserById, orgAvatarsById)
      }
    }

    val datas = librariesWithMembershipAndCollaborators map {
      case (lib, membership, collaboratorsIds) =>
        val owner = basicUserById.getOrElse(lib.ownerId, throw new Exception(s"owner of $lib does not have a membership model"))
        val collabs = (collaboratorsIds - request.userId).map(basicUserById(_)).toSeq
        val orgAvatarPath = lib.organizationId.map { orgId =>
          orgAvatarsById.get(orgId).map(_.imagePath).getOrElse(throw new Exception(s"No avatar for org of lib $lib"))
        }

        val membershipInfo = membership.map { mem =>
          db.readOnlyReplica { implicit session => libraryMembershipCommander.createMembershipInfo(mem) }
        }

        LibraryData(
          id = Library.publicId(lib.id.get),
          name = lib.name,
          color = lib.color,
          visibility = lib.visibility,
          path = libPathCommander.getPathForLibrary(lib),
          hasCollaborators = collabs.nonEmpty,
          subscribedToUpdates = membership.exists(_.subscribedToUpdates),
          collaborators = collabs,
          orgAvatar = orgAvatarPath,
          membership = membershipInfo
        )
    }
    Ok(Json.obj("libraries" -> datas))
  }

  def createLibrary() = UserAction(parse.tolerantJson) { request =>
    val externalCreateRequestValidated = request.body.validate[ExternalLibraryInitialValues](ExternalLibraryInitialValues.reads)

    externalCreateRequestValidated match {
      case JsError(errs) =>
        airbrake.notify(s"Could not json-validate addLibRequest from ${request.userId}: ${request.body}", new JsResultException(errs))
        BadRequest(Json.obj("error" -> "badly_formatted_request", "details" -> errs.toString))
      case JsSuccess(externalCreateRequest, _) =>
        val libCreateRequest = db.readOnlyReplica { implicit session =>
          val space = externalCreateRequest.space map {
            case ExternalUserSpace(extId) => LibrarySpace.fromUserId(userRepo.getByExternalId(extId).id.get)
            case ExternalOrganizationSpace(pubId) => LibrarySpace.fromOrganizationId(Organization.decodePublicId(pubId).get)
          }
          LibraryInitialValues(
            name = externalCreateRequest.name,
            slug = externalCreateRequest.slug,
            visibility = externalCreateRequest.visibility,
            description = externalCreateRequest.description,
            color = externalCreateRequest.color,
            listed = externalCreateRequest.listed,
            whoCanInvite = externalCreateRequest.whoCanInvite,
            space = space
          )
        }

        implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.keeper).build
        libraryCommander.createLibrary(libCreateRequest, request.userId) match {
          case Left(fail) => Status(fail.status)(Json.obj("error" -> fail.message))
          case Right(lib) =>
            val data = db.readOnlyMaster { implicit session =>
              val orgAvatarPath = lib.organizationId.map { orgId =>
                organizationAvatarCommander.getBestImageByOrgId(orgId, ExtLibraryController.defaultImageSize).imagePath
              }
              val membershipOpt = libraryMembershipRepo.getWithLibraryIdAndUserId(lib.id.get, request.userId)
              val membershipInfo = membershipOpt.map(libraryMembershipCommander.createMembershipInfo)

              LibraryData(
                id = Library.publicId(lib.id.get),
                name = lib.name,
                color = lib.color,
                visibility = lib.visibility,
                path = libPathCommander.getPathForLibrary(lib),
                hasCollaborators = false,
                subscribedToUpdates = membershipOpt.exists(_.subscribedToUpdates),
                collaborators = Seq.empty,
                orgAvatar = orgAvatarPath,
                membership = membershipInfo
              )
            }
            Ok(Json.toJson(data))
        }
    }
  }

  def getLibrary(libraryPubId: PublicId[Library]) = UserAction { request =>
    decode(libraryPubId) { libraryId =>
      libraryInfoCommander.getLibraryWithOwnerAndCounts(libraryId, request.userId) match {
        case Left(fail) =>
          Status(fail.status)(Json.obj("error" -> fail.message))
        case Right((library, owner, followerCount, following, subscribedToUpdates)) =>
          val imageOpt = libraryImageCommander.getBestImageForLibrary(libraryId, ExtLibraryController.defaultImageSize)
          Ok(Json.obj(
            "name" -> library.name,
            "slug" -> library.slug,
            "visibility" -> library.visibility,
            "color" -> library.color,
            "image" -> imageOpt.map(_.asInfo),
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
      libraryCommander.deleteLibrary(libraryId, request.userId) match {
        case Some(fail) => Status(fail.status)(Json.obj("error" -> fail.message))
        case _ => NoContent
      }
    }
  }

  def joinLibrary(libraryPubId: PublicId[Library]) = UserAction { request =>
    decode(libraryPubId) { libraryId =>
      implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.keeper).build
      libraryMembershipCommander.joinLibrary(request.userId, libraryId) match {
        case Left(fail) => Status(fail.status)(Json.obj("error" -> fail.message))
        case Right(lib) => NoContent
      }
    }
  }

  def leaveLibrary(libraryPubId: PublicId[Library]) = UserAction { request =>
    decode(libraryPubId) { libraryId =>
      implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.keeper).build
      libraryMembershipCommander.leaveLibrary(libraryId, request.userId) match {
        case Left(fail) => Status(fail.status)(Json.obj("error" -> fail.message))
        case Right(_) => NoContent
      }
    }
  }

  def setSubscribedToUpdates(pubId: PublicId[Library], newSubscripedToUpdate: Boolean) = UserAction { request =>
    val libraryId = Library.decodePublicId(pubId).get
    libraryCommander.updateSubscribedToLibrary(request.userId, libraryId, newSubscripedToUpdate) match {
      case Right(mem) => NoContent
      case Left(fail) => Status(fail.status)(Json.obj("error" -> fail.message))
    }
  }

  def addKeep(libraryPubId: PublicId[Library]) = UserAction.async(parse.tolerantJson) { request =>
    decodeAsync(libraryPubId) { libraryId =>
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

          val (keep, isNewKeep) = keepsCommander.keepOne(body.as[RawBookmarkRepresentation], request.userId, libraryId, source, SocialShare(body))
          val futureTagsAndImage = if (isNewKeep) {
            rover.getImagesByUris(Set(keep.uriId)).imap(_.get(keep.uriId)).map { imagesMaybe =>
              val existingImageUri = imagesMaybe.flatMap(_.getLargest.map(_.path.getUrl))
              (Seq.empty, existingImageUri) // optimizing the common case
            }
          } else {
            val tags = db.readOnlyReplica { implicit s =>
              collectionRepo.getHashtagsByKeepId(keep.id.get)
            }
            val image = keepImageCommander.getBestImageForKeep(keep.id.get, ScaleImageRequest(ExtLibraryController.defaultImageSize)).flatten.map(keepImageCommander.getUrl)
            Future.successful((tags, image))
          }

          futureTagsAndImage.map {
            case (tags, image) =>
              val keepData = KeepData(
                keep.externalId,
                mine = keep.userId.safely.contains(request.userId),
                removable = mem.canWrite,
                secret = keep.visibility == LibraryVisibility.SECRET,
                visibility = keep.visibility,
                libraryId = Some(libraryPubId))
              val moarKeepData = MoarKeepData(
                title = keep.title,
                image = image,
                note = keep.note,
                tags = tags.map(_.tag).toSeq)
              Ok(Json.toJson(keepData).as[JsObject] ++ Json.toJson(moarKeepData).as[JsObject])
          }

        case _ =>
          Future.successful(Forbidden(Json.obj("error" -> "invalid_access")))
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
    (request.body \ "title").validate[Option[String]] match {
      case JsError(errs) => throw new Exception(s"invalid request: ${request.body.toString} for updateKeep, expected 'title'")
      case JsSuccess(titleOpt, _) =>
        titleOpt.map { title =>
          db.readWrite { implicit s =>
            Try(keepRepo.convertExternalId(keepExtId)).map { keepId =>
              keepsCommander.updateKeepTitle(keepId, request.userId, title) match {
                case Failure(fail: KeepFail) => fail.asErrorResponse
                case Failure(unhandled) => throw unhandled
                case Success(keep) => NoContent
              }
            }.getOrElse(KeepFail.KEEP_NOT_FOUND.asErrorResponse)
          }
        }.getOrElse(NoContent)
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
          db.readWrite { implicit session =>
            keepsCommander.updateKeepNote(request.userId, keep, newNote)
          }
          NoContent
      }
    }
  }

  def suggestTags(pubId: PublicId[Library], keepId: ExternalId[Keep], query: Option[String], limit: Int) = (UserAction andThen LibraryWriteAction(pubId)).async { request =>
    keepsCommander.suggestTags(request.userId, Some(keepId), query, limit).imap { tagsAndMatches =>
      implicit val matchesWrites = TupleFormat.tuple2Writes[Int, Int]
      val result = JsArray(tagsAndMatches.map { case (tag, matches) => json.aggressiveMinify(Json.obj("tag" -> tag, "matches" -> matches)) })
      Ok(result)
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
      rawKeepInterner.persistRawKeeps(rawKeepFactory.toRawKeep(userId, KeepSource.bookmarkImport, json, installationId = installationId, libraryId = Some(libraryId)))
    }
    Status(ACCEPTED)(JsNumber(0))
  }

  private def decode(publicId: PublicId[Library])(action: Id[Library] => Result): Result = {
    Library.decodePublicId(publicId) match {
      case Failure(_) => BadRequest(Json.obj("error" -> "invalid_library_id"))
      case Success(id) => action(id)
    }
  }

  private def decodeAsync(publicId: PublicId[Library])(action: Id[Library] => Future[Result]): Future[Result] = {
    Library.decodePublicId(publicId) match {
      case Failure(_) => Future.successful(BadRequest(Json.obj("error" -> "invalid_library_id")))
      case Success(id) => action(id)
    }
  }
}

object ExtLibraryController {
  val defaultImageSize = ImageSize(600, 480)
}
