package com.keepit.controllers.mobile

import com.google.inject.Inject
import com.keepit.commanders._
import com.keepit.common.controller._
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.db.slick.Database
import com.keepit.common.mail.EmailAddress
import com.keepit.common.net.URI
import com.keepit.common.social.BasicUserRepo
import com.keepit.controllers.mobile.ImplicitHelper._
import com.keepit.heimdal.HeimdalContextBuilderFactory
import com.keepit.model._
import com.keepit.normalizer.NormalizedURIInterner
import com.keepit.shoebox.controllers.LibraryAccessActions
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.{ JsArray, JsObject, JsString, Json }

import scala.concurrent.Future
import scala.util.{ Failure, Success }
import com.keepit.common.store.ImageSize

class MobileLibraryController @Inject() (
  db: Database,
  libraryRepo: LibraryRepo,
  keepRepo: KeepRepo,
  collectionRepo: CollectionRepo,
  userRepo: UserRepo,
  basicUserRepo: BasicUserRepo,
  keepsCommander: KeepsCommander,
  pageCommander: PageCommander,
  keepImageCommander: KeepImageCommander,
  normalizedUriInterner: NormalizedURIInterner,
  heimdalContextBuilder: HeimdalContextBuilderFactory,
  val libraryCommander: LibraryCommander,
  val userActionsHelper: UserActionsHelper,
  val publicIdConfig: PublicIdConfiguration,
  implicit val config: PublicIdConfiguration)
    extends UserActions with LibraryAccessActions with ShoeboxServiceController {

  def createLibrary() = UserAction(parse.tolerantJson) { request =>
    val jsonBody = request.body
    val name = (jsonBody \ "name").as[String]
    val description = (jsonBody \ "description").asOpt[String]
    val visibility = (jsonBody \ "visibility").as[LibraryVisibility]
    val slug = LibrarySlug.generateFromName(name)
    val addRequest = LibraryAddRequest(name, visibility, description, slug)

    implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.mobile).build
    libraryCommander.addLibrary(addRequest, request.userId) match {
      case Left(fail) => Status(fail.status)(Json.obj("error" -> fail.message))
      case Right(lib) =>
        val (owner, numKeeps) = db.readOnlyMaster { implicit s => (basicUserRepo.load(lib.ownerId), keepRepo.getCountByLibrary(lib.id.get)) }
        Ok(Json.toJson(LibraryInfo.fromLibraryAndOwner(lib, owner, numKeeps, None)))
    }
  }

  def modifyLibrary(pubId: PublicId[Library]) = (UserAction andThen LibraryWriteAction(pubId))(parse.tolerantJson) { request =>
    val libId = Library.decodePublicId(pubId).get
    val json = request.body
    val newName = (json \ "newName").asOpt[String]
    val newDescription = (json \ "newDescription").asOpt[String]
    val newVisibility = (json \ "newVisibility").asOpt[LibraryVisibility]
    val newSlug = (json \ "newSlug").asOpt[String]
    val res = libraryCommander.modifyLibrary(libId, request.userId, newName, newDescription, newSlug, newVisibility)
    res match {
      case Left(fail) =>
        Status(fail.status)(Json.obj("error" -> fail.message))
      case Right(lib) =>
        val (owner, numKeeps) = db.readOnlyMaster { implicit s => (basicUserRepo.load(lib.ownerId), keepRepo.getCountByLibrary(libId)) }
        Ok(Json.toJson(LibraryInfo.fromLibraryAndOwner(lib, owner, numKeeps, None)))
    }
  }

  def deleteLibrary(pubId: PublicId[Library]) = (UserAction andThen LibraryWriteAction(pubId)) { request =>
    val libId = Library.decodePublicId(pubId).get
    implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.mobile).build
    libraryCommander.removeLibrary(libId, request.userId) match {
      case Some(fail) => Status(fail.status)(Json.obj("error" -> fail.message))
      case _ => NoContent
    }
  }

  def getLibraryById(pubId: PublicId[Library]) = (MaybeUserAction andThen LibraryViewAction(pubId)).async { request =>
    val id = Library.decodePublicId(pubId).get
    libraryCommander.getLibraryById(request.userIdOpt, false, id) map {
      case (libInfo, accessStr) => Ok(Json.obj("library" -> Json.toJson(libInfo), "membership" -> accessStr))
    }
  }

  def getLibraryByPath(userStr: String, slugStr: String) = MaybeUserAction.async { request =>
    libraryCommander.getLibraryWithUsernameAndSlug(userStr, LibrarySlug(slugStr), followRedirect = true) match {
      case Right(library) =>
        LibraryViewAction(Library.publicId(library.id.get)).invokeBlock(request, { _: MaybeUserRequest[_] =>
          request.userIdOpt.map { userId => libraryCommander.updateLastView(userId, library.id.get) }
          libraryCommander.createFullLibraryInfo(request.userIdOpt, false, library).map { libInfo =>
            val accessStr = request.userIdOpt.map { userId =>
              libraryCommander.getAccessStr(userId, library.id.get)
            }.flatten.getOrElse {
              "none"
            }
            Ok(Json.obj("library" -> Json.toJson(libInfo), "membership" -> accessStr))
          }
        })
      case Left(fail) =>
        Future.successful(Status(fail.status)(Json.obj("error" -> fail.message)))
    }
  }

  def getLibrarySummariesWithUrl = UserAction(parse.tolerantJson) { request =>
    val urlOpt = (request.body \ "url").asOpt[String]
    val parseUrl = urlOpt.map { url =>
      pageCommander.getUrlInfo(url, request.userId)
    }

    val (librariesWithMemberships, _) = libraryCommander.getLibrariesByUser(request.userId)
    val writeableLibraries = librariesWithMemberships.filter {
      case (membership, _) =>
        membership.canWrite
    }.map {
      case (mem, library) =>
        val (owner, numKeeps) = db.readOnlyMaster { implicit s =>
          (basicUserRepo.load(library.ownerId), keepRepo.getCountByLibrary(library.id.get))
        }
        val info = LibraryInfo.fromLibraryAndOwner(library, owner, numKeeps, None)
        var memInfo = Json.obj("access" -> mem.access)
        if (mem.lastViewed.nonEmpty) {
          memInfo = memInfo ++ Json.obj("lastViewed" -> mem.lastViewed)
        }
        Json.toJson(info).as[JsObject] ++ memInfo
    }

    val libsResponse = Json.obj("libraries" -> writeableLibraries)
    val keepResponse = parseUrl.collect {
      case Left(error) =>
        Json.obj("error" -> error)
      case Right(keepData) if keepData.nonEmpty =>
        val keepDataWithTags = db.readOnlyMaster { implicit s =>
          keepData.map { keep =>
            val kId = keepRepo.get(keep.id).id.get
            Json.toJson(keep).as[JsObject] + ("tags", Json.toJson(collectionRepo.getTagsByKeepId(kId)))
          }
        }
        Json.obj("alreadyKept" -> keepDataWithTags)
    }.getOrElse(Json.obj())
    Ok(libsResponse ++ keepResponse)
  }

  def getLibrarySummariesByUser = UserAction { request =>
    val (librariesWithMemberships, librariesWithInvites) = libraryCommander.getLibrariesByUser(request.userId)
    // rule out invites that are not duplicate invites to same library (only show library invite with highest access)
    val invitesToShow = librariesWithInvites.groupBy(x => x._2).map { lib =>
      val invites = lib._2.unzip._1
      val highestInvite = invites.sorted.last
      (highestInvite, lib._1)
    }.toSeq

    val libsFollowing = for ((mem, library) <- librariesWithMemberships) yield {
      val (owner, numKeeps) = db.readOnlyMaster { implicit s =>
        (basicUserRepo.load(library.ownerId), keepRepo.getCountByLibrary(library.id.get))
      }
      val info = LibraryInfo.fromLibraryAndOwner(library, owner, numKeeps, None)
      val memInfo = if (mem.lastViewed.nonEmpty) Json.obj("access" -> mem.access, "lastViewed" -> mem.lastViewed) else Json.obj("access" -> mem.access)
      Json.toJson(info).as[JsObject] ++ memInfo
    }
    val libsInvitedTo = for (invitePair <- invitesToShow) yield {
      val invite = invitePair._1
      val lib = invitePair._2
      val (libOwner, inviter, numKeeps) = db.readOnlyMaster { implicit s =>
        (basicUserRepo.load(lib.ownerId),
          basicUserRepo.load(invite.inviterId),
          keepRepo.getCountByLibrary(lib.id.get))
      }
      val info = LibraryInfo.fromLibraryAndOwner(lib, libOwner, numKeeps, Some(inviter))
      Json.toJson(info).as[JsObject] ++ Json.obj("access" -> invite.access)
    }
    Ok(Json.obj("libraries" -> libsFollowing, "invited" -> libsInvitedTo))
  }

  def inviteUsersToLibrary(pubId: PublicId[Library]) = UserAction.async(parse.tolerantJson) { request =>
    Library.decodePublicId(pubId) match {
      case Failure(ex) =>
        Future.successful(BadRequest(Json.obj("error" -> "invalid_id")))
      case Success(id) =>
        val invites = (request.body \ "invites").as[JsArray].value
        val msgOpt = (request.body \ "message").asOpt[String]
        val message = if (msgOpt == Some("")) None else msgOpt

        val validInviteList = db.readOnlyMaster { implicit s =>
          invites.map { i =>
            val access = (i \ "access").as[LibraryAccess]
            val id = (i \ "type").as[String] match {
              case "user" if userRepo.getOpt((i \ "id").as[ExternalId[User]]).nonEmpty =>
                Left(userRepo.getOpt((i \ "id").as[ExternalId[User]]).get.id.get)
              case "email" => Right((i \ "id").as[EmailAddress])
            }
            (id, access, message)
          }
        }
        implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.mobile).build
        libraryCommander.inviteUsersToLibrary(id, request.userId, validInviteList).map {
          case Left(fail) =>
            Status(fail.status)(Json.obj("error" -> fail.message))
          case Right(inviteesWithAccess) =>
            val result = inviteesWithAccess.map {
              case (Left(user), access) => Json.obj("user" -> user.externalId, "access" -> access)
              case (Right(contact), access) => Json.obj("email" -> contact.email, "access" -> access)
            }
            Ok(Json.toJson(result))
        }
    }
  }

  def joinLibrary(pubId: PublicId[Library]) = UserAction { request =>
    val idTry = Library.decodePublicId(pubId)
    idTry match {
      case Failure(ex) =>
        BadRequest(Json.obj("error" -> "invalid_id"))
      case Success(libId) =>
        implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.mobile).build
        val res = libraryCommander.joinLibrary(request.userId, libId)
        res match {
          case Left(fail) =>
            Status(fail.status)(Json.obj("error" -> fail.message))
          case Right(lib) =>
            val (owner, numKeeps) = db.readOnlyMaster { implicit s => (basicUserRepo.load(lib.ownerId), keepRepo.getCountByLibrary(libId)) }
            Ok(Json.toJson(LibraryInfo.fromLibraryAndOwner(lib, owner, numKeeps, None)))
        }
    }
  }

  def declineLibrary(pubId: PublicId[Library]) = UserAction { request =>
    val idTry = Library.decodePublicId(pubId)
    idTry match {
      case Failure(ex) =>
        BadRequest(Json.obj("error" -> "invalid_id"))
      case Success(libId) =>
        libraryCommander.declineLibrary(request.userId, libId)
        Ok(JsString("success"))
    }
  }

  def leaveLibrary(pubId: PublicId[Library]) = UserAction { request =>
    val idTry = Library.decodePublicId(pubId)
    idTry match {
      case Failure(ex) =>
        BadRequest(Json.obj("error" -> "invalid_id"))
      case Success(id) =>
        implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.mobile).build
        libraryCommander.leaveLibrary(id, request.userId) match {
          case Left(fail) => Status(fail.status)(Json.obj("error" -> fail.message))
          case Right(_) => Ok(JsString("success"))
        }
    }
  }

  def getKeeps(pubId: PublicId[Library], offset: Int, limit: Int, idealImageWidth: Option[Int], idealImageHeight: Option[Int]) = (MaybeUserAction andThen LibraryViewAction(pubId)).async { request =>
    if (limit > 30) { Future.successful(BadRequest(Json.obj("error" -> "invalid_limit"))) }
    else Library.decodePublicId(pubId) match {
      case Failure(ex) => Future.successful(BadRequest(Json.obj("error" -> "invalid_id")))
      case Success(libraryId) =>
        val idealImageSize = {
          for {
            w <- idealImageWidth
            h <- idealImageHeight
          } yield ImageSize(w, h)
        } getOrElse ProcessedImageSize.Large.idealSize
        for {
          keeps <- libraryCommander.getKeeps(libraryId, offset, limit)
          keepInfos <- keepsCommander.decorateKeepsIntoKeepInfos(request.userIdOpt, false, keeps, idealImageSize)
        } yield {
          Ok(Json.obj("keeps" -> keepInfos))
        }
    }
  }

  def keepToLibrary(pubId: PublicId[Library]) = (UserAction andThen LibraryWriteAction(pubId)).async(parse.tolerantJson) { request =>
    val libraryId = Library.decodePublicId(pubId).get
    val jsonBody = request.body
    val title = (jsonBody \ "title").asOpt[String]
    val url = (jsonBody \ "url").as[String]
    val tagNames = (jsonBody \ "tagNames").as[Seq[String]]
    val imageUrlOpt = (jsonBody \ "imageUrl").asOpt[String]
    val rawKeep = RawBookmarkRepresentation(title, url, None)
    val source = KeepSource.mobile
    implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, source).build
    keepsCommander.keepWithSelectedTags(request.userId, rawKeep, libraryId, source, tagNames) match {
      case Left(msg) =>
        Future.successful(BadRequest(msg))
      case Right((keep, tags)) =>
        val returnObj = Json.obj(
          "keep" -> Json.toJson(KeepInfo.fromKeep(keep)),
          "tags" -> tags.map(tag => Json.obj("name" -> tag.name, "id" -> tag.externalId))
        )
        imageUrlOpt.map { imageUrl =>
          keepImageCommander.setKeepImageFromUrl(imageUrl, keep.id.get, KeepImageSource.UserPicked)
        }
        Future.successful(Ok(returnObj))
    }
  }

  def unkeepFromLibrary(pubId: PublicId[Library], kId: ExternalId[Keep]) = (UserAction andThen LibraryWriteAction(pubId)) { request =>
    val libraryId = Library.decodePublicId(pubId).get

    implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.mobile).build
    keepsCommander.unkeepOneFromLibrary(kId, libraryId, request.userId) match {
      case Left(failMsg) =>
        BadRequest(Json.obj("error" -> failMsg))
      case Right(keepInfo) =>
        NoContent
    }
  }

  def getLibraryMembers(pubId: PublicId[Library], offset: Int, limit: Int) = (MaybeUserAction andThen LibraryViewAction(pubId)) { request =>
    if (limit > 30) { BadRequest(Json.obj("error" -> "invalid_limit")) }
    else Library.decodePublicId(pubId) match {
      case Failure(ex) => BadRequest(Json.obj("error" -> "invalid_id"))
      case Success(libraryId) =>
        val library = db.readOnlyMaster { implicit s => libraryRepo.get(libraryId) }
        val showInvites = request.userIdOpt.map(uId => uId == library.ownerId).getOrElse(false)
        val (collaborators, followers, inviteesWithInvites, _) = libraryCommander.getLibraryMembers(libraryId, offset, limit, fillInWithInvites = showInvites)
        val maybeMembers = libraryCommander.buildMaybeLibraryMembers(collaborators, followers, inviteesWithInvites)
        Ok(Json.obj("members" -> maybeMembers))
    }
  }

  def suggestMembers(pubId: PublicId[Library], query: Option[String], limit: Int) = (UserAction andThen LibraryViewAction(pubId)).async { request =>
    request match {
      case req: UserRequest[_] => {
        if (limit > 30) { Future.successful(BadRequest(Json.obj("error" -> "invalid_limit"))) }
        else Library.decodePublicId(pubId) match {
          case Failure(ex) => Future.successful(BadRequest(Json.obj("error" -> "invalid_id")))
          case Success(libraryId) => libraryCommander.suggestMembers(req.userId, libraryId, query, Some(limit)).map { members => Ok(Json.obj("members" -> members)) }
        }
      }
      case _ => Future.successful(Forbidden)
    }
  }
}

private object ImplicitHelper {
  implicit class PutUserIdOptInMaybeAuthReq(val request: MaybeUserRequest[_]) extends AnyVal {
    def userIdOpt: Option[Id[User]] = request match {
      case u: UserRequest[_] => Some(u.userId)
      case _ => None
    }
  }
}
