package com.keepit.controllers.mobile

import com.google.inject.Inject
import com.keepit.commanders._
import com.keepit.common.akka.SafeFuture
import com.keepit.common.controller._
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.db.slick.Database
import com.keepit.common.json
import com.keepit.common.json.TupleFormat
import com.keepit.common.mail.EmailAddress
import com.keepit.common.net.URI
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.time._
import com.keepit.common.util.Paginator
import com.keepit.controllers.mobile.ImplicitHelper._
import com.keepit.heimdal.{ HeimdalContext, HeimdalContextBuilderFactory }
import com.keepit.model._
import com.keepit.normalizer.NormalizedURIInterner
import com.keepit.shoebox.controllers.LibraryAccessActions
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json._
import com.keepit.common.core._
import play.api.mvc.AnyContent

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
    val whoCanInvite = (jsonBody \ "whoCanInvite").asOpt[LibraryInvitePermissions]
    val slug = LibrarySlug.generateFromName(name)
    val addRequest = LibraryAddRequest(name = name, visibility = visibility, description = description, slug = slug, color = color, whoCanInvite = whoCanInvite)

    implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.mobile).build
    libraryCommander.addLibrary(addRequest, request.userId) match {
      case Left(fail) => sendFailResponse(fail)
      case Right(lib) =>
        val owner = db.readOnlyMaster { implicit s => basicUserRepo.load(lib.ownerId) }
        val libImage = libraryImageCommander.getBestImageForLibrary(lib.id.get, MobileLibraryController.defaultLibraryImageSize)
        Ok(Json.toJson(LibraryInfo.fromLibraryAndOwner(lib, libImage, owner)))
    }
  }

  def modifyLibrary(pubId: PublicId[Library]) = (UserAction andThen LibraryOwnerAction(pubId))(parse.tolerantJson) { request =>
    val libId = Library.decodePublicId(pubId).get
    val json = request.body
    val newName = (json \ "newName").asOpt[String]
    val newDescription = (json \ "newDescription").asOpt[String]
    val newVisibility = (json \ "newVisibility").asOpt[LibraryVisibility]
    val newSlug = (json \ "newSlug").asOpt[String]
    val newColor = (json \ "newColor").asOpt[LibraryColor]
    val newListed = (json \ "newListed").asOpt[Boolean]
    val newWhoCanInvite = (json \ "newWhoCanInvite").asOpt[LibraryInvitePermissions]

    implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.mobile).build
    val modifyRequest = LibraryModifyRequest(newName, newSlug, newVisibility, newDescription, newColor, newListed, newWhoCanInvite)
    val res = libraryCommander.modifyLibrary(libId, request.userId, modifyRequest)
    res match {
      case Left(fail) => sendFailResponse(fail)
      case Right(lib) =>
        val owner = db.readOnlyMaster { implicit s => basicUserRepo.load(lib.ownerId) }
        val libImage = libraryImageCommander.getBestImageForLibrary(lib.id.get, MobileLibraryController.defaultLibraryImageSize)
        Ok(Json.toJson(LibraryInfo.fromLibraryAndOwner(lib, libImage, owner)))
    }
  }

  def deleteLibrary(pubId: PublicId[Library]) = (UserAction andThen LibraryOwnerAction(pubId)) { request =>
    val libId = Library.decodePublicId(pubId).get
    implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.mobile).build
    libraryCommander.removeLibrary(libId, request.userId) match {
      case Some(fail) => sendFailResponse(fail)
      case _ => NoContent
    }
  }

  def getLibraryByIdV1(pubId: PublicId[Library], imageSize: Option[String] = None) = (MaybeUserAction andThen LibraryViewAction(pubId)).async { request =>
    implicit val context = heimdalContextBuilder.withRequestInfo(request).build
    getLibraryById(request.userIdOpt, pubId, imageSize, true, request.userIdOpt)
  }

  def getLibraryByIdV2(pubId: PublicId[Library], imageSize: Option[String] = None) = (MaybeUserAction andThen LibraryViewAction(pubId)).async { request =>
    implicit val context = heimdalContextBuilder.withRequestInfo(request).build
    getLibraryById(request.userIdOpt, pubId, imageSize, false, request.userIdOpt)
  }

  private def getLibraryById(userIdOpt: Option[Id[User]], pubId: PublicId[Library], imageSize: Option[String] = None, v1: Boolean, viewerId: Option[Id[User]])(implicit context: HeimdalContext) = {
    val libraryId = Library.decodePublicId(pubId).get
    val idealSize = imageSize.flatMap { s => Try(ImageSize(s)).toOption }.getOrElse(MobileLibraryController.defaultLibraryImageSize)
    libraryCommander.getLibraryById(userIdOpt, false, libraryId, idealSize, viewerId) map { libInfo =>
      val editedLibInfo = libInfo.copy(keeps = libInfo.keeps.map { k =>
        k.copy(note = Hashtags.formatMobileNote(k.note, v1))
      })

      val (membershipOpt, inviteOpt) = libraryCommander.createViewerInfo(userIdOpt, libraryId)
      val accessStr = membershipOpt.map(_.access).getOrElse("none")
      val membershipJson = Json.toJson(membershipOpt)
      val inviteJson = Json.toJson(inviteOpt)
      val libraryJson = Json.toJson(editedLibInfo).as[JsObject] + ("membership" -> membershipJson) + ("invite" -> inviteJson)
      Ok(Json.obj("library" -> libraryJson, "membership" -> accessStr)) // todo: remove membership once it's not being used
    }
  }

  def getLibraryByPathV1(userStr: String, slugStr: String, imageSize: Option[String] = None) = MaybeUserAction.async { request =>
    getLibraryByPath(request, userStr, slugStr, imageSize, true)
  }
  def getLibraryByPathV2(userStr: String, slugStr: String, imageSize: Option[String] = None) = MaybeUserAction.async { request =>
    getLibraryByPath(request, userStr, slugStr, imageSize, false)
  }

  private def getLibraryByPath(request: MaybeUserRequest[AnyContent], userStr: String, slugStr: String, imageSize: Option[String] = None, v1: Boolean) = {
    implicit val context = heimdalContextBuilder.withRequestInfo(request).build
    libraryCommander.getLibraryWithUsernameAndSlug(userStr, LibrarySlug(slugStr), request.userIdOpt) match {
      case Right(library) =>
        LibraryViewAction(Library.publicId(library.id.get)).invokeBlock(request, { _: MaybeUserRequest[_] =>
          request.userIdOpt.foreach { userId => libraryCommander.updateLastView(userId, library.id.get) }
          val idealSize = imageSize.flatMap { s => Try(ImageSize(s)).toOption }.getOrElse(MobileLibraryController.defaultLibraryImageSize)
          libraryCommander.createFullLibraryInfo(request.userIdOpt, false, library, idealSize).map { libInfo =>
            val editedLibInfo = libInfo.copy(keeps = libInfo.keeps.map { k =>
              k.copy(note = Hashtags.formatMobileNote(k.note, v1))
            })
            val (membershipOpt, inviteOpt) = libraryCommander.createViewerInfo(request.userIdOpt, library.id.get)
            val accessStr = membershipOpt.map(_.access).getOrElse("none")
            val membershipJson = Json.toJson(membershipOpt)
            val inviteJson = Json.toJson(inviteOpt)
            val libraryJson = Json.toJson(editedLibInfo).as[JsObject] + ("membership" -> membershipJson) + ("invite" -> inviteJson)
            Ok(Json.obj("library" -> libraryJson, "membership" -> accessStr)) // todo: remove membership once it's not being used
          }
        })
      case Left(fail) =>
        Future.successful(sendFailResponse(fail))
    }
  }

  def getWriteableLibrariesWithUrlV1 = UserAction(parse.tolerantJson) { request =>
    val urlOpt = (request.body \ "url").asOpt[String]
    getWriteableLibrariesWithUrl(request.userId, urlOpt, true)
  }
  def getWriteableLibrariesWithUrlV2 = UserAction(parse.tolerantJson) { request =>
    val urlOpt = (request.body \ "url").asOpt[String]
    getWriteableLibrariesWithUrl(request.userId, urlOpt, false)
  }

  private def getWriteableLibrariesWithUrl(userId: Id[User], urlOpt: Option[String], v1: Boolean) = {
    val parseUrl = urlOpt.map { url =>
      pageCommander.getUrlInfo(url, userId)
    }

    val (librariesWithMemberships, _) = libraryCommander.getLibrariesByUser(userId)
    val writeableLibraries = librariesWithMemberships.filter {
      case (membership, _) =>
        membership.canWrite
    }
    val libOwnerIds = writeableLibraries.map(_._2.ownerId).toSet
    val libraryCards = db.readOnlyReplica { implicit session =>
      val user = userRepo.get(userId)
      val libOwners = basicUserRepo.loadAll(libOwnerIds)
      val libraryCards = libraryCommander.createLibraryCardInfos(libs = writeableLibraries.map(_._2), owners = libOwners, viewerOpt = Some(user), withFollowing = true, idealSize = MobileLibraryController.defaultLibraryImageSize)
      libraryCards
    }

    // Kind of weird, but library cards don't have membership information.
    val wbi = writeableLibraries.map(m => m._1.libraryId -> m._1).toMap
    val writeableLibraryInfos = libraryCards.map { libraryCard =>
      val mem = wbi(Library.decodePublicId(libraryCard.id).get)

      var memInfo = Json.obj("access" -> mem.access)
      if (mem.lastViewed.nonEmpty) {
        memInfo = memInfo ++ Json.obj("lastViewed" -> mem.lastViewed)
      }
      // Backwards compatibility for old iOS. Check with Jeremy/Tommy before removing.
      val shortDesc = Json.obj("shortDescription" -> libraryCard.description.getOrElse("").take(100))
      Json.toJson(libraryCard).as[JsObject] ++ memInfo ++ shortDesc
    }.toList

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
                val keepImageUrl = keepImageCommander.getBestImageForKeep(keep.id.get, ScaleImageRequest(MobileLibraryController.defaultKeepImageSize)).flatten.map(keepImageCommander.getUrl)
                if (v1) {
                  val keepObj = Json.obj("id" -> keep.externalId, "title" -> keep.title, "note" -> Hashtags.formatMobileNote(keep.note, v1), "imageUrl" -> keepImageUrl, "hashtags" -> Json.toJson(collectionRepo.getHashtagsByKeepId(keep.id.get)))
                  Json.obj("keep" -> keepObj) ++ Json.toJson(keepData).as[JsObject] - ("id")
                } else {
                  Json.obj("id" -> keep.externalId, "title" -> keep.title, "note" -> Hashtags.formatMobileNote(keep.note, v1), "imageUrl" -> keepImageUrl, "libraryId" -> keepData.libraryId)
                }

              case _ => Json.obj()
            }
          }
        }
        Json.obj("alreadyKept" -> completeKeepData)
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

    val libImages = libraryImageCommander.getBestImageForLibraries((librariesWithMemberships.map(_._2.id.get) ++ librariesWithInvites.map(_._2.id.get)).toSet, MobileLibraryController.defaultLibraryImageSize)

    val libsFollowing = for ((mem, library) <- librariesWithMemberships) yield {
      val owner = db.readOnlyMaster { implicit s =>
        basicUserRepo.load(library.ownerId)
      }
      val libImage = libImages.get(library.id.get)
      val info = LibraryInfo.fromLibraryAndOwner(lib = library, image = libImage, owner = owner)
      val memInfo = if (mem.lastViewed.nonEmpty) Json.obj("access" -> mem.access, "lastViewed" -> mem.lastViewed) else Json.obj("access" -> mem.access)
      Json.toJson(info).as[JsObject] ++ memInfo ++ Json.obj("subscribedToUpdates" -> mem.subscribedToUpdates)
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

  def getUserByIdOrEmail(json: JsValue) = {
    (json \ "type").as[String] match {
      case "user" => Right(Left((json \ "invitee").as[ExternalId[User]]))
      case "email" => Right(Right((json \ "invitee").as[EmailAddress]))
      case _ => Left("invalid_invitee_type")
    }
  }

  def revokeLibraryInvitation(publicLibraryId: PublicId[Library]) = UserAction.async(parse.tolerantJson) { request =>
    Library.decodePublicId(publicLibraryId) match {
      case Failure(ex) => Future.successful(BadRequest(Json.obj("error" -> "invalid_library_id")))
      case Success(libraryId) =>
        getUserByIdOrEmail(request.body) match {
          case Right(invitee) =>
            libraryCommander.revokeInvitationToLibrary(libraryId, request.userId, invitee) match {
              case Right(ok) => Future.successful(NoContent)
              case Left(error) => Future.successful(BadRequest(Json.obj(error._1 -> error._2)))
            }
          case Left(error) => Future.successful(BadRequest(Json.obj("error" -> error)))
        }
    }
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
          case Left(fail) => sendFailResponse(fail)
          case Right(inviteesWithAccess) =>
            val result = inviteesWithAccess.map {
              case (Left(user), access) => Json.obj("user" -> user.externalId, "access" -> access)
              case (Right(contact), access) => Json.obj("email" -> contact.email, "access" -> access)
            }
            Ok(Json.toJson(result))
        }
    }
  }
  def createAnonymousInviteToLibrary(pubId: PublicId[Library]) = UserAction(parse.tolerantJson) { request =>
    Library.decodePublicId(pubId) match {
      case Failure(ex) =>
        BadRequest(Json.obj("error" -> "invalid_id"))
      case Success(id) =>
        val access = (request.body \ "access").as[LibraryAccess]
        val msgOpt = (request.body \ "message").asOpt[String]
        val messageOpt = msgOpt.filter(_.nonEmpty)

        implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.mobile).build
        libraryCommander.inviteAnonymousToLibrary(id, request.userId, access, messageOpt) match {
          case Left(fail) => sendFailResponse(fail)
          case Right((invite, library)) =>
            val owner = db.readOnlyMaster { implicit s =>
              basicUserRepo.load(library.ownerId)
            }
            val libraryPath = Library.formatLibraryPath(owner.username, library.slug)
            val link = if (library.isSecret) {
              libraryPath + "?authToken=" + invite.authToken
            } else {
              libraryPath
            }
            Ok(Json.obj("link" -> link, "access" -> invite.access, "message" -> invite.message))
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
          case Left(fail) => sendFailResponse(fail)
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
          case Left(fail) => sendFailResponse(fail)
          case Right(_) => Ok(JsString("success"))
        }
    }
  }

  def getKeepsV1(pubId: PublicId[Library], offset: Int, limit: Int, idealImageWidth: Option[Int], idealImageHeight: Option[Int]) = (MaybeUserAction andThen LibraryViewAction(pubId)).async { request =>
    getKeeps(request.userIdOpt, pubId, offset, limit, idealImageWidth, idealImageHeight, true)
  }

  def getKeepsV2(pubId: PublicId[Library], offset: Int, limit: Int, idealImageWidth: Option[Int], idealImageHeight: Option[Int]) = (MaybeUserAction andThen LibraryViewAction(pubId)).async { request =>
    getKeeps(request.userIdOpt, pubId, offset, limit, idealImageWidth, idealImageHeight, false)
  }

  private def getKeeps(userIdOpt: Option[Id[User]], pubId: PublicId[Library], offset: Int, limit: Int, idealImageWidth: Option[Int], idealImageHeight: Option[Int], v1: Boolean) = {
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
          keepInfos <- keepDecorator.decorateKeepsIntoKeepInfos(userIdOpt, false, keeps, idealImageSize, withKeepTime = true)
        } yield {
          val editedKeepInfos = keepInfos.map { kInfo =>
            kInfo.copy(note = Hashtags.formatMobileNote(kInfo.note, v1))
          }
          Ok(Json.obj("keeps" -> editedKeepInfos))
        }
    }
  }

  def keepToLibraryV1(pubId: PublicId[Library]) = (UserAction andThen LibraryWriteAction(pubId)).async(parse.tolerantJson) { request =>
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

  def keepToLibraryV2(pubId: PublicId[Library]) = (UserAction andThen LibraryWriteAction(pubId))(parse.tolerantJson) { request =>
    val libraryId = Library.decodePublicId(pubId).get
    val jsonBody = request.body
    val title = (jsonBody \ "title").asOpt[String]
    val url = (jsonBody \ "url").as[String]
    val imageUrlOpt = (jsonBody \ "imageUrl").asOpt[String]
    val note = (jsonBody \ "note").asOpt[String]
    val rawKeep = RawBookmarkRepresentation(title, url, None, keptAt = Some(clock.now), note = note)
    val source = KeepSource.mobile

    implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, source).build
    val (keep, _) = keepsCommander.keepOne(rawKeep, request.userId, libraryId, request.kifiInstallationId, source, SocialShare(jsonBody))
    val hashtagNamesToPersist = Hashtags.findAllHashtagNames(keep.note.getOrElse(""))
    db.readWrite { implicit s =>
      keepsCommander.persistHashtagsForKeepAndSaveKeep(request.userId, keep, hashtagNamesToPersist.toSeq)(s, context)
    }
    imageUrlOpt.map { imageUrl =>
      keepImageCommander.setKeepImageFromUrl(imageUrl, keep.id.get, ImageSource.UserPicked)
    }
    Ok(Json.obj(
      "keep" -> Json.toJson(KeepInfo.fromKeep(keep))
    ))
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

  def getLibraryMembersV1(pubId: PublicId[Library], offset: Int, limit: Int) = (MaybeUserAction andThen LibraryViewAction(pubId)) { request =>
    getLibraryMembers(pubId, offset, limit, request.userIdOpt, true)
  }

  def getLibraryMembersV2(pubId: PublicId[Library], offset: Int, limit: Int) = (MaybeUserAction andThen LibraryViewAction(pubId)) { request =>
    getLibraryMembers(pubId, offset, limit, request.userIdOpt, false)
  }

  private def getLibraryMembers(pubId: PublicId[Library], offset: Int, limit: Int, userIdOpt: Option[Id[User]], v1: Boolean) = {
    if (limit > 30) { BadRequest(Json.obj("error" -> "invalid_limit")) }
    else Library.decodePublicId(pubId) match {
      case Failure(ex) => BadRequest(Json.obj("error" -> "invalid_id"))
      case Success(libraryId) =>
        val recordsToTake = if (!v1 && offset == 0) limit - 1 else limit // If it's the beginning of a v2 call, leave room for the owner
        val recordsToDrop = if (!v1 && offset != 0) offset - 1 else offset
        val library = db.readOnlyMaster { implicit s => libraryRepo.get(libraryId) }
        val showInvites = userIdOpt.exists(_ == library.ownerId)
        val (collaborators, followers, inviteesWithInvites, _) = libraryCommander.getLibraryMembers(libraryId, recordsToDrop, recordsToTake, fillInWithInvites = showInvites)
        val maybeMembers = libraryCommander.buildMaybeLibraryMembers(collaborators, followers, inviteesWithInvites)
        val membersList = if (v1 || offset != 0) {
          maybeMembers
        } else {
          val owner = db.readOnlyMaster { implicit s => basicUserRepo.load(library.ownerId) }
          MaybeLibraryMember(Left(owner), Some(LibraryAccess.OWNER), None) +: maybeMembers
        }
        Ok(Json.obj("members" -> membersList))
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

  def setSubscribedToUpdates(pubId: PublicId[Library], newSubscripedToUpdate: Boolean) = UserAction { request =>
    val libraryId = Library.decodePublicId(pubId).get
    libraryCommander.updatedLibraryUpdateSubscription(request.userId, libraryId, newSubscripedToUpdate) match {
      case Right(mem) => NoContent
      case Left(fail) => Status(fail.status)(Json.obj("error" -> fail.message))
    }
  }

  ///////////////////
  // Collaborators!
  ///////////////////

  def updateLibraryMembership(pubId: PublicId[Library], extUserId: ExternalId[User]) = UserAction(parse.tolerantJson) { request =>
    val libraryId = Library.decodePublicId(pubId).get
    db.readOnlyMaster { implicit s =>
      userRepo.getOpt(extUserId)
    } match {
      case None =>
        NotFound(Json.obj("error" -> "user_id_not_found"))
      case Some(targetUser) =>
        val access = (request.body \ "access").as[String]
        val result = access.toLowerCase match {
          case "none" =>
            libraryCommander.updateLibraryMembershipAccess(request.userId, libraryId, targetUser.id.get, None)
          case "read_only" =>
            libraryCommander.updateLibraryMembershipAccess(request.userId, libraryId, targetUser.id.get, Some(LibraryAccess.READ_ONLY))
          case "read_write" =>
            libraryCommander.updateLibraryMembershipAccess(request.userId, libraryId, targetUser.id.get, Some(LibraryAccess.READ_WRITE))
          case _ =>
            Left(LibraryFail(BAD_REQUEST, "invalid_access_request"))
        }
        result match {
          case Left(fail) => sendFailResponse(fail)
          case Right(_) => NoContent
        }
    }
  }

  private def sendFailResponse(fail: LibraryFail) = Status(fail.status)(Json.obj("error" -> fail.message))

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
