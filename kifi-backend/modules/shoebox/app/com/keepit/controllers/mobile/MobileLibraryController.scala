package com.keepit.controllers.mobile

import com.google.inject.Inject
import com.keepit.commanders._
import com.keepit.common.akka.SafeFuture
import com.keepit.common.controller._
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.db.slick.Database
import com.keepit.common.mail.EmailAddress
import com.keepit.common.net.URI
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.time._
import com.keepit.common.util.Paginator
import com.keepit.controllers.mobile.ImplicitHelper._
import com.keepit.heimdal.HeimdalContextBuilderFactory
import com.keepit.model._
import com.keepit.normalizer.NormalizedURIInterner
import com.keepit.shoebox.controllers.LibraryAccessActions
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.{ JsArray, JsObject, JsString, Json }

import scala.concurrent.Future
import scala.util.{ Try, Failure, Success }
import com.keepit.common.store.ImageSize

class MobileLibraryController @Inject() (
  db: Database,
  libraryRepo: LibraryRepo,
  keepRepo: KeepRepo,
  keepToCollectionRepo: KeepToCollectionRepo,
  collectionRepo: CollectionRepo,
  userRepo: UserRepo,
  basicUserRepo: BasicUserRepo,
  keepsCommander: KeepsCommander,
  pageCommander: PageCommander,
  keepDecorator: KeepDecorator,
  userCommander: UserCommander,
  keepImageCommander: KeepImageCommander,
  libraryImageCommander: LibraryImageCommander,
  normalizedUriInterner: NormalizedURIInterner,
  heimdalContextBuilder: HeimdalContextBuilderFactory,
  clock: Clock,
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
    val color = (jsonBody \ "color").asOpt[LibraryColor]
    val slug = LibrarySlug.generateFromName(name)
    val addRequest = LibraryAddRequest(name = name, visibility = visibility, description = description, slug = slug, color = color)

    implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.mobile).build
    libraryCommander.addLibrary(addRequest, request.userId) match {
      case Left(fail) => Status(fail.status)(Json.obj("error" -> fail.message))
      case Right(lib) =>
        val owner = db.readOnlyMaster { implicit s => basicUserRepo.load(lib.ownerId) }
        val libImage = libraryImageCommander.getBestImageForLibrary(lib.id.get, MobileLibraryController.defaultLibraryImageSize)
        Ok(Json.toJson(LibraryInfo.fromLibraryAndOwner(lib, libImage, owner)))
    }
  }

  def modifyLibrary(pubId: PublicId[Library]) = (UserAction andThen LibraryWriteAction(pubId))(parse.tolerantJson) { request =>
    val libId = Library.decodePublicId(pubId).get
    val json = request.body
    val newName = (json \ "newName").asOpt[String]
    val newDescription = (json \ "newDescription").asOpt[String]
    val newVisibility = (json \ "newVisibility").asOpt[LibraryVisibility]
    val newSlug = (json \ "newSlug").asOpt[String]
    val newColor = (json \ "newColor").asOpt[LibraryColor]
    val newListed = (json \ "newListed").asOpt[Boolean]

    implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.mobile).build
    val modifyRequest = LibraryModifyRequest(newName, newSlug, newVisibility, newDescription, newColor, newListed)
    val res = libraryCommander.modifyLibrary(libId, request.userId, modifyRequest)
    res match {
      case Left(fail) =>
        Status(fail.status)(Json.obj("error" -> fail.message))
      case Right(lib) =>
        val owner = db.readOnlyMaster { implicit s => basicUserRepo.load(lib.ownerId) }
        val libImage = libraryImageCommander.getBestImageForLibrary(lib.id.get, MobileLibraryController.defaultLibraryImageSize)
        Ok(Json.toJson(LibraryInfo.fromLibraryAndOwner(lib, libImage, owner)))
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

  def getLibraryById(pubId: PublicId[Library], imageSize: Option[String] = None) = (MaybeUserAction andThen LibraryViewAction(pubId)).async { request =>
    val libraryId = Library.decodePublicId(pubId).get
    val idealSize = imageSize.flatMap { s => Try(ImageSize(s)).toOption }.getOrElse(MobileLibraryController.defaultLibraryImageSize)
    libraryCommander.getLibraryById(request.userIdOpt, false, libraryId, idealSize) map { libInfo =>
      val memOpt = libraryCommander.getMaybeMembership(request.userIdOpt, libraryId)
      val accessStr = memOpt.map(_.access.value).getOrElse("none")
      Ok(Json.obj("library" -> Json.toJson(libInfo), "membership" -> accessStr))
    }
  }

  def getLibraryByPath(userStr: String, slugStr: String, imageSize: Option[String] = None) = MaybeUserAction.async { request =>
    libraryCommander.getLibraryWithUsernameAndSlug(userStr, LibrarySlug(slugStr)) match {
      case Right(library) =>
        LibraryViewAction(Library.publicId(library.id.get)).invokeBlock(request, { _: MaybeUserRequest[_] =>
          request.userIdOpt.map { userId => libraryCommander.updateLastView(userId, library.id.get) }
          val idealSize = imageSize.flatMap { s => Try(ImageSize(s)).toOption }.getOrElse(MobileLibraryController.defaultLibraryImageSize)
          libraryCommander.createFullLibraryInfo(request.userIdOpt, false, library, idealSize).map { libInfo =>
            val memOpt = libraryCommander.getMaybeMembership(request.userIdOpt, library.id.get)
            val accessStr = memOpt.map(_.access.value).getOrElse("none")
            Ok(Json.obj("library" -> Json.toJson(libInfo), "membership" -> accessStr))
          }
        })
      case Left(fail) =>
        Future.successful(Status(fail.status)(Json.obj("error" -> fail.message)))
    }
  }

  // this endpoint gives you a list of libraries you can keep to
  // with information about active keeps (in those libraries) with the same url
  // this endpoint also strips out hashtags in keep notes
  def getLibrarySummariesWithUrl = UserAction(parse.tolerantJson) { request =>
    val urlOpt = (request.body \ "url").asOpt[String]
    Ok(getWritableLibrariesForUrl(request.userId, urlOpt, true))
  }

  // this endpoint gives you a list of libraries you can keep to
  // with information about active keeps (in those libraries) with the same url
  // this endpoint gives keep notes as is from the database
  def getLibrarySummariesWithUrl2 = UserAction(parse.tolerantJson) { request =>
    val urlOpt = (request.body \ "url").asOpt[String]
    Ok(getWritableLibrariesForUrl(request.userId, urlOpt, false))
  }

  private def getWritableLibrariesForUrl(userId: Id[User], urlOpt: Option[String], parseNote: Boolean): JsObject = {
    val parseUrl = urlOpt.map { url =>
      pageCommander.getUrlInfo(url, userId)
    }
    val (librariesWithMemberships, _) = libraryCommander.getLibrariesByUser(userId)
    val writeableLibraries = librariesWithMemberships.filter {
      case (membership, _) =>
        membership.canWrite
    }
    val libraryImages = libraryImageCommander.getBestImageForLibraries(writeableLibraries.map(_._2.id.get).toSet, MobileLibraryController.defaultLibraryImageSize)
    val writeableLibraryInfos = writeableLibraries.map {
      case (mem, library) =>
        val owner = db.readOnlyMaster { implicit s =>
          basicUserRepo.load(library.ownerId)
        }
        val libImage = libraryImages.get(library.id.get)
        val info = LibraryInfo.fromLibraryAndOwner(lib = library, image = libImage, owner = owner)
        var memInfo = Json.obj("access" -> mem.access)
        if (mem.lastViewed.nonEmpty) {
          memInfo = memInfo ++ Json.obj("lastViewed" -> mem.lastViewed)
        }
        Json.toJson(info).as[JsObject] ++ memInfo
    }

    val libsResponse = Json.obj("libraries" -> writeableLibraryInfos)
    val keepResponse = parseUrl.collect {
      case Left(error) =>
        Json.obj("error" -> error)
      case Right(keepDataList) if keepDataList.nonEmpty =>
        val completeKeepData = db.readOnlyMaster { implicit s =>
          val allKeepExtIds = keepDataList.map(_.id).toSet
          val keepMap = keepRepo.getByExtIds(allKeepExtIds)

          keepDataList.map { keepData =>
            keepMap(keepData.id) match {
              case Some(keep) =>
                val keepNoteToShow = if (parseNote) {
                  keep.note.map { note =>
                    val noteWithoutHashtags = Hashtags.removeAllHashtagsFromString(note)
                    keepDecorator.unescapeMarkupNotes(noteWithoutHashtags).trim
                  }
                } else {
                  keep.note
                }
                val keepImageUrl = keepImageCommander.getBestImageForKeep(keep.id.get, ScaleImageRequest(MobileLibraryController.defaultKeepImageSize)).flatten.map(keepImageCommander.getUrl)
                val keepObj = Json.obj("id" -> keep.externalId, "title" -> keep.title, "note" -> keepNoteToShow, "imageUrl" -> keepImageUrl, "hashtags" -> Json.toJson(collectionRepo.getHashtagsByKeepId(keep.id.get)))
                Json.obj("keep" -> keepObj) ++ Json.toJson(keepData).as[JsObject] - ("id")

              case _ => Json.obj()
            }
          }
        }
        Json.obj("alreadyKept" -> completeKeepData)
    }.getOrElse(Json.obj())
    libsResponse ++ keepResponse
  }

  def getLibrarySummariesByUser = UserAction { request =>
    val (librariesWithMemberships, librariesWithInvites) = libraryCommander.getLibrariesByUser(request.userId)
    // rule out invites that are not duplicate invites to same library (only show library invite with highest access)
    val invitesToShow = librariesWithInvites.groupBy(x => x._2).map { lib =>
      val invites = lib._2.unzip._1
      val highestInvite = invites.sorted.last
      (highestInvite, lib._1)
    }.toSeq

    val libImages = libraryImageCommander.getBestImageForLibraries((librariesWithMemberships.map(_._2.id.get) ++ librariesWithInvites.map(_._2.id.get)).toSet, MobileLibraryController.defaultLibraryImageSize)

    val libsFollowing = for ((mem, library) <- librariesWithMemberships) yield {
      val owner = db.readOnlyMaster { implicit s =>
        basicUserRepo.load(library.ownerId)
      }
      val libImage = libImages.get(library.id.get)
      val info = LibraryInfo.fromLibraryAndOwner(lib = library, image = libImage, owner = owner)
      val memInfo = if (mem.lastViewed.nonEmpty) Json.obj("access" -> mem.access, "lastViewed" -> mem.lastViewed) else Json.obj("access" -> mem.access)
      Json.toJson(info).as[JsObject] ++ memInfo
    }
    val libsInvitedTo = for (invitePair <- invitesToShow) yield {
      val invite = invitePair._1
      val lib = invitePair._2
      val (libOwner, inviter) = db.readOnlyMaster { implicit s =>
        (basicUserRepo.load(lib.ownerId),
          basicUserRepo.load(invite.inviterId))
      }
      val libImage = libImages.get(lib.id.get)
      val info = LibraryInfo.fromLibraryAndOwner(lib = lib, image = libImage, owner = libOwner, inviter = Some(inviter))
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
            val owner = db.readOnlyMaster { implicit s => basicUserRepo.load(lib.ownerId) }
            Ok(Json.toJson(LibraryInfo.fromLibraryAndOwner(lib, None, owner)))
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
          keepInfos <- keepDecorator.decorateKeepsIntoKeepInfos(request.userIdOpt, false, keeps, idealImageSize, withKeepTime = true)
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
    val tagNames = (jsonBody \ "hashtags").as[Seq[String]]
    val imageUrlOpt = (jsonBody \ "imageUrl").asOpt[String]
    val note = (jsonBody \ "note").asOpt[String]
    val rawKeep = RawBookmarkRepresentation(title, url, None, keptAt = Some(clock.now), note = note)
    val source = KeepSource.mobile
    implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, source).build
    keepsCommander.keepWithSelectedTags(request.userId, rawKeep, libraryId, source, tagNames, SocialShare(jsonBody)) match {
      case Left(msg) =>
        Future.successful(BadRequest(msg))
      case Right((keep, tags)) =>
        val returnObj = Json.obj(
          "keep" -> Json.toJson(KeepInfo.fromKeep(keep)),
          "hashtags" -> tags.map(_.name)
        )
        imageUrlOpt.map { imageUrl =>
          keepImageCommander.setKeepImageFromUrl(imageUrl, keep.id.get, ImageSource.UserPicked)
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

object MobileLibraryController {
  val defaultKeepImageSize = ImageSize(1024, 1024)
  val defaultLibraryImageSize = ImageSize(1024, 1024)
}

private object ImplicitHelper {
  implicit class PutUserIdOptInMaybeAuthReq(val request: MaybeUserRequest[_]) extends AnyVal {
    def userIdOpt: Option[Id[User]] = request match {
      case u: UserRequest[_] => Some(u.userId)
      case _ => None
    }
  }
}
