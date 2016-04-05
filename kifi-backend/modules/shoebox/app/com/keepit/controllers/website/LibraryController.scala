package com.keepit.controllers.website

import com.google.inject.Inject
import com.keepit.commanders.LibraryQuery.Arrangement
import com.keepit.commanders.{ RawBookmarkRepresentation, _ }
import com.keepit.common.akka.SafeFuture
import com.keepit.common.controller.{ UserRequest, _ }
import com.keepit.common.core._
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.slick.Database
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.json
import com.keepit.common.json.TupleFormat
import com.keepit.common.logging.Logging
import com.keepit.common.mail.EmailAddress
import com.keepit.common.net.URI
import com.keepit.common.path.Path
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.store.ImageSize
import com.keepit.common.time.Clock
import com.keepit.common.util.Paginator
import com.keepit.controllers.ext.ExtLibraryController
import com.keepit.heimdal.HeimdalContextBuilderFactory
import com.keepit.inject.FortyTwoConfig
import com.keepit.model.ExternalLibrarySpace.{ ExternalOrganizationSpace, ExternalUserSpace }
import com.keepit.model._
import com.keepit.shoebox.controllers.LibraryAccessActions
import com.keepit.shoebox.data.keep.{ PartialKeepInfo, KeepInfo }
import com.keepit.social.BasicUser
import org.joda.time.DateTime
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json._
import play.api.mvc.Action

import scala.collection.parallel.ParSeq
import scala.concurrent.Future
import scala.util.{ Failure, Success, Try }

class LibraryController @Inject() (
  db: Database,
  libraryRepo: LibraryRepo,
  libraryMembershipRepo: LibraryMembershipRepo,
  userRepo: UserRepo,
  keepRepo: KeepRepo,
  orgRepo: OrganizationRepo,
  basicUserRepo: BasicUserRepo,
  libPathCommander: PathCommander,
  keepsCommander: KeepCommander,
  keepDecorator: KeepDecorator,
  heimdalContextBuilder: HeimdalContextBuilderFactory,
  collectionRepo: CollectionRepo,
  clock: Clock,
  relatedLibraryCommander: RelatedLibraryCommander,
  suggestedSearchCommander: LibrarySuggestedSearchCommander,
  airbrake: AirbrakeNotifier,
  keepImageRequestRepo: KeepImageRequestRepo,
  keepImageCommander: KeepImageCommander,
  permissionCommander: PermissionCommander,
  libraryCardCommander: LibraryCardCommander,
  libraryQueryCommander: LibraryQueryCommander,
  val libraryCommander: LibraryCommander,
  val libraryInfoCommander: LibraryInfoCommander,
  val libraryMembershipCommander: LibraryMembershipCommander,
  val libraryAccessCommander: LibraryAccessCommander,
  val libraryInviteCommander: LibraryInviteCommander,
  val userActionsHelper: UserActionsHelper,
  val publicIdConfig: PublicIdConfiguration,
  implicit val config: PublicIdConfiguration)
    extends UserActions with LibraryAccessActions with ShoeboxServiceController with Logging {

  private def getSuggestedSearchesAsJson(libId: Id[Library]): JsValue = {
    val (terms, weights) = suggestedSearchCommander.getTopTermsForLibrary(libId, limit = 10)
    Json.obj("terms" -> terms, "weights" -> weights)
  }

  def addLibrary() = UserAction(parse.tolerantJson) { request =>
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
            space = space,
            orgMemberAccess = externalCreateRequest.orgMemberAccess
          )
        }

        implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.site).build
        libraryCommander.createLibrary(libCreateRequest, request.userId) match {
          case Left(fail) =>
            Status(fail.status)(Json.obj("error" -> fail.message))
          case Right(newLibrary) =>
            val membership = db.readOnlyMaster {
              implicit s =>
                libraryMembershipRepo.getWithLibraryIdAndUserId(newLibrary.id.get, request.userId)
            }
            val libCardInfo = libraryCardCommander.createLibraryCardInfo(newLibrary, BasicUser.fromUser(request.user), viewerOpt = Some(request.userId), withFollowing = false, LibraryController.defaultLibraryImageSize)
            Ok(Json.obj("library" -> Json.toJson(libCardInfo), "listed" -> membership.map(_.listed)))
        }
    }
  }

  def modifyLibrary(pubId: PublicId[Library]) = (UserAction andThen LibraryWriteAction(pubId))(parse.tolerantJson) { request =>
    val id = Library.decodePublicId(pubId).get
    val externalLibraryModifyRequest = request.body.as[ExternalLibraryModifications](ExternalLibraryModifications.reads)

    val libModifyRequest = db.readOnlyReplica { implicit session =>
      val space = externalLibraryModifyRequest.externalSpace map {
        case ExternalUserSpace(extId) => LibrarySpace.fromUserId(userRepo.getByExternalId(extId).id.get)
        case ExternalOrganizationSpace(pubId) => LibrarySpace.fromOrganizationId(Organization.decodePublicId(pubId).get)
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

    implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.site).build
    libraryCommander.modifyLibrary(id, request.userId, libModifyRequest) match {
      case Left(fail) =>
        Status(fail.status)(Json.obj("error" -> fail.message))
      case Right(response) =>
        val lib = response.modifiedLibrary
        val (owner, membership, org) = db.readOnlyMaster { implicit s =>
          val basicUser = basicUserRepo.load(lib.ownerId)
          val membership = libraryMembershipRepo.getWithLibraryIdAndUserId(lib.id.get, request.userId)
          val org = lib.organizationId.map { id => orgRepo.get(id) }
          (basicUser, membership, org)
        }
        val libInfo = Json.toJson(LibraryInfo.fromLibraryAndOwner(lib, None, owner, org))
        Ok(Json.obj("library" -> libInfo, "listed" -> membership.map(_.listed)))
    }
  }

  def removeLibrary(pubId: PublicId[Library]) = (UserAction andThen LibraryWriteAction(pubId)) { request =>
    val id = Library.decodePublicId(pubId).get
    implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.site).build
    libraryCommander.deleteLibrary(id, request.userId) match {
      case Some(fail) => Status(fail.status)(Json.obj("error" -> fail.message))
      case _ => Ok(JsString("success"))
    }
  }

  def getLibraryById(pubId: PublicId[Library], showPublishedLibraries: Boolean, imageSize: Option[String] = None) = (MaybeUserAction andThen LibraryViewAction(pubId)).async { request =>
    val libraryId = Library.decodePublicId(pubId).get
    val idealSize = imageSize.flatMap { s => Try(ImageSize(s)).toOption }.getOrElse(LibraryController.defaultLibraryImageSize)
    implicit val context = heimdalContextBuilder.withRequestInfo(request).build
    libraryInfoCommander.getLibraryById(request.userIdOpt, showPublishedLibraries, libraryId, idealSize, request.userIdOpt, sanitizeUrls = false) map { libInfo =>
      val suggestedSearches = getSuggestedSearchesAsJson(libraryId)
      Ok(Json.obj("library" -> libInfo, "suggestedSearches" -> suggestedSearches))
    }
  }

  def getLibrarySummaryById(pubId: PublicId[Library]) = (MaybeUserAction andThen LibraryViewAction(pubId)) { request =>
    val id = Library.decodePublicId(pubId).get
    val (lib, info) = db.readOnlyReplica { implicit session =>
      val lib = libraryRepo.get(id)
      val owners = Map(lib.ownerId -> basicUserRepo.load(lib.ownerId))
      val info = libraryCardCommander.createLibraryCardInfos(Seq(lib), owners, request.userIdOpt, withFollowing = false, idealSize = ProcessedImageSize.Medium.idealSize).seq.head
      (lib, info)
    }
    val path = libPathCommander.getPathForLibraryUrlEncoded(lib)
    Ok(Json.obj("library" -> (Json.toJson(info).as[JsObject] + ("url" -> JsString(path))))) // TODO: stop adding "url" once web app stops using it
  }

  def getLibraryUpdates(pubId: PublicId[Library], since: DateTime) = (MaybeUserAction andThen LibraryViewAction(pubId)) { request =>
    val id = Library.decodePublicId(pubId).get
    val updates = libraryCommander.getUpdatesToLibrary(id, since)
    Ok(Json.obj("updates" -> Json.toJson(updates)))
  }

  def getLibraryByHandleAndSlug(handle: Handle, slug: LibrarySlug, authTokenOpt: Option[String] = None) = MaybeUserAction.async { request =>
    implicit val context = heimdalContextBuilder.withRequestInfo(request).build
    libraryInfoCommander.getLibraryWithHandleAndSlug(handle, slug, request.userIdOpt) match {
      case Right(library) =>
        LibraryViewAction(Library.publicId(library.id.get)).invokeBlock(request, { _: MaybeUserRequest[_] =>
          val idealSize = LibraryController.defaultLibraryImageSize
          request.userIdOpt foreach { userId => libraryCommander.updateLastView(userId, library.id.get) }
          libraryInfoCommander.createFullLibraryInfo(request.userIdOpt, showPublishedLibraries = true, library, idealSize, authTokenOpt, sanitizeUrls = false).map { libInfo =>
            val suggestedSearches = getSuggestedSearchesAsJson(library.id.get)

            libraryCommander.trackLibraryView(request.userIdOpt, library)
            Ok(Json.obj("library" -> libInfo, "suggestedSearches" -> suggestedSearches))
          }
        })
      case Left(fail) => Future.successful {
        if (fail.status == MOVED_PERMANENTLY) MovedPermanently(fail.message) else Status(fail.status)(Json.obj("error" -> fail.message))
      }
    }
  }

  def getLibrarySummariesByUser = UserAction.async { request =>
    val libInfos: ParSeq[(LibraryCardInfo, Option[MiniLibraryMembership])] = db.readOnlyMaster { implicit session =>
      val libs = libraryRepo.getOwnerLibrariesForSelf(request.userId, Paginator.fromStart(200)) // might want to paginate and/or stop preloading all of these
      libraryCardCommander.createLiteLibraryCardInfos(libs, request.userId)
    }
    val objs = libInfos.map {
      case (info: LibraryCardInfo, memOpt: Option[MiniLibraryMembership]) =>
        val id = Library.decodePublicId(info.id).get
        val lib = db.readOnlyMaster { implicit s => libraryRepo.get(id) }
        val path = libPathCommander.getPathForLibraryUrlEncoded(lib)
        val obj = Json.toJson(info).as[JsObject] + ("url" -> JsString(path)) // TODO: stop adding "url" when web app uses "slug" instead
        if (memOpt.flatMap(_.lastViewed).nonEmpty) {
          obj ++ Json.obj("lastViewed" -> memOpt.flatMap(_.lastViewed).get)
        } else {
          obj
        }
    }
    SafeFuture {
      Ok(Json.obj("libraries" -> objs.seq))
    }
  }

  def getKeepableLibraries(includeOrgLibraries: Boolean) = UserAction { request =>
    val librariesWithMembershipAndCollaborators = libraryInfoCommander.getLibrariesUserCanKeepTo(request.userId, includeOrgLibraries)
    val libraryCardInfos = db.readOnlyMaster(implicit s => libraryCardCommander.createLiteLibraryCardInfos(librariesWithMembershipAndCollaborators.map(_._1), request.userId))

    val objs = libraryCardInfos.map {
      case (libCardInfo, memOpt) =>
        val obj = Json.toJson(libCardInfo).as[JsObject]
        if (memOpt.flatMap(_.lastViewed).nonEmpty) {
          obj + ("lastViewed" -> Json.toJson(memOpt.map(_.lastViewed)))
        } else obj
    }
    Ok(Json.obj("libraries" -> objs.seq))
  }

  def getUserByIdOrEmail(json: JsValue): Either[String, Either[ExternalId[User], EmailAddress]] = {
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
        implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.site).build
        libraryInviteCommander.inviteToLibrary(id, request.userId, validInviteList).map {
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

  def joinLibrary(pubId: PublicId[Library], authToken: Option[String] = None, subscribedOpt: Option[Boolean]) = UserAction { request =>
    Library.decodePublicId(pubId) match {
      case Failure(ex) =>
        BadRequest(Json.obj("error" -> "invalid_id"))
      case Success(libId) =>
        implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.site).build

        libraryMembershipCommander.joinLibrary(request.userId, libId, authToken, subscribedOpt) match {
          case Left(fail) =>
            Status(fail.status)(Json.obj("error" -> fail.message))
          case Right((lib, mem)) =>
            val membershipInfo = db.readOnlyReplica { implicit session => libraryMembershipCommander.createMembershipInfo(mem) }
            Ok(Json.obj("membership" -> membershipInfo))
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
        implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.site).build
        libraryMembershipCommander.leaveLibrary(id, request.userId) match {
          case Left(fail) => Status(fail.status)(Json.obj("error" -> fail.message))
          case Right(_) => Ok(JsString("success"))
        }
    }
  }

  def getKeeps(pubId: PublicId[Library], offset: Int, limit: Int, showPublishedLibraries: Boolean, maxMessagesShown: Int) = (MaybeUserAction andThen LibraryViewAction(pubId)).async { request =>
    if (limit > 30) { Future.successful(BadRequest(Json.obj("error" -> "invalid_limit"))) }
    else Library.decodePublicId(pubId) match {
      case Failure(ex) => Future.successful(BadRequest(Json.obj("error" -> "invalid_id")))
      case Success(libraryId) =>
        val numKeepsF = libraryInfoCommander.getKeepsCount(libraryId)
        val keeps = libraryInfoCommander.getKeeps(libraryId, offset, limit)
        for {
          keepInfos <- keepDecorator.decorateKeepsIntoKeepInfos(request.userIdOpt, showPublishedLibraries, keeps, ProcessedImageSize.Large.idealSize, maxMessagesShown, sanitizeUrls = false)
          numKeeps <- numKeepsF
        } yield {
          Ok(Json.obj("keeps" -> Json.toJson(keepInfos), "numKeeps" -> numKeeps))
        }
    }
  }

  def getLibraryMembers(pubId: PublicId[Library], offset: Int, limit: Int) = (MaybeUserAction andThen LibraryViewAction(pubId)) { request =>
    if (limit > 30) { BadRequest(Json.obj("error" -> "invalid_limit")) }
    else Library.decodePublicId(pubId) match {
      case Failure(ex) => BadRequest(Json.obj("error" -> "invalid_id"))
      case Success(libraryId) =>
        val library = db.readOnlyMaster { implicit s => libraryRepo.get(libraryId) }
        val showInvites = request.userIdOpt.safely.contains(library.ownerId)
        val maybeMembers = libraryInfoCommander.getLibraryMembersAndInvitees(libraryId, offset, limit, fillInWithInvites = showInvites)
        Ok(Json.obj("members" -> maybeMembers))
    }
  }

  def copyKeepsFromCollectionToLibrary(libraryId: PublicId[Library], tag: String) = (UserAction andThen LibraryWriteAction(libraryId)).async { request =>
    val hashtag = Hashtag(tag)
    val id = Library.decodePublicId(libraryId).get
    SafeFuture {
      implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.site).build
      libraryCommander.copyKeepsFromCollectionToLibrary(request.userId, id, hashtag) match {
        case Left(fail) => Status(fail.status)(Json.obj("error" -> fail.message))
        case Right((goodKeeps, badKeeps)) =>
          val errors = badKeeps.map {
            case (keep, error) =>
              Json.obj(
                "keep" -> PartialKeepInfo.fromKeep(keep),
                "error" -> error.message
              )
          }
          if (errors.nonEmpty) {
            Ok(Json.obj("successes" -> 0, "failures" -> errors)) // complete or partial failure
          } else {
            Ok(Json.obj("successes" -> goodKeeps.length))
          }
      }
    }
  }

  def moveKeepsFromCollectionToLibrary(libraryId: PublicId[Library], tag: String) = (UserAction andThen LibraryWriteAction(libraryId)).async { request =>
    val hashtag = Hashtag(tag)
    val id = Library.decodePublicId(libraryId).get
    SafeFuture {
      implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.site).build
      libraryCommander.moveKeepsFromCollectionToLibrary(request.userId, id, hashtag) match {
        case Left(fail) => Status(fail.status)(Json.obj("error" -> fail.message))
        case Right((goodKeeps, badKeeps)) =>
          val errors = badKeeps.map {
            case (keep, error) =>
              Json.obj(
                "keep" -> PartialKeepInfo.fromKeep(keep),
                "error" -> error.message
              )
          }
          val mapLibrary = goodKeeps.groupBy(_.lowestLibraryId).map {
            case (libId, keeps) =>
              val pubId = Library.publicId(libId.get)
              val numKeepsMoved = keeps.length
              Json.obj("library" -> pubId, "numMoved" -> numKeepsMoved)
          }
          if (errors.nonEmpty) {
            Ok(Json.obj("successes" -> mapLibrary, "failures" -> errors)) // complete or partial failure
          } else {
            Ok(Json.obj("successes" -> mapLibrary))
          }
      }
    }
  }

  def copyKeeps = UserAction(parse.tolerantJson) { request =>
    val json = request.body
    val toPubId = (json \ "to").as[PublicId[Library]]
    val targetKeepsExt = (json \ "keeps").as[Set[ExternalId[Keep]]]

    Library.decodePublicId(toPubId) match {
      case Failure(ex) => BadRequest(Json.obj("error" -> "dest_invalid_id"))
      case Success(toId) =>
        implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.site).build
        val targetKeeps = db.readOnlyMaster { implicit s => targetKeepsExt.map(keepRepo.getOpt) }.flatten
        val (goodKeeps, badKeeps) = libraryCommander.copyKeeps(request.userId, toId, targetKeeps, Some(KeepSource.userCopied))
        val errors = badKeeps.map {
          case (keep, error) =>
            Json.obj(
              "keep" -> PartialKeepInfo.fromKeep(keep),
              "error" -> error.message
            )
        }
        val successKeeps = goodKeeps.map(k => Json.obj("id" -> k.externalId, "url" -> k.url))
        if (errors.nonEmpty) {
          Ok(Json.obj("successes" -> successKeeps, "failures" -> errors)) // complete or partial failure
        } else {
          Ok(Json.obj("successes" -> successKeeps))
        }
    }
  }

  def moveKeeps = UserAction(parse.tolerantJson) { request =>
    val json = request.body
    val toPubId = (json \ "to").as[PublicId[Library]]
    val targetKeepsExt = (json \ "keeps").as[Seq[ExternalId[Keep]]]

    Library.decodePublicId(toPubId) match {
      case Failure(ex) => BadRequest(Json.obj("error" -> "dest_invalid_id"))
      case Success(toId) =>
        implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.site).build
        val targetKeeps = db.readOnlyReplica { implicit s => targetKeepsExt.map { keepRepo.getOpt } }.flatten
        val (goodKeeps, badKeeps) = libraryCommander.moveKeeps(request.userId, toId, targetKeeps)
        val errors = badKeeps.map {
          case (keep, error) =>
            Json.obj(
              "keep" -> PartialKeepInfo.fromKeep(keep),
              "error" -> error.message
            )
        }
        val mapLibrary = goodKeeps.groupBy(_.lowestLibraryId).map {
          case (libId, keeps) =>
            val pubId = Library.publicId(libId.get)
            val numKeepsMoved = keeps.length
            Json.obj("library" -> pubId, "numMoved" -> numKeepsMoved)
        }
        if (errors.nonEmpty) {
          Ok(Json.obj("successes" -> mapLibrary, "failures" -> errors)) // complete or partial failure
        } else {
          Ok(Json.obj("successes" -> mapLibrary))
        }
    }
  }

  def addKeeps(pubId: PublicId[Library]) = (UserAction andThen LibraryWriteAction(pubId))(parse.tolerantJson) { request =>
    val libraryId = Library.decodePublicId(pubId).get
    (request.body \ "keeps").asOpt[Seq[RawBookmarkRepresentation]] map { fromJson =>
      val source = KeepSource.site
      implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, source).build

      val existingKeeps = db.readOnlyMaster { implicit s =>
        keepRepo.pageByLibrary(libraryId, 0, Int.MaxValue).map(_.externalId).toSet
      }
      val (keeps, failures) = keepsCommander.keepMultiple(fromJson, libraryId, request.userId, source)
      val (alreadyKept, newKeeps) = keeps.partition(k => existingKeeps.contains(k.id))

      log.info(s"kept ${keeps.size} keeps")
      Ok(Json.obj(
        "keeps" -> newKeeps,
        "failures" -> failures,
        "alreadyKept" -> alreadyKept
      ))
    } getOrElse {
      log.error(s"can't parse object from request ${request.body} for user ${request.user}")
      BadRequest(Json.obj("error" -> "Could not parse object from request body"))
    }
  }

  def removeKeep(pubId: PublicId[Library], extId: ExternalId[Keep]) = (UserAction andThen LibraryWriteAction(pubId)) { request =>
    val libraryId = Library.decodePublicId(pubId).get
    val source = KeepSource.site
    implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, source).build
    keepsCommander.unkeepOneFromLibrary(extId, libraryId, request.userId) match {
      case Left(failMsg) => BadRequest(Json.obj("error" -> failMsg))
      case Right(info) => Ok(Json.obj("unkept" -> info))
    }
  }

  def removeKeeps(pubId: PublicId[Library]) = (UserAction andThen LibraryWriteAction(pubId))(parse.tolerantJson) { request =>
    val libraryId = Library.decodePublicId(pubId).get
    val keepExtIds = (request.body \ "ids").as[Seq[ExternalId[Keep]]]
    val source = KeepSource.site
    implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, source).build
    keepsCommander.unkeepManyFromLibrary(keepExtIds, libraryId, request.userId) match {
      case Left(failMsg) => BadRequest(Json.obj("error" -> failMsg))
      case Right((infos, failures)) => Ok(Json.obj("failures" -> failures, "unkept" -> infos))
    }
  }

  def suggestTags(pubId: PublicId[Library], keepId: ExternalId[Keep], query: Option[String], limit: Int) = (UserAction andThen LibraryWriteAction(pubId)).async { request =>
    keepsCommander.suggestTags(request.userId, Some(keepId), query, limit).imap { tagsAndMatches =>
      implicit val matchesWrites = TupleFormat.tuple2Writes[Int, Int]
      val result = JsArray(tagsAndMatches.map { case (tag, matches) => json.aggressiveMinify(Json.obj("tag" -> tag, "matches" -> matches)) })
      Ok(result)
    }
  }

  def suggestTagsSimple(pubId: PublicId[Library], limit: Int) = (UserAction andThen LibraryWriteAction(pubId)).async { request =>
    keepsCommander.suggestTags(request.userId, None, None, limit).imap { tagsAndMatches =>
      implicit val matchesWrites = TupleFormat.tuple2Writes[Int, Int]
      val result = JsArray(tagsAndMatches.map { case (tag, matches) => json.aggressiveMinify(Json.obj("tag" -> tag, "matches" -> matches)) })
      Ok(result)
    }
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

  def relatedLibraries(pubId: PublicId[Library]) = MaybeUserAction.async { request =>
    val id = Library.decodePublicId(pubId).get
    val userIdOpt = request.userIdOpt
    val t1 = System.currentTimeMillis()
    relatedLibraryCommander.suggestedLibrariesInfo(id, userIdOpt)
      .map {
        case (fullInfos, relatedKinds) =>
          val permissions = db.readOnlyReplica { implicit s => permissionCommander.getLibraryPermissions(id, userIdOpt) }
          val libs = fullInfos.map { info =>
            LibraryCardInfo(
              id = info.id,
              name = info.name,
              description = info.description,
              color = info.color,
              image = info.image,
              slug = info.slug,
              visibility = info.visibility,
              owner = info.owner,
              numKeeps = info.numKeeps,
              numFollowers = info.numFollowers,
              followers = LibraryCardInfo.chooseFollowers(info.followers),
              numCollaborators = info.numCollaborators,
              collaborators = LibraryCardInfo.chooseCollaborators(info.collaborators),
              lastKept = info.lastKept.getOrElse(new DateTime(0)),
              following = None,
              membership = info.membership,
              invite = info.invite,
              permissions = permissions,
              modifiedAt = info.modifiedAt,
              kind = info.kind,
              path = Path(info.path),
              org = info.org,
              orgMemberAccess = info.orgMemberAccess,
              whoCanComment = info.whoCanComment
            )
          }
          val t2 = System.currentTimeMillis()
          statsd.timing("libraryController.relatedLibraries", t2 - t1, 1.0)
          Ok(Json.obj("libs" -> libs, "kinds" -> relatedKinds))
      }
  }

  def marketingSiteSuggestedLibraries() = MaybeUserAction.async {
    libraryCardCommander.getMarketingSiteSuggestedLibraries map { infos => Ok(Json.toJson(infos)) }
  }

  def setSubscribedToUpdates(pubId: PublicId[Library], newSubscribedToUpdate: Boolean) = UserAction { request =>
    val libraryId = Library.decodePublicId(pubId).get
    libraryCommander.updateSubscribedToLibrary(request.userId, libraryId, newSubscribedToUpdate) match {
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
          case Left(fail) => Status(fail.status)(Json.obj("error" -> fail.message))
          case Right(_) => NoContent
        }
    }
  }

  def changeKeepImage(libraryPubId: PublicId[Library], keepExtId: ExternalId[Keep], size: Option[String]) = UserAction.async(parse.tolerantJson) { request =>
    db.readOnlyMaster { implicit session =>
      Library.decodePublicId(libraryPubId).toOption.flatMap(libId => keepRepo.getByExtIdandLibraryId(keepExtId, libId))
    } map { keep =>
      request.body \ "image" match {
        case v if v.isFalsy =>
          keepImageCommander.removeKeepImageForKeep(keep.id.get)
          Future.successful(Ok(Json.obj("image" -> JsNull)))
        case JsString(imageUrl @ URI(scheme, _, _, _, _, _, _)) if scheme.exists(_.startsWith("http")) =>
          val imageRequest = db.readWrite { implicit session =>
            keepImageRequestRepo.save(KeepImageRequest(keepId = keep.id.get, source = ImageSource.UserUpload))
          }
          keepImageCommander.setKeepImageFromUrl(imageUrl, keep.id.get, ImageSource.UserUpload, imageRequest.id) map {
            case fail: ImageStoreFailure =>
              InternalServerError(Json.obj("error" -> fail.reason))
            case _: ImageProcessSuccess =>
              val idealSize = size.flatMap { s => Try(ImageSize(s)).toOption }.getOrElse(ExtLibraryController.defaultImageSize)
              Ok(Json.obj("image" -> keepImageCommander.getBestImageForKeep(keep.id.get, ScaleImageRequest(idealSize)).flatten.map(keepImageCommander.getUrl)))
          }
        case JsString(badUrl) =>
          log.info(s"rejecting image url: $badUrl")
          Future.successful(BadRequest(Json.obj("error" -> "bad_image_url")))
        case _ =>
          Future.successful(BadRequest(Json.obj("error" -> "no_image_url")))
      }
    } getOrElse {
      Future.successful(NotFound(Json.obj("error" -> "keep_not_found")))
    }
  }

  def getLHRLibrariesForUser(userExtId: ExternalId[User], orderingOpt: Option[String], directionOpt: Option[String], offset: Int, limit: Int, windowSize: Option[Int]) = UserAction { request =>
    val arrangement = for {
      ordering <- orderingOpt.flatMap(LibraryOrdering.fromStr)
      direction <- directionOpt.flatMap(SortDirection.fromStr)
    } yield LibraryQuery.Arrangement(ordering, direction)

    val basicLibs = db.readOnlyMaster { implicit s =>
      libraryQueryCommander.getLHRLibrariesForUser(request.userId, arrangement, fromIdOpt = None, Offset(offset), Limit(limit), windowSize)
    }
    Ok(Json.obj("libs" -> basicLibs))
  }

}

object LibraryController {
  val defaultLibraryImageSize = ProcessedImageSize.XLarge.idealSize
}

private object ImplicitHelper {
  implicit class PutUserIdOptInMaybeAuthReq(val request: MaybeUserRequest[_]) extends AnyVal {
    def userIdOpt: Option[Id[User]] = request match {
      case u: UserRequest[_] => Some(u.userId)
      case _ => None
    }
  }
}
