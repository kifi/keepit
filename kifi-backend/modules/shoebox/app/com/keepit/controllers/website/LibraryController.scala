package com.keepit.controllers.website

import com.google.inject.Inject
import com.keepit.commanders._
import com.keepit.common.controller._
import com.keepit.common.crypto.{ PublicIdConfiguration, PublicId }
import com.keepit.common.db.{ Id, ExternalId }
import com.keepit.common.db.slick.Database
import com.keepit.common.mail.EmailAddress
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.time.Clock
import com.keepit.heimdal.HeimdalContextBuilderFactory
import com.keepit.model._
import com.keepit.shoebox.controllers.LibraryAccessActions
import org.joda.time.DateTime
import play.api.libs.json.{ JsObject, JsArray, JsString, Json }
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.mvc.BodyParsers

import scala.concurrent.Future
import scala.util.{ Success, Failure }
import ImplicitHelper._

class LibraryController @Inject() (
  db: Database,
  libraryRepo: LibraryRepo,
  libraryMembershipRepo: LibraryMembershipRepo,
  libraryInviteRepo: LibraryInviteRepo,
  userRepo: UserRepo,
  keepRepo: KeepRepo,
  basicUserRepo: BasicUserRepo,
  keepsCommander: KeepsCommander,
  actionAuthenticator: ActionAuthenticator,
  heimdalContextBuilder: HeimdalContextBuilderFactory,
  clock: Clock,
  val libraryCommander: LibraryCommander,
  val userActionsHelper: UserActionsHelper,
  val publicIdConfig: PublicIdConfiguration,
  implicit val config: PublicIdConfiguration)
    extends WebsiteController(actionAuthenticator) with UserActions with LibraryAccessActions with ShoeboxServiceController {

  def addLibrary() = UserAction.async(parse.tolerantJson) { request =>
    val addRequest = request.body.as[LibraryAddRequest]

    libraryCommander.addLibrary(addRequest, request.userId) match {
      case Left(LibraryFail(message)) =>
        Future.successful(BadRequest(Json.obj("error" -> message)))
      case Right(newLibrary) =>
        libraryCommander.createFullLibraryInfo(Some(request.userId), newLibrary).map { lib =>
          Ok(Json.toJson(lib))
        }
    }
  }

  def modifyLibrary(pubId: PublicId[Library]) = (UserAction andThen LibraryWriteAction(pubId))(parse.tolerantJson) { request =>
    val id = Library.decodePublicId(pubId).get
    val json = request.body
    val newName = (json \ "name").asOpt[String]
    val newDescription = (json \ "description").asOpt[String]
    val newSlug = (json \ "slug").asOpt[String]
    val newVisibility = (json \ "visibility").asOpt[LibraryVisibility]
    val res = libraryCommander.modifyLibrary(id, request.userId, newName, newDescription, newSlug, newVisibility)
    res match {
      case Left(fail) =>
        BadRequest(Json.obj("error" -> fail.message))
      case Right(lib) =>
        val (owner, numKeeps) = db.readOnlyMaster { implicit s => (basicUserRepo.load(lib.ownerId), keepRepo.getCountByLibrary(id)) }
        Ok(Json.toJson(LibraryInfo.fromLibraryAndOwner(lib, owner, numKeeps)))
    }
  }

  def removeLibrary(pubId: PublicId[Library]) = (UserAction andThen LibraryWriteAction(pubId)) { request =>
    val id = Library.decodePublicId(pubId).get
    implicit val context = heimdalContextBuilder.withRequestInfo(request).build
    libraryCommander.removeLibrary(id, request.userId) match {
      case Some((status, message)) => Status(status)(Json.obj("error" -> message))
      case _ => Ok(JsString("success"))
    }
  }

  def copyKeepsFromCollectionToLibrary(libraryId: PublicId[Library], tag: String) = (UserAction andThen LibraryWriteAction(libraryId)) { request =>
    val hashtag = Hashtag(tag)
    val id = Library.decodePublicId(libraryId).get
    libraryCommander.copyKeepsFromCollectionToLibrary(id, hashtag) match {
      case Left(fail) => BadRequest(Json.obj("error" -> fail.message))
      case Right(success) => Ok(JsString("success"))
    }
  }

  def getLibraryById(pubId: PublicId[Library], authToken: Option[String] = None, passPhrase: Option[HashedPassPhrase] = None) = (MaybeUserAction andThen LibraryViewAction(pubId)).async { request =>
    val id = Library.decodePublicId(pubId).get
    val lib = db.readOnlyMaster { implicit s => libraryRepo.get(id) }
    libraryCommander.createFullLibraryInfo(request.userIdOpt, lib).map { library =>
      Ok(Json.obj("library" -> Json.toJson(library)))
    }
  }

  def getLibraryByPath(userStr: String, slugStr: String, authToken: Option[String] = None, passPhrase: Option[HashedPassPhrase] = None) = MaybeUserAction.async { request =>
    // check if str is either a username or externalId
    val ownerOpt = db.readOnlyMaster { implicit s =>
      ExternalId.asOpt[User](userStr) match {
        case Some(eid) => userRepo.getOpt(eid)
        case None => userRepo.getUsername(Username(userStr))
      }
    }
    ownerOpt match {
      case None =>
        Future.successful(BadRequest(Json.obj("error" -> "invalid_username")))
      case Some(owner) =>
        db.readOnlyMaster { implicit s =>
          libraryRepo.getBySlugAndUserId(userId = owner.id.get, slug = LibrarySlug(slugStr))
        } match {
          case None =>
            Future.successful(BadRequest(Json.obj("error" -> "no_library_found")))
          case Some(lib) =>
            if (libraryCommander.canViewLibrary(request.userIdOpt, lib, authToken, passPhrase)) {
              request.userIdOpt.map { userId =>
                db.readWrite { implicit s =>
                  libraryMembershipRepo.getWithLibraryIdAndUserId(lib.id.get, userId).map { mem =>
                    libraryMembershipRepo.save(mem.copy(lastViewed = Some(DateTime.now)))
                  }
                }
              }
              libraryCommander.createFullLibraryInfo(request.userIdOpt, lib).map { libInfo =>
                Ok(Json.obj("library" -> Json.toJson(libInfo)))
              }
            } else {
              Future.successful(BadRequest(Json.obj("error" -> "invalid_access")))
            }
        }
    }
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
      val info = LibraryInfo.fromLibraryAndOwner(library, owner, numKeeps)
      Json.toJson(info).as[JsObject] ++ Json.obj("access" -> mem.access)
    }
    val libsInvitedTo = for (invitePair <- invitesToShow) yield {
      val invite = invitePair._1
      val lib = invitePair._2
      val (inviteOwner, numKeeps) = db.readOnlyMaster { implicit s => (basicUserRepo.load(invite.ownerId), keepRepo.getCountByLibrary(lib.id.get)) }
      val info = LibraryInfo.fromLibraryAndOwner(lib, inviteOwner, numKeeps)
      Json.toJson(info).as[JsObject] ++ Json.obj("access" -> invite.access)
    }
    Ok(Json.obj("libraries" -> libsFollowing, "invited" -> libsInvitedTo))
  }

  def inviteUsersToLibrary(pubId: PublicId[Library]) = UserAction(parse.tolerantJson) { request =>
    val idTry = Library.decodePublicId(pubId)
    idTry match {
      case Failure(ex) =>
        BadRequest(Json.obj("error" -> "invalid_id"))
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

        val res = libraryCommander.inviteUsersToLibrary(id, request.userId, validInviteList)
        res match {
          case Left(fail) =>
            BadRequest(Json.obj("error" -> fail.message))
          case Right(info) =>
            val res = info.map {
              case (Left(externalId), access) => Json.obj("user" -> externalId, "access" -> access)
              case (Right(email), access) => Json.obj("email" -> email, "access" -> access)
            }
            Ok(Json.toJson(res))
        }
    }
  }

  def joinLibrary(pubId: PublicId[Library]) = UserAction { request =>
    val idTry = Library.decodePublicId(pubId)
    idTry match {
      case Failure(ex) =>
        BadRequest(Json.obj("error" -> "invalid_id"))
      case Success(libId) =>

        val res = libraryCommander.joinLibrary(request.userId, libId)
        res match {
          case Left(fail) =>
            BadRequest(Json.obj("error" -> fail.message))
          case Right(lib) =>
            val (owner, numKeeps) = db.readOnlyMaster { implicit s => (basicUserRepo.load(lib.ownerId), keepRepo.getCountByLibrary(libId)) }
            Ok(Json.toJson(LibraryInfo.fromLibraryAndOwner(lib, owner, numKeeps)))
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
        libraryCommander.leaveLibrary(id, request.userId) match {
          case Left(fail) => BadRequest(Json.obj("error" -> fail.message))
          case Right(_) => Ok(JsString("success"))
        }
    }
  }

  def getKeeps(pubId: PublicId[Library], count: Int, offset: Int, authToken: Option[String] = None, passPhrase: Option[HashedPassPhrase] = None) = MaybeUserAction.async { request =>
    val idTry = Library.decodePublicId(pubId)
    idTry match {
      case Failure(ex) =>
        Future.successful(BadRequest(Json.obj("error" -> "invalid_id")))
      case Success(libraryId) =>
        db.readOnlyReplica { implicit session =>
          val lib = libraryRepo.get(libraryId)
          if (libraryCommander.canViewLibrary(request.userIdOpt, lib, authToken, passPhrase)) {
            val take = Math.min(count, 30)
            val numKeeps = keepRepo.getCountByLibrary(libraryId)
            val keeps = keepRepo.getByLibrary(libraryId, take, offset)
            val keepInfosF = keepsCommander.decorateKeepsIntoKeepInfos(request.userIdOpt, keeps)
            keepInfosF.map { keepInfos =>
              Ok(Json.obj("keeps" -> Json.toJson(keepInfos), "count" -> Math.min(take, keepInfos.length), "offset" -> offset, "numKeeps" -> numKeeps))
            }
          } else
            Future.successful(BadRequest(Json.obj("error" -> "invalid_access")))
        }
    }
  }

  def getCollaborators(pubId: PublicId[Library], count: Int, offset: Int, authToken: Option[String] = None, passPhrase: Option[HashedPassPhrase] = None) = MaybeUserAction { request =>
    val idTry = Library.decodePublicId(pubId)
    idTry match {
      case Failure(ex) =>
        BadRequest(Json.obj("error" -> "invalid_id"))
      case Success(libraryId) =>

        db.readOnlyReplica { implicit session =>
          val lib = libraryRepo.get(libraryId)
          if (libraryCommander.canViewLibrary(request.userIdOpt, lib, authToken, passPhrase)) {
            val take = Math.min(count, 10)
            val memberships = libraryMembershipRepo.pageWithLibraryIdAndAccess(libraryId, take, offset, Set(LibraryAccess.READ_WRITE, LibraryAccess.READ_INSERT, LibraryAccess.READ_ONLY))
            val (f, c) = memberships.partition(_.access == LibraryAccess.READ_ONLY)
            val followers = f.map(m => basicUserRepo.load(m.userId))
            val collaborators = c.map(m => basicUserRepo.load(m.userId))

            val numF = libraryMembershipRepo.countWithLibraryIdAndAccess(libraryId, Set(LibraryAccess.READ_ONLY))
            val numC = libraryMembershipRepo.countWithLibraryIdAndAccess(libraryId, Set(LibraryAccess.READ_WRITE, LibraryAccess.READ_INSERT))

            Ok(Json.obj("collaborators" -> Json.toJson(collaborators),
              "followers" -> Json.toJson(followers),
              "numCollaborators" -> numC,
              "numFollowers" -> numF,
              "count" -> take,
              "offset" -> offset))
          } else
            BadRequest(Json.obj("error" -> "invalid_access"))
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
        val targetKeeps = db.readOnlyMaster { implicit s => targetKeepsExt.map(keepRepo.getOpt) }.flatten
        val badKeeps = libraryCommander.copyKeeps(request.userId, toId, targetKeeps)
        val errors = badKeeps.map {
          case (keep, error) =>
            Json.obj(
              "keep" -> KeepInfo.fromKeep(keep),
              "error" -> error.message
            )
        }
        if (errors.nonEmpty) {
          if (errors.length == targetKeepsExt.length) {
            Ok(Json.obj("success" -> false, "failures" -> errors)) // complete failure
          } else {
            Ok(Json.obj("success" -> "partial", "failures" -> errors)) // partial failure
          }
        } else {
          Ok(Json.obj("success" -> true))
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
        val targetKeeps = db.readOnlyReplica { implicit s => targetKeepsExt.map { keepRepo.getOpt } }.flatten
        val badKeeps = libraryCommander.moveKeeps(request.userId, toId, targetKeeps)
        val errors = badKeeps.map {
          case (keep, error) =>
            Json.obj(
              "keep" -> KeepInfo.fromKeep(keep),
              "error" -> error.message
            )
        }
        if (errors.nonEmpty) {
          if (errors.length == targetKeepsExt.length) {
            Ok(Json.obj("success" -> false, "failures" -> errors)) // complete failure
          } else {
            Ok(Json.obj("success" -> "partial", "failures" -> errors)) // partial failure
          }
        } else {
          Ok(Json.obj("success" -> true))
        }
    }
  }

  def addKeeps(pubId: PublicId[Library]) = (UserAction andThen LibraryWriteAction(pubId))(parse.tolerantJson) { request =>
    val libraryId = Library.decodePublicId(pubId).get
    (request.body \ "keeps").asOpt[Seq[RawBookmarkRepresentation]] map { fromJson =>
      val source = KeepSource.site
      implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, source).build

      val existingKeeps = db.readOnlyMaster { implicit s =>
        keepRepo.getByLibrary(libraryId, Int.MaxValue, 0).map(_.externalId).toSet
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

  def authToLibrary(id: PublicId[Library], authToken: Option[String]) = MaybeUserAction(parse.tolerantJson) { implicit request =>
    val passPhrase = (request.body \ "passPhrase").asOpt[String].map(HashedPassPhrase.generateHashedPhrase)
    val libraryIdTry = Library.decodePublicId(id)

    libraryIdTry match {
      case Success(libraryId) =>
        val canView = request match {
          case userRequest: UserRequest[_] =>
            libraryCommander.canViewLibrary(Some(userRequest.userId), libraryId, authToken, passPhrase)
          case other =>
            libraryCommander.canViewLibrary(None, libraryId, authToken, passPhrase)
        }
        if (canView) {
          (authToken, passPhrase) match {
            case (Some(a), Some(p)) =>
              val cookie = ("library_access", s"${id.id}/${p.value}")
              NoContent.addingToSession(cookie)
            case _ =>
              NoContent
          }
        } else {
          Forbidden
        }
      case _ =>
        Forbidden
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
