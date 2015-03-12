package com.keepit.controllers.mobile

import com.keepit.common.controller._
import com.keepit.common.db._
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.mail.{ EmailAddress, SystemEmailAddress, ElectronicMail, LocalPostOffice }
import com.keepit.common.store.S3ImageStore
import com.keepit.heimdal.{ DelightedAnswerSources, BasicDelightedAnswer }
import com.keepit.model._
import com.keepit.commanders._
import com.keepit.social.BasicUser

import play.api.libs.json._
import play.api.libs.json.Json.toJson

import com.google.inject.Inject
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.mvc.{ Action, MaxSizeExceeded, Request }
import scala.concurrent.Future
import securesocial.core.{ SecureSocial, Authenticator }
import play.api.libs.json.JsSuccess
import scala.util.Failure
import com.keepit.commanders.ConnectionInfo
import scala.util.Success
import play.api.libs.json.JsObject
import com.keepit.common.http._
import KifiSession._

class MobileUserController @Inject() (
  val userActionsHelper: UserActionsHelper,
  userCommander: UserCommander,
  userConnectionsCommander: UserConnectionsCommander,
  typeaheadCommander: TypeaheadCommander,
  keepCountCache: KeepCountCache,
  keepRepo: KeepRepo,
  libraryRepo: LibraryRepo,
  userRepo: UserRepo,
  userConnectionRepo: UserConnectionRepo,
  userValueRepo: UserValueRepo,
  notifyPreferenceRepo: UserNotifyPreferenceRepo,
  libraryCommander: LibraryCommander,
  db: Database,
  airbrakeNotifier: AirbrakeNotifier,
  postOffice: LocalPostOffice,
  s3ImageStore: S3ImageStore)
    extends UserActions with ShoeboxServiceController {

  def friends(page: Int, pageSize: Int) = UserAction { request =>
    val (connectionsPage, total) = userConnectionsCommander.getConnectionsPage(request.userId, page, pageSize)
    val friendsJsons = connectionsPage.map {
      case ConnectionInfo(friend, _, unfriended, unsearched) =>
        Json.toJson(friend).asInstanceOf[JsObject] ++ Json.obj(
          "searchFriend" -> unsearched,
          "unfriended" -> unfriended
        )
    }
    Ok(Json.obj(
      "friends" -> friendsJsons,
      "total" -> total
    ))
  }

  def socialNetworkInfo() = UserAction { request =>
    Ok(Json.toJson(userCommander.socialNetworkInfo(request.userId)))
  }

  def abookInfo() = UserAction.async { request =>
    userCommander.getGmailABookInfos(request.userId) map { abooks =>
      Ok(Json.toJson(abooks))
    }
  }

  def uploadContacts(origin: ABookOriginType) = UserAction.async(parse.json(maxLength = 1024 * 50000)) { request =>
    val json: JsValue = request.body
    userCommander.uploadContactsProxy(request.userId, origin, json) collect {
      case Success(abookInfo) => Ok(Json.toJson(abookInfo))
      case Failure(ex) => BadRequest(Json.obj("code" -> ex.getMessage)) // can do better
    }
  }

  def currentUser = UserAction.async { implicit request =>
    getUserInfo(request, true)

  }

  def updateCurrentUser() = UserAction.async(parse.tolerantJson) { implicit request =>
    request.body.validate[UpdatableUserInfo] match {
      case JsSuccess(userData, _) => {
        userCommander.updateUserInfo(request.userId, userData)
        getUserInfo(request)
      }
      case JsError(errors) if errors.exists { case (path, _) => path == __ \ "emails" } =>
        Future.successful(BadRequest(Json.obj("error" -> "bad email addresses")))
      case _ =>
        Future.successful(BadRequest(Json.obj("error" -> "could not parse user info from body")))
    }
  }

  def basicUserInfo(id: ExternalId[User], friendCount: Boolean) = UserAction { implicit request =>
    db.readOnlyReplica { implicit session =>
      userRepo.getOpt(id).map { user =>
        Ok {
          val userJson = Json.toJson(BasicUser.fromUser(user)).as[JsObject]
          if (friendCount) userJson ++ Json.obj("friendCount" -> userConnectionRepo.getConnectionCount(user.id.get))
          else userJson
        }
      } getOrElse {
        NotFound(Json.obj("error" -> "user not found"))
      }
    }
  }

  private def getProfileInfo(userId: Id[User])(implicit session: RSession): (Int, Int, Int, Int) = {
    val friendCount = userConnectionRepo.getConnectionCount(userId)
    val keepCount = keepCountCache.getOrElse(KeepCountKey(userId)) { keepRepo.getCountByUser(userId) } // getCountByUser goes directly to db
    val libraries = libraryRepo.getAllByOwner(userId)
    val libCount = libraries.size
    val libFollowerCount = libraries.foldLeft(0) { (a, c) => a + c.memberCount } - libCount // memberCount includes owner
    (friendCount, keepCount, libCount, libFollowerCount)
  }

  private def getUserInfo[T](request: UserRequest[T], profileInfo: Boolean = false) = {
    val user = userCommander.getUserInfo(request.user)
    val (friendCount, keepCount, libCount, libFollowerCount) = if (profileInfo) db.readOnlyMaster { implicit s => getProfileInfo(request.userId) } else (0, 0, 0, 0)
    userCommander.getKeepAttributionInfo(request.userId) map { info =>
      Ok(toJson(user.basicUser).as[JsObject] ++
        toJson(user.info).as[JsObject] ++
        Json.obj(
          "notAuthed" -> user.notAuthed,
          "experiments" -> request.experiments.map(_.value),
          "clickCount" -> info.clickCount,
          "rekeepCount" -> info.rekeepCount,
          "rekeepTotalCount" -> info.rekeepTotalCount,
          "friendCount" -> friendCount,
          "keepCount" -> keepCount,
          "libCount" -> libCount,
          "libFollowerCount" -> libFollowerCount
        )
      )
    }
  }

  def changePassword = UserAction(parse.tolerantJson) { implicit request =>
    val oldPassword = (request.body \ "oldPassword").asOpt[String] // todo: use char[]
    val newPassword = (request.body \ "newPassword").as[String]
    if (newPassword.length < 7) {
      BadRequest(Json.obj("error" -> "bad_new_password"))
    } else {
      userCommander.doChangePassword(request.userId, oldPassword, newPassword) match {
        case Failure(e) => Forbidden(Json.obj("code" -> e.getMessage))
        case Success(_) => Ok(Json.obj("code" -> "password_changed"))
      }
    }
  }

  // legacy
  def queryAll(search: Option[String], network: Option[String], limit: Int, pictureUrl: Boolean = false) = UserAction.async { request =>
    typeaheadCommander.queryAll(request.userId, search, network, limit, pictureUrl) map { r =>
      Ok(Json.toJson(r))
    }
  }

  def friend(externalId: ExternalId[User]) = UserAction { request =>
    val (success, code) = userConnectionsCommander.friend(request.userId, externalId)
    val res = Json.obj("code" -> code)
    if (success) Ok(res) else NotFound(res)
  }

  def unfriend(externalId: ExternalId[User]) = UserAction { request =>
    if (userConnectionsCommander.unfriend(request.userId, externalId)) {
      Ok(Json.obj("code" -> "removed"))
    } else {
      NotFound(Json.obj("code" -> "user_not_found"))
    }
  }

  def ignoreFriendRequest(externalId: ExternalId[User]) = UserAction { request =>
    val (success, code) = userConnectionsCommander.ignoreFriendRequest(request.userId, externalId)
    val res = Json.obj("code" -> code)
    if (success) Ok(res) else NotFound(res)
  }

  def incomingFriendRequests = UserAction { request =>
    val users = userConnectionsCommander.incomingFriendRequests(request.userId)
    Ok(Json.toJson(users))
  }

  def outgoingFriendRequests = UserAction { request =>
    val users = userConnectionsCommander.outgoingFriendRequests(request.userId)
    Ok(Json.toJson(users))
  }

  def postDelightedAnswer = UserAction.async(parse.tolerantJson) { request =>
    implicit val source = DelightedAnswerSources.fromUserAgent(request.userAgentOpt)
    Json.fromJson[BasicDelightedAnswer](request.body) map { answer =>
      userCommander.postDelightedAnswer(request.userId, answer) map { externalIdOpt =>
        externalIdOpt map { externalId =>
          Ok(Json.obj("answerId" -> externalId))
        } getOrElse NotFound
      }
    } getOrElse Future.successful(BadRequest)
  }

  def cancelDelightedSurvey = UserAction.async { implicit request =>
    userCommander.cancelDelightedSurvey(request.userId) map { success =>
      if (success) Ok else BadRequest
    }
  }

  def disconnect(networkString: String) = UserAction { implicit request =>
    val (suiOpt, code) = userConnectionsCommander.disconnect(request.userId, networkString)
    suiOpt match {
      case None => BadRequest(Json.obj("code" -> code))
      case Some(newLoginUser) =>
        val identity = newLoginUser.credentials.get
        Authenticator.create(identity).fold(
          error => Status(INTERNAL_SERVER_ERROR)(Json.obj("code" -> "internal_server_error")),
          authenticator => {
            Ok(Json.obj("code" -> code))
              .withSession((request.session - SecureSocial.OriginalUrlKey).setUserId(newLoginUser.userId.get))
              .withCookies(authenticator.toCookie)
          }
        )
    }
  }

  def excludeFriend(id: ExternalId[User]) = UserAction { request =>
    userConnectionsCommander.excludeFriend(request.userId, id) map { changed =>
      val msg = if (changed) "changed" else "no_change"
      Ok(Json.obj("code" -> msg))
    } getOrElse {
      BadRequest(Json.obj("code" -> "not_friend"))
    }
  }

  def includeFriend(id: ExternalId[User]) = UserAction { request =>
    userConnectionsCommander.includeFriend(request.userId, id) map { changed =>
      val msg = if (changed) "changed" else "no_change"
      Ok(Json.obj("code" -> msg))
    } getOrElse {
      BadRequest(Json.obj("code" -> "not_friend"))
    }
  }

  private val MobilePrefNames = Set(UserValueName.SHOW_DELIGHTED_QUESTION)

  def getPrefs() = UserAction.async { request =>
    // Make sure the user's last active date has been updated before returning the result
    userCommander.setLastUserActive(request.userId)
    val notifyPrefs = db.readOnlyMaster { implicit s =>
      val canShowRecosReminders = notifyPreferenceRepo.canNotify(request.userId, NotifyPreference.RECOS_REMINDER)
      Json.obj("recos_reminder" -> canShowRecosReminders)
    }
    userCommander.getPrefs(MobilePrefNames, request.userId, request.experiments) map { prefObj =>
      Ok(prefObj ++ notifyPrefs)
    }
  }

  def getSettings() = UserAction { request =>
    val storedBody = db.readOnlyMaster { implicit s =>
      userValueRepo.getValue(request.userId, UserValues.userProfileSettings)
    }
    val userSettings = UserValueSettings.readFromJsValue(storedBody)
    //Ok(Json.toJson(userSettings)) // todo (aaron): use this when multiple fields to settings. With only one field @json macro doesn't describe field name
    Ok(Json.obj("showFollowedLibraries" -> userSettings.showFollowedLibraries))
  }

  def setSettings() = UserAction(parse.tolerantJson) { request =>
    val showFollowLibrariesOpt = (request.body \ "showFollowedLibraries").asOpt[Boolean]
    val settingsList = Map(UserValueName.SHOW_FOLLOWED_LIBRARIES -> showFollowLibrariesOpt)

    val newMapping = settingsList.collect {
      case (userVal, Some(optionVal)) => userVal -> Json.toJson(optionVal)
    }
    userCommander.setSettings(request.userId, newMapping)
    NoContent
  }

  //this takes appsflyer attribution data and converts it into a kcid for the user
  def setAppsflyerAttribution() = UserAction(parse.json(maxLength = 1024 * 50000)) { request =>
    val kcid1: String = (request.body \ "campaign").asOpt[String].orElse((request.body \ "campaign_name").asOpt[String]).orElse((request.body \ "campaign_id").asOpt[Long].map(_.toString)).getOrElse("na").replace("-", "_")
    val kcid2: String = (request.body \ "af_status").asOpt[String].map { af_status =>
      if (af_status == "Non-organic") "pm_m"
      else af_status.toLowerCase.replace("-", "_")
    } getOrElse ("na")
    val kcid3: String = (request.body \ "media_source").asOpt[String].getOrElse("na")
    db.readWrite(attempts = 2) { implicit session =>
      if (userValueRepo.getValueStringOpt(request.userId, UserValueName.KIFI_CAMPAIGN_ID).isEmpty) userValueRepo.setValue(request.userId, UserValueName.KIFI_CAMPAIGN_ID, s"$kcid1-$kcid2-$kcid3")
    }
    Ok
  }

  def profile(username: String) = MaybeUserAction { request =>
    val viewer = request.userOpt
    userCommander.profile(Username(username), viewer) match {
      case None => NotFound(s"can't find username $username")
      case Some(profile) =>
        val (numLibraries, numFollowedLibs, numInvitedLibs) = libraryCommander.countLibraries(profile.userId, viewer.map(_.id.get))
        val json = Json.toJson(profile.basicUserWithFriendStatus).asInstanceOf[JsObject] ++ Json.obj(
          "numLibraries" -> numLibraries,
          "numFollowedLibraries" -> numFollowedLibs,
          "numKeeps" -> profile.numKeeps)

        numInvitedLibs match {
          case Some(numInvited) =>
            Ok(json ++ Json.obj("numInvitedLibraries" -> numInvited))
          case _ =>
            Ok(json)
        }
    }
  }

  def uploadBinaryUserPicture() = UserAction(parse.maxLength(1024 * 1024 * 15, parse.temporaryFile)) { implicit request =>
    request.body match {
      case Right(tempFile) =>
        s3ImageStore.uploadTemporaryPicture(tempFile.file) match {
          case Success((token, _)) =>
            s3ImageStore.copyTempFileToUserPic(request.userId, request.user.externalId, token, None) match {
              case Some(picUrl) => Ok(Json.obj("url" -> picUrl))
              case None => InternalServerError(Json.obj("error" -> "unable_to_resize"))
            }
          case Failure(ex) =>
            airbrakeNotifier.notify("Couldn't upload temporary picture (xhr direct)", ex)
            BadRequest(JsNumber(0))
        }
      case Left(err) =>
        BadRequest(Json.obj("error" -> "file_too_large", "size" -> err.length))
    }
  }

  def reportData() = Action(parse.tolerantJson) { implicit request =>
    val body = "<pre>" + Json.prettyPrint(request.body) + "</pre>"
    val email = ElectronicMail(
      from = SystemEmailAddress.ANDREW,
      to = Seq(EmailAddress("jeremy@kifi.com"), EmailAddress("thass@kifi.com"), EmailAddress("andrew@kifi.com")),
      subject = "iOS Report",
      htmlBody = body,
      fromName = Some("iOS Nagger"),
      category = NotificationCategory.System.PLAY
    )
    db.readWrite { implicit rw => postOffice.sendMail(email) }
    Ok
  }

}

