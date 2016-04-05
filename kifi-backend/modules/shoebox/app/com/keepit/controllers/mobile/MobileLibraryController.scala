package com.keepit.controllers.mobile

import com.google.inject.Inject
import com.keepit.commanders._
import com.keepit.common.akka.SafeFuture
import com.keepit.common.controller._
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.json
import com.keepit.common.json.TupleFormat
import com.keepit.common.mail.EmailAddress
import com.keepit.common.net.URI
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.time._
import com.keepit.common.util.Paginator
import com.keepit.controllers.mobile.ImplicitHelper._
import com.keepit.heimdal.{ HeimdalContext, HeimdalContextBuilderFactory }
import com.keepit.inject.FortyTwoConfig
import com.keepit.model.ExternalLibrarySpace.{ ExternalOrganizationSpace, ExternalUserSpace }
import com.keepit.model._
import com.keepit.normalizer.NormalizedURIInterner
import com.keepit.shoebox.controllers.LibraryAccessActions
import com.keepit.shoebox.data.keep.{ PartialKeepInfo, KeepInfo }
import com.keepit.social.BasicUser
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json._
import com.keepit.common.core._
import play.api.mvc.{ Action, AnyContent }

import scala.concurrent.Future
import scala.util.{ Try, Failure, Success }
import com.keepit.common.store.ImageSize

class MobileLibraryController @Inject() (
  db: Database,
  fortyTwoConfig: FortyTwoConfig,
  libraryRepo: LibraryRepo,
  keepRepo: KeepRepo,
  collectionRepo: CollectionRepo,
  userRepo: UserRepo,
  basicUserRepo: BasicUserRepo,
  keepsCommander: KeepCommander,
  pageCommander: PageCommander,
  keepDecorator: KeepDecorator,
  keepImageCommander: KeepImageCommander,
  libraryImageCommander: LibraryImageCommander,
  orgRepo: OrganizationRepo,
  libPathCommander: PathCommander,
  heimdalContextBuilder: HeimdalContextBuilderFactory,
  clock: Clock,
  airbrake: AirbrakeNotifier,
  libraryCardCommander: LibraryCardCommander,
  val libraryInfoCommander: LibraryInfoCommander,
  val libraryAccessCommander: LibraryAccessCommander,
  val libraryCommander: LibraryCommander,
  val libraryMembershipCommander: LibraryMembershipCommander,
  val libraryInviteCommander: LibraryInviteCommander,
  val userActionsHelper: UserActionsHelper,
  val publicIdConfig: PublicIdConfiguration,
  implicit val config: PublicIdConfiguration)
    extends UserActions with LibraryAccessActions with ShoeboxServiceController {

  private def constructLibraryInfo(lib: Library, inviter: Option[BasicUser] = None) = {
    val (owner, org) = db.readOnlyMaster { implicit s => (basicUserRepo.load(lib.ownerId), lib.organizationId.map { id => orgRepo.get(id) }) }
    val libImage = libraryImageCommander.getBestImageForLibrary(lib.id.get, MobileLibraryController.defaultLibraryImageSize).map(_.asInfo)
    LibraryInfo.fromLibraryAndOwner(lib, libImage, owner, org, inviter)
  }

  def createLibrary() = UserAction(parse.tolerantJson) { request =>
    val externalCreateRequestValidated = request.body.validate[ExternalLibraryInitialValues](ExternalLibraryInitialValues.readsMobileV1)

    externalCreateRequestValidated match {
      case JsError(errs) =>
        airbrake.notify(s"Could not json-validate addLibRequest from ${request.userId}: ${request.body}", new JsResultException(errs))
        BadRequest(Json.obj("error" -> "badly_formatted_request"))
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
            space = space,
            orgMemberAccess = externalCreateRequest.orgMemberAccess
          )
        }

        implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.mobile).build
        libraryCommander.createLibrary(libCreateRequest, request.userId) match {
          case Left(fail) =>
            Status(fail.status)(Json.obj("error" -> fail.message))
          case Right(newLibrary) =>
            Ok(Json.toJson(constructLibraryInfo(newLibrary)))
        }
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
    val modifyRequest = LibraryModifications(newName, newSlug, newVisibility, newDescription, newColor, newListed, newWhoCanInvite)
    val res = libraryCommander.modifyLibrary(libId, request.userId, modifyRequest)
    res match {
      case Left(fail) => sendFailResponse(fail)
      case Right(response) => Ok(Json.toJson(constructLibraryInfo(response.modifiedLibrary)))
    }
  }

  def modifyLibraryV2(pubId: PublicId[Library]) = (UserAction andThen LibraryOwnerAction(pubId))(parse.tolerantJson) { request =>
    val id = Library.decodePublicId(pubId).get
    val externalModifyRequestValidated = request.body.validate[ExternalLibraryModifications](ExternalLibraryModifications.readsMobileV1)

    externalModifyRequestValidated match {
      case JsError(errs) =>
        airbrake.notify(s"Could not json-validate modifyLibRequest from ${request.userId}: ${request.body}", new JsResultException(errs))
        BadRequest(Json.obj("error" -> "could_not_parse", "details" -> errs.toString))
      case JsSuccess(externalLibraryModifyRequest, _) =>
        val libModifyRequest = db.readOnlyReplica { implicit session =>
          val space: Option[LibrarySpace] = externalLibraryModifyRequest.externalSpace map {
            case ExternalUserSpace(extId) => LibrarySpace.fromUserId(userRepo.getByExternalId(extId).id.get)
            case ExternalOrganizationSpace(pubOrgId) => LibrarySpace.fromOrganizationId(Organization.decodePublicId(pubOrgId).get)
          }
          LibraryModifications(
            name = externalLibraryModifyRequest.name,
            slug = externalLibraryModifyRequest.slug,
            visibility = externalLibraryModifyRequest.visibility,
            description = externalLibraryModifyRequest.description,
            color = externalLibraryModifyRequest.color,
            listed = externalLibraryModifyRequest.listed,
            whoCanInvite = externalLibraryModifyRequest.whoCanInvite,
            space = space,
            orgMemberAccess = externalLibraryModifyRequest.orgMemberAccess,
            whoCanComment = externalLibraryModifyRequest.whoCanComment
          )
        }

        implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.mobile).build
        libraryCommander.modifyLibrary(id, request.userId, libModifyRequest) match {
          case Left(fail) =>
            Status(fail.status)(Json.obj("error" -> fail.message))
          case Right(response) =>
            Ok(Json.obj("library" -> Json.toJson(constructLibraryInfo(response.modifiedLibrary))))
        }
    }
  }

  def deleteLibrary(pubId: PublicId[Library]) = (UserAction andThen LibraryWriteAction(pubId)) { request =>
    val libId = Library.decodePublicId(pubId).get
    implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.mobile).build
    libraryCommander.deleteLibrary(libId, request.userId) match {
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
    libraryInfoCommander.getLibraryById(userIdOpt, false, libraryId, idealSize, viewerId, sanitizeUrls = true) map { libInfo =>
      val editedLibInfo = libInfo.copy(keeps = libInfo.keeps.map { k =>
        k.copy(note = Hashtags.formatMobileNote(k.note, v1))
      })
      val accessStr = editedLibInfo.membership.map(_.access.value).getOrElse("none")
      Ok(Json.obj("library" -> editedLibInfo, "membership" -> accessStr)) // todo: remove membership once it's not being used
    }
  }

  def getLibraryByPathV1(handle: Handle, slug: LibrarySlug, imageSize: Option[String] = None) = MaybeUserAction.async { request =>
    getLibraryByPath(request, handle, slug, imageSize, true)
  }
  def getLibraryByPathV2(handle: Handle, slug: LibrarySlug, imageSize: Option[String] = None) = MaybeUserAction.async { request =>
    getLibraryByPath(request, handle, slug, imageSize, false)
  }

  private def getLibraryByPath(request: MaybeUserRequest[AnyContent], handle: Handle, slug: LibrarySlug, imageSize: Option[String] = None, v1: Boolean) = {
    implicit val context = heimdalContextBuilder.withRequestInfo(request).build
    libraryInfoCommander.getLibraryWithHandleAndSlug(handle, slug, request.userIdOpt) match {
      case Right(library) =>
        LibraryViewAction(Library.publicId(library.id.get)).invokeBlock(request, { _: MaybeUserRequest[_] =>
          request.userIdOpt.foreach { userId => libraryCommander.updateLastView(userId, library.id.get) }
          val idealSize = imageSize.flatMap { s => Try(ImageSize(s)).toOption }.getOrElse(MobileLibraryController.defaultLibraryImageSize)
          libraryInfoCommander.createFullLibraryInfo(request.userIdOpt, false, library, idealSize, None, sanitizeUrls = true).map { libInfo =>
            val editedLibInfo = libInfo.copy(keeps = libInfo.keeps.map { k =>
              k.copy(note = Hashtags.formatMobileNote(k.note, v1))
            })
            val accessStr = editedLibInfo.membership.map(_.access.value).getOrElse("none")
            libraryCommander.trackLibraryView(request.userIdOpt, library)
            Ok(Json.obj("library" -> editedLibInfo, "membership" -> accessStr)) // todo: remove membership once it's not being used
          }
        })
      case Left(fail) =>
        Future.successful(sendFailResponse(fail))
    }
  }

  def getWriteableLibrariesWithUrlV3 = UserAction(parse.tolerantJson) { request =>
    val urlOpt = (request.body \ "url").asOpt[String]
    getLibrariesUserCanKeepToWithUrl(request.userId, urlOpt)
  }

  private def getLibrariesUserCanKeepToWithUrl(userId: Id[User], urlOpt: Option[String]) = {
    val parseUrl = urlOpt.map { url =>
      pageCommander.getUrlInfo(url, userId)
    }

    val libs = libraryInfoCommander.getLibrariesUserCanKeepTo(userId, includeOrgLibraries = true)
    val libOwnerIds = libs.map(_._1.ownerId).toSet
    val libraryCards = db.readOnlyReplica { implicit session =>
      val libOwners = basicUserRepo.loadAll(libOwnerIds)
      libraryCardCommander.createLibraryCardInfos(libs = libs.map(_._1), owners = libOwners, viewerOpt = Some(userId), withFollowing = true, idealSize = MobileLibraryController.defaultLibraryImageSize)
    }

    val writeableLibraryInfos = libraryCards.map { libraryCard =>
      val access = libraryCard.membership.map(_.access).getOrElse(LibraryAccess.READ_WRITE)
      val memInfo = Json.obj("access" -> access)
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
                Json.obj("id" -> keep.externalId, "title" -> keep.title, "note" -> Hashtags.formatMobileNote(keep.note, false), "imageUrl" -> keepImageUrl, "libraryId" -> keepData.libraryId)
              case _ => Json.obj()
            }
          }
        }
        Json.obj("alreadyKept" -> completeKeepData)
    }.getOrElse(Json.obj())
    Ok(libsResponse ++ keepResponse)
  }

  // Next 3 methods can be removed when everyone's on org mobile clients (Sept 14 2015)

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

    val (librariesWithMemberships, _) = libraryInfoCommander.getLibrariesByUser(userId)
    val writeableLibraries = librariesWithMemberships.filter {
      case (membership, _) =>
        membership.canWrite
    }
    val libOwnerIds = writeableLibraries.map(_._2.ownerId).toSet
    val libraryCards = db.readOnlyReplica { implicit session =>
      val libOwners = basicUserRepo.loadAll(libOwnerIds)
      val libraryCards = libraryCardCommander.createLibraryCardInfos(libs = writeableLibraries.map(_._2), owners = libOwners, viewerOpt = Some(userId), withFollowing = true, idealSize = MobileLibraryController.defaultLibraryImageSize)
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
    val (librariesWithMemberships, librariesWithInvites) = libraryInfoCommander.getLibrariesByUser(request.userId)
    // rule out invites that are not duplicate invites to same library (only show library invite with highest access)
    val invitesToShow = librariesWithInvites.groupBy(x => x._2).map { lib =>
      val invites = lib._2.unzip._1
      val highestInvite = invites.maxBy(_.access)
      (highestInvite, lib._1)
    }.toSeq

    val libImages = libraryImageCommander.getBestImageForLibraries((librariesWithMemberships.map(_._2.id.get) ++ librariesWithInvites.map(_._2.id.get)).toSet, MobileLibraryController.defaultLibraryImageSize)

    val libsFollowing = for ((mem, library) <- librariesWithMemberships) yield {
      val info = constructLibraryInfo(library)
      val memInfo = if (mem.lastViewed.nonEmpty) Json.obj("access" -> mem.access, "lastViewed" -> mem.lastViewed) else Json.obj("access" -> mem.access)
      Json.toJson(info).as[JsObject] ++ memInfo ++ Json.obj("subscribedToUpdates" -> mem.subscribedToUpdates)
    }
    val libsInvitedTo = for (invitePair <- invitesToShow) yield {
      val invite = invitePair._1
      val lib = invitePair._2
      val inviter = db.readOnlyMaster { implicit s => basicUserRepo.load(invite.inviterId) }
      val info = constructLibraryInfo(lib, Some(inviter))
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
            libraryInviteCommander.revokeInvitationToLibrary(libraryId, request.userId, invitee) match {
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
        libraryInviteCommander.inviteToLibrary(id, request.userId, validInviteList).map {
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
        libraryInviteCommander.inviteAnonymousToLibrary(id, request.userId, access, messageOpt) match {
          case Left(fail) => sendFailResponse(fail)
          case Right((invite, library)) =>
            val owner = db.readOnlyMaster { implicit s =>
              basicUserRepo.load(library.ownerId)
            }
            val libraryPath = s"${fortyTwoConfig.applicationBaseUrl}${libPathCommander.getPathForLibrary(library)}"
            val link = libraryPath + "?authToken=" + invite.authToken

            val (subjectText, bodyText) = if (LibraryAccess.collaborativePermissions.contains(access)) {
              (s"""I want to collaborate with you on this Kifi library: "${library.name}"""",
                s"""Please help me build the "${library.name}" Kifi library -
                   |$link
                   |
                   |So we can collaborate together. It's a great way to organize the links that we find.""".stripMargin)
            } else {
              (s"Check out this Kifi library: ${library.name}",
                s"I think you will find this Kifi library interesting: $link")
            }
            Ok(Json.obj(
              "link" -> link,
              "access" -> invite.access.value,
              "sms" -> s"Check out this interesting Kifi library: $link",
              "email" -> Json.obj(
                "subject" -> subjectText,
                "body" -> bodyText
              ),
              "facebook" -> s"Check out this interesting Kifi library: $link",
              "twitter" -> s"Check out this interesting Kifi library: $link",
              "message" -> "" // Ignore!
            ))
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
        val res = libraryMembershipCommander.joinLibrary(request.userId, libId)
        res match {
          case Left(fail) => sendFailResponse(fail)
          case Right((lib, _)) =>
            val (owner, org) = db.readOnlyMaster { implicit s => (basicUserRepo.load(lib.ownerId), lib.organizationId.map(orgRepo.get(_))) }
            Ok(Json.toJson(LibraryInfo.fromLibraryAndOwner(lib, None, owner, org)))
        }
    }
  }

  def joinLibraries() = UserAction(parse.tolerantJson) { request =>
    val libIds = request.body.as[Seq[PublicId[Library]]]
    val results: Seq[(PublicId[Library], Either[LibraryFail, LibraryMembershipInfo])] = libIds.map { pubId =>
      Library.decodePublicId(pubId) match {
        case Failure(ex) => (pubId, Left(LibraryFail(BAD_REQUEST, "invalid_public_id")))
        case Success(libId) =>
          implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.site).build
          libraryMembershipCommander.joinLibrary(request.userId, libId, authToken = None, subscribed = None) match {
            case Left(libFail) => (pubId, Left(libFail))
            case Right((lib, mem)) =>
              val membershipInfo = db.readOnlyReplica { implicit session => libraryMembershipCommander.createMembershipInfo(mem) }
              (pubId, Right(membershipInfo))
          }
      }
    }
    val errorsJson = results.collect { case (id, Left(fail)) => Json.obj("id" -> id.id, "error" -> fail.message) }
    val successesJson = results.collect { case (id, Right(membership)) => Json.obj("id" -> id.id, "membership" -> membership) }

    Ok(Json.obj("errors" -> errorsJson, "successes" -> successesJson))
  }

  def declineLibrary(pubId: PublicId[Library]) = UserAction { request =>
    val idTry = Library.decodePublicId(pubId)
    idTry match {
      case Failure(ex) =>
        BadRequest(Json.obj("error" -> "invalid_id"))
      case Success(libId) =>
        libraryInviteCommander.declineLibrary(request.userId, libId)
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
        libraryMembershipCommander.leaveLibrary(id, request.userId) match {
          case Left(fail) => sendFailResponse(fail)
          case Right(_) => Ok(JsString("success"))
        }
    }
  }

  def getKeepsV1(pubId: PublicId[Library], offset: Int, limit: Int, idealImageWidth: Option[Int], idealImageHeight: Option[Int], maxMessagesShown: Int) = (MaybeUserAction andThen LibraryViewAction(pubId)).async { request =>
    getKeeps(request.userIdOpt, pubId, offset, limit, idealImageWidth, idealImageHeight, maxMessagesShown, true)
  }

  def getKeepsV2(pubId: PublicId[Library], offset: Int, limit: Int, idealImageWidth: Option[Int], idealImageHeight: Option[Int], maxMessagesShown: Int) = (MaybeUserAction andThen LibraryViewAction(pubId)).async { request =>
    getKeeps(request.userIdOpt, pubId, offset, limit, idealImageWidth, idealImageHeight, maxMessagesShown, false)
  }

  private def getKeeps(userIdOpt: Option[Id[User]], pubId: PublicId[Library], offset: Int, limit: Int, idealImageWidth: Option[Int], idealImageHeight: Option[Int], maxMessagesShown: Int, v1: Boolean) = {
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
        val keeps = libraryInfoCommander.getKeeps(libraryId, offset, limit)
        keepDecorator.decorateKeepsIntoKeepInfos(userIdOpt, false, keeps, idealImageSize, maxMessagesShown, sanitizeUrls = true).map { keepInfos =>
          val editedKeepInfos = keepInfos.map { kInfo =>
            kInfo.copy(note = Hashtags.formatMobileNote(kInfo.note, v1))
          }
          Ok(Json.obj("keeps" -> editedKeepInfos))
        }
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
    val (keep, _) = keepsCommander.keepOne(rawKeep, request.userId, libraryId, source, SocialShare(jsonBody))
    imageUrlOpt.map { imageUrl =>
      keepImageCommander.setKeepImageFromUrl(imageUrl, keep.id.get, ImageSource.UserPicked)
    }
    Ok(Json.obj(
      "keep" -> Json.toJson(PartialKeepInfo.fromKeep(keep))
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
        val showInvites = userIdOpt.contains(library.ownerId)
        val maybeMembers = libraryInfoCommander.getLibraryMembersAndInvitees(libraryId, recordsToDrop, recordsToTake, fillInWithInvites = showInvites)
        val membersList = if (v1 || offset != 0) {
          maybeMembers
        } else {
          val owner = db.readOnlyMaster { implicit s => basicUserRepo.load(library.ownerId) }
          MaybeLibraryMember(Left(owner), Some(LibraryAccess.OWNER), None) +: maybeMembers
        }
        Ok(Json.obj("members" -> membersList))
    }
  }

  def marketingSiteSuggestedLibraries() = Action.async {
    libraryCardCommander.getMarketingSiteSuggestedLibraries map { infos => Ok(Json.toJson(infos)) }
  }

  def suggestMembers(pubId: PublicId[Library], query: Option[String], limit: Int) = (UserAction andThen LibraryViewAction(pubId)).async { request =>
    request match {
      case req: UserRequest[_] => {
        if (limit > 30) { Future.successful(BadRequest(Json.obj("error" -> "invalid_limit"))) }
        else Library.decodePublicId(pubId) match {
          case Failure(ex) => Future.successful(BadRequest(Json.obj("error" -> "invalid_id")))
          case Success(libraryId) => libraryMembershipCommander.suggestMembers(req.userId, libraryId, query, Some(limit)).map { members => Ok(Json.obj("members" -> members)) }
        }
      }
      case _ => Future.successful(Forbidden)
    }
  }

  def setSubscribedToUpdates(pubId: PublicId[Library], newSubscripedToUpdate: Boolean) = UserAction { request =>
    val libraryId = Library.decodePublicId(pubId).get
    libraryCommander.updateSubscribedToLibrary(request.userId, libraryId, newSubscripedToUpdate) match {
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
            libraryMembershipCommander.updateLibraryMembershipAccess(request.userId, libraryId, targetUser.id.get, None)
          case "read_only" =>
            libraryMembershipCommander.updateLibraryMembershipAccess(request.userId, libraryId, targetUser.id.get, Some(LibraryAccess.READ_ONLY))
          case "read_write" =>
            libraryMembershipCommander.updateLibraryMembershipAccess(request.userId, libraryId, targetUser.id.get, Some(LibraryAccess.READ_WRITE))
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
