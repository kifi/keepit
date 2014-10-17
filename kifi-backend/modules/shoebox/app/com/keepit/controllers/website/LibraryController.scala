package com.keepit.controllers.website

import com.google.inject.Inject
import com.keepit.commanders._
import com.keepit.common.akka.SafeFuture
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
import com.keepit.common.core._
import com.keepit.common.json
import com.keepit.common.json.TupleFormat

class LibraryController @Inject() (
  db: Database,
  libraryRepo: LibraryRepo,
  libraryMembershipRepo: LibraryMembershipRepo,
  libraryInviteRepo: LibraryInviteRepo,
  userRepo: UserRepo,
  keepRepo: KeepRepo,
  basicUserRepo: BasicUserRepo,
  keepsCommander: KeepsCommander,
  heimdalContextBuilder: HeimdalContextBuilderFactory,
  clock: Clock,
  val libraryCommander: LibraryCommander,
  val userActionsHelper: UserActionsHelper,
  val publicIdConfig: PublicIdConfiguration,
  implicit val config: PublicIdConfiguration)
    extends UserActions with LibraryAccessActions with ShoeboxServiceController {

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

  def copyKeepsFromCollectionToLibrary(libraryId: PublicId[Library], tag: String) = (UserAction andThen LibraryWriteAction(libraryId)).async { request =>
    val hashtag = Hashtag(tag)
    val id = Library.decodePublicId(libraryId).get
    SafeFuture {
      libraryCommander.copyKeepsFromCollectionToLibrary(id, hashtag) match {
        case Left(fail) => BadRequest(Json.obj("error" -> fail.message))
        case Right(success) => Ok(JsString("success"))
      }
    }
  }

  def getLibraryById(pubId: PublicId[Library]) = (MaybeUserAction andThen LibraryViewAction(pubId)).async { request =>
    val id = Library.decodePublicId(pubId).get
    libraryCommander.getLibraryById(request.userIdOpt, id) map {
      case (libInfo, accessStr) => Ok(Json.obj("library" -> Json.toJson(libInfo), "membership" -> accessStr))
    }
  }

  def getLibrarySummaryById(pubId: PublicId[Library]) = (MaybeUserAction andThen LibraryViewAction(pubId)) { request =>
    val id = Library.decodePublicId(pubId).get
    val (libInfo, accessStr) = libraryCommander.getLibrarySummaryById(request.userIdOpt, id)
    Ok(Json.obj("library" -> Json.toJson(libInfo), "membership" -> accessStr))
  }

  def getLibraryByPath(userStr: String, slugStr: String) = MaybeUserAction.async { request =>
    libraryCommander.getLibraryWithUsernameAndSlug(userStr, LibrarySlug(slugStr)) match {
      case Right(library) =>
        LibraryViewAction(Library.publicId(library.id.get)).invokeBlock(request, { _: MaybeUserRequest[_] =>
          request.userIdOpt.map { userId => libraryCommander.updateLastView(userId, library.id.get) }
          libraryCommander.createFullLibraryInfo(request.userIdOpt, library).map { libInfo =>
            val accessStr = request.userIdOpt.map { userId =>
              libraryCommander.getAccessStr(userId, library.id.get)
            }.flatten.getOrElse {
              "none"
            }
            Ok(Json.obj("library" -> Json.toJson(libInfo), "membership" -> accessStr))
          }
        })
      case Left((respCode, msg)) =>
        Future.successful(Status(respCode)(Json.obj("error" -> msg)))
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
      val memInfo = if (mem.lastViewed.nonEmpty) Json.obj("access" -> mem.access, "lastViewed" -> mem.lastViewed) else Json.obj("access" -> mem.access)
      Json.toJson(info).as[JsObject] ++ memInfo
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
        implicit val context = heimdalContextBuilder.withRequestInfo(request).build
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
        implicit val context = heimdalContextBuilder.withRequestInfo(request).build
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

  def getKeeps(pubId: PublicId[Library], offset: Int, count: Int) = (MaybeUserAction andThen LibraryViewAction(pubId)).async { request =>
    val idTry = Library.decodePublicId(pubId)
    idTry match {
      case Failure(ex) =>
        Future.successful(BadRequest(Json.obj("error" -> "invalid_id")))
      case Success(libraryId) =>
        val limit = Math.min(count, 30)
        val numKeepsF = libraryCommander.getKeepsCount(libraryId)
        for {
          keeps <- libraryCommander.getKeeps(libraryId, offset, limit)
          keepInfos <- keepsCommander.decorateKeepsIntoKeepInfos(request.userIdOpt, keeps)
          numKeeps <- numKeepsF
        } yield {
          Ok(Json.obj("keeps" -> Json.toJson(keepInfos), "count" -> Math.min(limit, keepInfos.length), "offset" -> offset, "numKeeps" -> numKeeps))
        }
    }
  }

  def getMembers(pubId: PublicId[Library], offset: Int, limit: Int) = (MaybeUserAction andThen LibraryViewAction(pubId)) { request =>
    val libraryId = Library.decodePublicId(pubId).get
    val maybeMembers = libraryCommander.getLibraryMembers(libraryId, offset, limit)
    Ok(Json.toJson(maybeMembers))
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

  def authToLibrary(userStr: String, slug: String, authToken: Option[String]) = MaybeUserAction(parse.tolerantJson) { implicit request =>
    libraryCommander.getLibraryWithUsernameAndSlug(userStr, LibrarySlug(slug)) match {
      case Right(library) if libraryCommander.canViewLibrary(request.userIdOpt, library) =>
        NoContent // Don't need to check anything, they already have access
      case Right(library) =>
        val existingCookieFields = request.session.get("library_access").flatMap(libraryCommander.getLibraryIdAndPassPhraseFromCookie)
        existingCookieFields.flatMap {
          case (cookieLibraryId, cookiePassPhrase) =>
            if (cookieLibraryId == library.id.get && authToken.isDefined) {
              // User has existing cookie auth for this library. Verify it.
              if (libraryCommander.canViewLibrary(request.userIdOpt, library, authToken, Some(cookiePassPhrase))) {
                // existing cookie was good
                Some(NoContent)
              } else {
                None // existing cookie wasn't good, but the new form info may be okay
              }
            } else {
              None
            }
        }.getOrElse {
          // Check request
          val unhashedPassPhraseOpt = (request.body \ "passPhrase").asOpt[String]
          unhashedPassPhraseOpt.map { unhashedPassPhrase =>
            val passPhrase = HashedPassPhrase.generateHashedPhrase(unhashedPassPhrase)
            if (libraryCommander.canViewLibrary(request.userIdOpt, library, authToken, Some(passPhrase))) {
              val cookie = ("library_access", s"${Library.publicId(library.id.get).id}/${passPhrase.value}")
              NoContent.addingToSession(cookie)
            } else {
              BadRequest(Json.obj("error" -> "invalid_access"))
            }
          }.getOrElse(BadRequest(Json.obj("error" -> "no_passphrase_provided")))
        }
      case Left((respCode, msg)) =>
        Status(respCode)(Json.obj("error" -> msg))
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

  def suggestTags(pubId: PublicId[Library], keepId: ExternalId[Keep], query: String, limit: Option[Int]) = (UserAction andThen LibraryWriteAction(pubId)).async { request =>
    val futureTagsAndMatches = if (query.trim.isEmpty) {
      val libraryId = Library.decodePublicId(pubId).get
      val uriId = db.readOnlyMaster { implicit session => keepRepo.get(keepId).uriId }
      keepsCommander.suggestTags(request.userId, libraryId, uriId, limit).map(_.map((_, Seq.empty[(Int, Int)])))
    } else {
      keepsCommander.searchTags(request.userId, query, limit).map(_.map(hit => (hit.tag, hit.matches)))
    }
    futureTagsAndMatches.imap { tagsAndMatches =>
      implicit val matchesWrites = TupleFormat.tuple2Writes[Int, Int]
      val result = JsArray(tagsAndMatches.map { case (tag, matches) => json.minify(Json.obj("tag" -> tag, "matches" -> matches)) })
      Ok(result)
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
