package com.keepit.controllers.website

import com.google.inject.Inject
import com.keepit.commanders.{ RawBookmarkRepresentation, _ }
import com.keepit.common.akka.SafeFuture
import com.keepit.common.controller.{ UserRequest, _ }
import com.keepit.common.core._
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.slick.Database
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.json
import com.keepit.common.json.TupleFormat
import com.keepit.common.logging.Logging
import com.keepit.common.mail.EmailAddress
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.store.ImageSize
import com.keepit.common.time.Clock
import com.keepit.common.util.Paginator
import com.keepit.heimdal.HeimdalContextBuilderFactory
import com.keepit.inject.FortyTwoConfig
import com.keepit.model._
import com.keepit.shoebox.controllers.LibraryAccessActions

import org.joda.time.DateTime

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json._
import play.api.mvc.Action

import scala.concurrent.Future
import scala.util.{ Try, Failure, Success }

class LibraryController @Inject() (
  db: Database,
  libraryRepo: LibraryRepo,
  libraryMembershipRepo: LibraryMembershipRepo,
  libraryInviteRepo: LibraryInviteRepo,
  userRepo: UserRepo,
  keepRepo: KeepRepo,
  basicUserRepo: BasicUserRepo,
  keepsCommander: KeepsCommander,
  keepDecorator: KeepDecorator,
  userCommander: UserCommander,
  heimdalContextBuilder: HeimdalContextBuilderFactory,
  collectionRepo: CollectionRepo,
  fortyTwoConfig: FortyTwoConfig,
  clock: Clock,
  relatedLibraryCommander: RelatedLibraryCommander,
  suggestedSearchCommander: LibrarySuggestedSearchCommander,
  val libraryCommander: LibraryCommander,
  val libraryInviteCommander: LibraryInviteCommander,
  val userActionsHelper: UserActionsHelper,
  val publicIdConfig: PublicIdConfiguration,
  implicit val config: PublicIdConfiguration)
    extends UserActions with LibraryAccessActions with ShoeboxServiceController with Logging {

  private def getSuggestedSearchesAsJson(libId: Id[Library]): JsValue = {
    val top = suggestedSearchCommander.getSuggestedTermsForLibrary(libId, limit = 10, kind = SuggestedSearchTermKind.AUTO)
    val (terms, weights) = top.terms.toArray.sortBy(-_._2).unzip
    Json.obj("terms" -> terms, "weights" -> weights)
  }

  def addLibrary() = UserAction.async(parse.tolerantJson) { request =>
    val addRequest = request.body.as[LibraryAddRequest]

    implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.site).build
    libraryCommander.addLibrary(addRequest, request.userId) match {
      case Left(fail) =>
        Future.successful(Status(fail.status)(Json.obj("error" -> fail.message)))
      case Right(newLibrary) =>
        val membership = db.readOnlyMaster { implicit s =>
          libraryMembershipRepo.getWithLibraryIdAndUserId(newLibrary.id.get, request.userId)
        }
        libraryCommander.createFullLibraryInfo(Some(request.userId), false, newLibrary, LibraryController.defaultLibraryImageSize).map { lib =>
          Ok(Json.obj("library" -> Json.toJson(lib), "listed" -> membership.map(_.listed)))
        }
    }
  }

  def modifyLibrary(pubId: PublicId[Library]) = (UserAction andThen LibraryOwnerAction(pubId))(parse.tolerantJson) { request =>
    val id = Library.decodePublicId(pubId).get
    val libModifyRequest = request.body.as[LibraryModifyRequest]

    implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.site).build
    libraryCommander.modifyLibrary(id, request.userId, libModifyRequest) match {
      case Left(fail) =>
        Status(fail.status)(Json.obj("error" -> fail.message))
      case Right(lib) =>
        val (owner, membership) = db.readOnlyMaster { implicit s =>
          val basicUser = basicUserRepo.load(lib.ownerId)
          val membership = libraryMembershipRepo.getWithLibraryIdAndUserId(lib.id.get, request.userId)
          (basicUser, membership)
        }
        val libInfo = Json.toJson(LibraryInfo.fromLibraryAndOwner(lib, None, owner))
        Ok(Json.obj("library" -> libInfo, "listed" -> membership.map(_.listed)))
    }
  }

  def removeLibrary(pubId: PublicId[Library]) = (UserAction andThen LibraryOwnerAction(pubId)) { request =>
    val id = Library.decodePublicId(pubId).get
    implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.site).build
    libraryCommander.removeLibrary(id, request.userId) match {
      case Some(fail) => Status(fail.status)(Json.obj("error" -> fail.message))
      case _ => Ok(JsString("success"))
    }
  }

  def getLibraryById(pubId: PublicId[Library], showPublishedLibraries: Boolean, imageSize: Option[String] = None) = (MaybeUserAction andThen LibraryViewAction(pubId)).async { request =>
    val libraryId = Library.decodePublicId(pubId).get
    val idealSize = imageSize.flatMap { s => Try(ImageSize(s)).toOption }.getOrElse(LibraryController.defaultLibraryImageSize)
    implicit val context = heimdalContextBuilder.withRequestInfo(request).build
    libraryCommander.getLibraryById(request.userIdOpt, showPublishedLibraries, libraryId, idealSize, request.userIdOpt) map { libInfo =>
      val suggestedSearches = getSuggestedSearchesAsJson(libraryId)
      val membershipOpt = libraryCommander.getViewerMembershipInfo(request.userIdOpt, libraryId)
      val inviteOpt = libraryInviteCommander.getViewerInviteInfo(request.userIdOpt, libraryId)

      val membershipJson = Json.toJson(membershipOpt)
      val inviteJson = Json.toJson(inviteOpt)
      val libraryJson = Json.toJson(libInfo).as[JsObject] + ("membership" -> membershipJson) + ("invite" -> inviteJson)
      Ok(Json.obj("library" -> libraryJson, "suggestedSearches" -> suggestedSearches))
    }
  }

  def getLibrarySummaryById(pubId: PublicId[Library]) = (MaybeUserAction andThen LibraryViewAction(pubId)) { request =>
    val id = Library.decodePublicId(pubId).get

    val (libInfo, memInfoOpt) = libraryCommander.getLibrarySummaryAndMembership(request.userIdOpt, id)
    val membershipJson = Json.toJson(memInfoOpt)
    val libraryJson = Json.toJson(libInfo).as[JsObject] + ("membership" -> membershipJson)
    Ok(Json.obj("library" -> libraryJson))
  }

  def getLibraryByPath(userStr: String, slugStr: String, showPublishedLibraries: Boolean, imageSize: Option[String] = None, authTokenOpt: Option[String] = None) = MaybeUserAction.async { request =>
    implicit val context = heimdalContextBuilder.withRequestInfo(request).build
    libraryCommander.getLibraryWithUsernameAndSlug(userStr, LibrarySlug(slugStr), request.userIdOpt) match {
      case Right(library) =>
        LibraryViewAction(Library.publicId(library.id.get)).invokeBlock(request, { _: MaybeUserRequest[_] =>
          val idealSize = imageSize.flatMap { s => Try(ImageSize(s)).toOption }.getOrElse(LibraryController.defaultLibraryImageSize)
          request.userIdOpt foreach { userId => libraryCommander.updateLastView(userId, library.id.get) }
          libraryCommander.createFullLibraryInfo(request.userIdOpt, showPublishedLibraries, library, idealSize, showKeepCreateTime = true).map { libInfo =>
            val suggestedSearches = getSuggestedSearchesAsJson(library.id.get)
            val membershipOpt = libraryCommander.getViewerMembershipInfo(request.userIdOpt, library.id.get)
            // if viewer, get invite for that viewer. Otherwise, if viewer unknown, use authToken to find invite info
            val inviteOpt = libraryInviteCommander.getViewerInviteInfo(request.userIdOpt, library.id.get) orElse {
              authTokenOpt.map { authToken =>
                db.readOnlyMaster { implicit s =>
                  libraryInviteRepo.getByLibraryIdAndAuthToken(library.id.get, authToken).headOption.map { invite =>
                    val inviter = basicUserRepo.load(invite.inviterId)
                    (invite, inviter)
                  }
                }.map {
                  case (invite, inviter) =>
                    LibraryInviteInfo.createInfo(invite, inviter)
                }
              }.flatten
            }

            libraryCommander.trackLibraryView(request.userIdOpt, library)
            val membershipJson = Json.toJson(membershipOpt)
            val inviteJson = Json.toJson(inviteOpt)
            val libraryJson = Json.toJson(libInfo).as[JsObject] + ("membership" -> membershipJson) + ("invite" -> inviteJson)
            Ok(Json.obj("library" -> libraryJson, "suggestedSearches" -> suggestedSearches))
          }
        })
      case Left(fail) => Future.successful {
        if (fail.status == MOVED_PERMANENTLY) MovedPermanently(fail.message) else Status(fail.status)(Json.obj("error" -> fail.message))
      }
    }
  }

  def getLibrarySummariesByUser = UserAction.async { request =>
    val (tooManyLibsWithMemberships, _) = libraryCommander.getLibrariesByUser(request.userId) // TODO: stop loading invited libraries

    // TODO: filter out followed libraries at database level
    val libsWithMemberships = tooManyLibsWithMemberships.filter(l => LibraryAccess.collaborativePermissions.contains(l._1.access))

    val basicUsers = db.readOnlyReplica { implicit session =>
      basicUserRepo.loadAll(libsWithMemberships.map(_._2.ownerId).toSet)
    }
    SafeFuture {
      db.readOnlyReplica { implicit session =>
        for ((mem, library) <- libsWithMemberships) yield {
          val owner = basicUsers(library.ownerId)
          (LibraryInfo.fromLibraryAndOwner(library, None, owner), mem) // should have library image, but this endpoint doesn't use it and is already slow & heavy.
        }
      }
    } map { libInfosWithMemberships =>
      Ok(Json.obj("libraries" -> libInfosWithMemberships.map(libInfoToJsonWithLastViewed)))
    }
  }

  @inline private def libInfoToJsonWithLastViewed(pair: (LibraryInfo, LibraryMembership)): JsValue = {
    val lastViewed = pair._2.lastViewed
    if (lastViewed.nonEmpty) {
      Json.toJson(pair._1).as[JsObject] ++ Json.obj("lastViewed" -> lastViewed)
    } else {
      Json.toJson(pair._1)
    }
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
        libraryInviteCommander.inviteUsersToLibrary(id, request.userId, validInviteList).map {
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

  def joinLibrary(pubId: PublicId[Library], authToken: Option[String] = None, subscribed: Boolean = false) = UserAction { request =>
    Library.decodePublicId(pubId) match {
      case Failure(ex) =>
        BadRequest(Json.obj("error" -> "invalid_id"))
      case Success(libId) =>
        implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.site).build
        db.readOnlyMaster { implicit s =>
          libraryMembershipRepo.getWithLibraryIdAndUserId(libId, request.userId)
        } match {
          case None =>
            libraryCommander.joinLibrary(request.userId, libId, authToken, Some(subscribed)) match {
              case Left(fail) =>
                Status(fail.status)(Json.obj("error" -> fail.message))
              case Right((_, mem)) =>
                Ok(Json.obj("membership" -> LibraryMembershipInfo.fromMembership(mem)))
            }
          case Some(mem) =>
            log.info(s"user ${request.userId} is already following library $libId, possible race condition")
            if (mem.subscribedToUpdates != subscribed) {
              libraryCommander.updateSubscribedToLibrary(request.userId, libId, subscribed) match {
                case Left(fail) =>
                  Status(fail.status)(Json.obj("error" -> fail.message))
                case Right(mem) =>
                  Ok(Json.obj("membership" -> LibraryMembershipInfo.fromMembership(mem)))
              }
            } else {
              Ok(Json.obj("membership" -> LibraryMembershipInfo.fromMembership(mem)))
            }
        }
    }
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
        libraryCommander.leaveLibrary(id, request.userId) match {
          case Left(fail) => Status(fail.status)(Json.obj("error" -> fail.message))
          case Right(_) => Ok(JsString("success"))
        }
    }
  }

  def getKeeps(pubId: PublicId[Library], offset: Int, limit: Int, showPublishedLibraries: Boolean) = (MaybeUserAction andThen LibraryViewAction(pubId)).async { request =>
    if (limit > 30) { Future.successful(BadRequest(Json.obj("error" -> "invalid_limit"))) }
    else Library.decodePublicId(pubId) match {
      case Failure(ex) => Future.successful(BadRequest(Json.obj("error" -> "invalid_id")))
      case Success(libraryId) =>
        val numKeepsF = libraryCommander.getKeepsCount(libraryId)
        for {
          keeps <- libraryCommander.getKeeps(libraryId, offset, limit)
          keepInfos <- keepDecorator.decorateKeepsIntoKeepInfos(request.userIdOpt, showPublishedLibraries, keeps, ProcessedImageSize.Large.idealSize, withKeepTime = true)
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
        val showInvites = request.userIdOpt.map(uId => uId == library.ownerId).getOrElse(false)
        val (collaborators, followers, inviteesWithInvites, _) = libraryCommander.getLibraryMembers(libraryId, offset, limit, fillInWithInvites = showInvites)
        val maybeMembers = libraryCommander.buildMaybeLibraryMembers(collaborators, followers, inviteesWithInvites)
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
                "keep" -> KeepInfo.fromKeep(keep),
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
                "keep" -> KeepInfo.fromKeep(keep),
                "error" -> error.message
              )
          }
          val mapLibrary = goodKeeps.groupBy(_.libraryId).map {
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
    val targetKeepsExt = (json \ "keeps").as[Seq[ExternalId[Keep]]]

    Library.decodePublicId(toPubId) match {
      case Failure(ex) => BadRequest(Json.obj("error" -> "dest_invalid_id"))
      case Success(toId) =>
        implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.site).build
        val targetKeeps = db.readOnlyMaster { implicit s => targetKeepsExt.map(keepRepo.getOpt) }.flatten
        val (goodKeeps, badKeeps) = libraryCommander.copyKeeps(request.userId, toId, targetKeeps, Some(KeepSource.userCopied))
        val errors = badKeeps.map {
          case (keep, error) =>
            Json.obj(
              "keep" -> KeepInfo.fromKeep(keep),
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
              "keep" -> KeepInfo.fromKeep(keep),
              "error" -> error.message
            )
        }
        val mapLibrary = goodKeeps.groupBy(_.libraryId).map {
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
        keepRepo.getByLibrary(libraryId, 0, Int.MaxValue).map(_.externalId).toSet
      }
      val (keeps, _, failures, _) = keepsCommander.keepMultiple(fromJson, libraryId, request.userId, source, None, false)
      val (alreadyKept, newKeeps) = keeps.partition(k => existingKeeps.contains(k.id.get))

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

  def authToLibrary(userStr: String, slug: String, authToken: Option[String]) = MaybeUserAction(parse.tolerantJson) { implicit request =>
    implicit val context = heimdalContextBuilder.withRequestInfo(request).build
    libraryCommander.getLibraryWithUsernameAndSlug(userStr, LibrarySlug(slug), request.userIdOpt) match {
      case Right(library) if libraryCommander.canViewLibrary(request.userIdOpt, library) =>
        NoContent // Don't need to check anything, they already have access
      case Right(library) =>
        // Check request
        if (libraryCommander.canViewLibrary(request.userIdOpt, library, authToken)) {
          NoContent
        } else {
          BadRequest(Json.obj("error" -> "invalid_access"))
        }
      case Left(fail) =>
        if (fail.status == MOVED_PERMANENTLY) Redirect(fail.message, authToken.map("authToken" -> Seq(_)).toMap, MOVED_PERMANENTLY) else Status(fail.status)(Json.obj("error" -> fail.message))
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

  def updateKeep(libraryPubId: PublicId[Library], keepExtId: ExternalId[Keep]) = (UserAction andThen LibraryWriteAction(libraryPubId))(parse.tolerantJson) { request =>
    val libraryId = Library.decodePublicId(libraryPubId).get
    val body = request.body
    val title = (body \ "title").asOpt[String]

    implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.site).build
    keepsCommander.updateKeepInLibrary(keepExtId, libraryId, request.userId, title) match {
      case Left((status, code)) => Status(status)(Json.obj("error" -> code))
      case Right(keep) => NoContent
    }
  }

  def editKeepNote(libraryPubId: PublicId[Library], keepExtId: ExternalId[Keep]) = (UserAction andThen LibraryWriteAction(libraryPubId))(parse.tolerantJson) { request =>
    db.readOnlyMaster { implicit s =>
      keepRepo.getOpt(keepExtId)
    } match {
      case None =>
        NotFound(Json.obj("error" -> "keep_id_not_found"))
      case Some(keep) =>
        val body = request.body.as[JsObject]
        val newNote = (body \ "note").as[String]
        implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.site).build
        keepsCommander.updateKeepNote(request.userId, keep, newNote)
        NoContent
    }
  }

  def tagKeep(libraryPubId: PublicId[Library], keepExtId: ExternalId[Keep], tag: String) = (UserAction andThen LibraryWriteAction(libraryPubId)) { request =>
    val libraryId = Library.decodePublicId(libraryPubId).get;

    keepsCommander.getKeep(libraryId, keepExtId, request.userId) match {
      case Right(keep) =>
        implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.keeper).build
        val coll = keepsCommander.getOrCreateTag(request.userId, tag) // TODO: library ID, not user ID
        keepsCommander.addToCollection(coll.id.get, Seq(keep))
        Ok(Json.obj("tag" -> coll.name))
      case Left((status, code)) =>
        Status(status)(Json.obj("error" -> code))
    }
  }

  def untagKeep(libraryPubId: PublicId[Library], keepExtId: ExternalId[Keep], tag: String) = (UserAction andThen LibraryWriteAction(libraryPubId)) { request =>
    val libraryId = Library.decodePublicId(libraryPubId).get
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
  }

  def suggestTags(pubId: PublicId[Library], keepId: ExternalId[Keep], query: Option[String], limit: Int) = (UserAction andThen LibraryWriteAction(pubId)).async { request =>
    keepsCommander.suggestTags(request.userId, Some(keepId), query, limit).imap { tagsAndMatches =>
      implicit val matchesWrites = TupleFormat.tuple2Writes[Int, Int]
      val result = JsArray(tagsAndMatches.map { case (tag, matches) => json.minify(Json.obj("tag" -> tag, "matches" -> matches)) })
      Ok(result)
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

  def relatedLibraries(pubId: PublicId[Library]) = MaybeUserAction.async { request =>
    val id = Library.decodePublicId(pubId).get
    val userIdOpt = request.userIdOpt
    val t1 = System.currentTimeMillis()
    relatedLibraryCommander.suggestedLibrariesInfo(id, userIdOpt)
      .map {
        case (fullInfos, relatedKinds) =>
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
              membership = None,
              caption = None,
              modifiedAt = info.modifiedAt,
              kind = info.kind)
          }
          val t2 = System.currentTimeMillis()
          statsd.timing("libraryController.relatedLibraries", t2 - t1, 1.0)
          Ok(Json.obj("libs" -> libs, "kinds" -> relatedKinds))
      }
  }

  def marketingSiteSuggestedLibraries() = Action.async {
    libraryCommander.getMarketingSiteSuggestedLibraries() map { infos => Ok(Json.toJson(infos)) }
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
            libraryCommander.updateLibraryMembershipAccess(request.userId, libraryId, targetUser.id.get, None)
          case "read_only" =>
            libraryCommander.updateLibraryMembershipAccess(request.userId, libraryId, targetUser.id.get, Some(LibraryAccess.READ_ONLY))
          case "read_write" =>
            libraryCommander.updateLibraryMembershipAccess(request.userId, libraryId, targetUser.id.get, Some(LibraryAccess.READ_WRITE))
          case _ =>
            Left(LibraryFail(BAD_REQUEST, "invalid_access_request"))
        }
        result match {
          case Left(fail) => Status(fail.status)(Json.obj("error" -> fail.message))
          case Right(_) => NoContent
        }
    }
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
