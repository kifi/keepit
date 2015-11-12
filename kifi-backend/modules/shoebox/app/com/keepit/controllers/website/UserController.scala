package com.keepit.controllers.website

import java.util.concurrent.atomic.AtomicBoolean

import com.google.inject.Inject
import com.keepit.abook.{ ABookServiceClient, ABookUploadConf }
import com.keepit.commanders.emails.EmailSenderProvider
import com.keepit.commanders.{ ConnectionInfo, _ }
import com.keepit.common.controller._
import com.keepit.common.crypto.{ PublicIdConfiguration, PublicId }
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.http._
import com.keepit.common.mail.{ EmailAddress, _ }
import com.keepit.common.social.NewConnections
import com.keepit.common.store.{ ImageCropAttributes, S3ImageStore }
import com.keepit.common.time._
import com.keepit.controllers.core.NetworkInfoLoader
import com.keepit.eliza.ElizaServiceClient
import com.keepit.heimdal.{ BasicDelightedAnswer, DelightedAnswerSources }
import com.keepit.inject.FortyTwoConfig
import com.keepit.model._
import com.keepit.notify.model.Recipient
import com.keepit.notify.model.event.NewConnectionInvite
import com.keepit.search.SearchServiceClient
import com.keepit.social.BasicUser
import com.keepit.common.core._

import play.api.data.Form
import play.api.data.Forms._
import play.api.libs.Comet
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.concurrent.{ Promise => PlayPromise }
import play.api.libs.iteratee.Enumerator
import play.api.libs.json.Json.toJson
import play.api.libs.json.{ JsBoolean, JsNumber, _ }
import play.api.mvc.{ MaxSizeExceeded, Request }
import play.twirl.api.Html
import play.api.libs.json._

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{ Failure, Success, Try }

class UserController @Inject() (
    db: Database,
    userRepo: UserRepo,
    userExperimentCommander: LocalUserExperimentCommander,
    userConnectionRepo: UserConnectionRepo,
    emailRepo: UserEmailAddressRepo,
    userEmailAddressCommander: UserEmailAddressCommander,
    userValueRepo: UserValueRepo,
    socialUserRepo: SocialUserInfoRepo,
    invitationRepo: InvitationRepo,
    networkInfoLoader: NetworkInfoLoader,
    val userActionsHelper: UserActionsHelper,
    friendRequestRepo: FriendRequestRepo,
    postOffice: LocalPostOffice,
    userConnectionsCommander: UserConnectionsCommander,
    organizationDomainOwnershipCommander: OrganizationDomainOwnershipCommander,
    userCommander: UserCommander,
    clock: Clock,
    s3ImageStore: S3ImageStore,
    abookServiceClient: ABookServiceClient,
    airbrakeNotifier: AirbrakeNotifier,
    abookUploadConf: ABookUploadConf,
    elizaServiceClient: ElizaServiceClient,
    emailSender: EmailSenderProvider,
    userProfileCommander: UserProfileCommander,
    checklistCommander: ChecklistCommander,
    fortytwoConfig: FortyTwoConfig,
    implicit val publicIdConfig: PublicIdConfiguration) extends UserActions with ShoeboxServiceController {

  def friends(page: Int, pageSize: Int) = UserAction { request =>
    val (connectionsPage, total) = userConnectionsCommander.getConnectionsPage(request.userId, page, pageSize)
    val friendsJsons = db.readOnlyMaster { implicit s =>
      val friendCounts = userConnectionRepo.getConnectionCounts(connectionsPage.map(_.userId).toSet)
      connectionsPage.map {
        case ConnectionInfo(friend, friendId, unfriended, unsearched) =>
          Json.toJson(friend).asInstanceOf[JsObject] ++ Json.obj(
            "searchFriend" -> unsearched,
            "unfriended" -> unfriended,
            "friendCount" -> friendCounts(friendId)
          )
      }
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
    val abookF = userCommander.getGmailABookInfos(request.userId)
    abookF.map { abooks =>
      Ok(Json.toJson(abooks.map(ExternalABookInfo.fromABookInfo _)))
    }
  }

  def friendNetworkInfo(id: ExternalId[User]) = UserAction { request =>
    Ok(toJson(networkInfoLoader.load(request.userId, id)))
  }

  def unfriend(id: ExternalId[User]) = UserAction { request =>
    if (userConnectionsCommander.unfriend(request.userId, id)) {
      Ok(Json.obj("removed" -> true))
    } else {
      NotFound(Json.obj("error" -> s"User with id $id not found."))
    }
  }

  def closeAccount = UserAction(parse.tolerantJson) { request =>
    val comment = (request.body \ "comment").asOpt[String].getOrElse("")
    userCommander.sendCloseAccountEmail(request.userId, comment)
    Ok(Json.obj("closed" -> true))
  }

  def friend(id: ExternalId[User]) = UserAction { request =>
    val (success, code) = userConnectionsCommander.friend(request.user, id)
    if (success) {
      Ok(Json.obj("success" -> true, code -> true))
    } else {
      NotFound(Json.obj("error" -> code))
    }
  }

  def ignoreFriendRequest(id: ExternalId[User]) = UserAction { request =>
    val (success, code) = userConnectionsCommander.ignoreFriendRequest(request.userId, id)
    if (success) Ok(Json.obj("success" -> true))
    else if (code == "friend_request_not_found") NotFound(Json.obj("error" -> s"There is no active friend request from user $id."))
    else if (code == "user_not_found") BadRequest(Json.obj("error" -> s"User with id $id not found."))
    else BadRequest(Json.obj("error" -> code))
  }

  def cancelFriendRequest(id: ExternalId[User]) = UserAction { request =>
    db.readWrite { implicit s =>
      userRepo.getOpt(id) map { recipient =>
        friendRequestRepo.getBySenderAndRecipient(request.userId, recipient.id.get,
          Set(FriendRequestStates.ACCEPTED, FriendRequestStates.ACTIVE)) map { friendRequest =>
            if (friendRequest.state == FriendRequestStates.ACCEPTED) {
              BadRequest(Json.obj("error" -> s"The friend request has already been accepted", "alreadyAccepted" -> true))
            } else {
              friendRequestRepo.save(friendRequest.copy(state = FriendRequestStates.INACTIVE))
              elizaServiceClient.completeNotification(NewConnectionInvite, friendRequest.senderId -> friendRequest.recipientId, Recipient(friendRequest.recipientId))
              Ok(Json.obj("success" -> true))
            }
          } getOrElse NotFound(Json.obj("error" -> s"There is no active friend request for user $id."))
      } getOrElse BadRequest(Json.obj("error" -> s"User with id $id not found."))
    }
  }

  def incomingFriendRequests = UserAction { request =>
    val users = userConnectionsCommander.incomingFriendRequests(request.userId)
    Ok(Json.toJson(users))
  }

  def outgoingFriendRequests = UserAction { request =>
    val users = userConnectionsCommander.outgoingFriendRequests(request.userId)
    Ok(Json.toJson(users))
  }

  def excludeFriend(id: ExternalId[User]) = UserAction { request =>
    userConnectionsCommander.excludeFriend(request.userId, id) map { changed =>
      Ok(Json.obj("changed" -> changed))
    } getOrElse {
      BadRequest(Json.obj("error" -> s"You are not friends with user $id"))
    }
  }

  def includeFriend(id: ExternalId[User]) = UserAction { request =>
    userConnectionsCommander.includeFriend(request.userId, id) map { changed =>
      Ok(Json.obj("changed" -> changed))
    } getOrElse {
      BadRequest(Json.obj("error" -> s"You are not friends with user $id"))
    }
  }

  def currentUser = UserAction { implicit request =>
    getUserInfo(request.userId)
  }

  def changePassword = UserAction(parse.tolerantJson) { implicit request =>
    val oldPasswordOpt = (request.body \ "oldPassword").asOpt[String] // todo: use char[]
    val newPassword = (request.body \ "newPassword").as[String]
    if (newPassword.length < 7) {
      BadRequest(Json.obj("error" -> "bad_new_password"))
    } else {
      userCommander.changePassword(request.userId, newPassword, oldPassword = oldPasswordOpt) match {
        case Failure(e) => Forbidden(Json.obj("error" -> e.getMessage))
        case Success(_) => Ok(Json.obj("success" -> true))
      }
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

  def getEmailInfo(email: EmailAddress) = UserAction { implicit request =>
    db.readOnlyMaster { implicit session =>
      emailRepo.getByAddress(email) match {
        case Some(emailRecord) if (emailRecord.userId != request.userId) && emailRecord.verified => Forbidden(Json.obj("error" -> "email_belongs_to_other_user"))
        case Some(emailRecord) if (emailRecord.userId == request.userId) => {
          val pendingPrimary = userValueRepo.getValueStringOpt(request.user.id.get, UserValueName.PENDING_PRIMARY_EMAIL).map(EmailAddress(_))
          Ok(Json.toJson(EmailInfo(
            address = emailRecord.address,
            isVerified = emailRecord.verified,
            isPrimary = emailRecord.primary,
            isPendingPrimary = pendingPrimary.exists(_.equalsIgnoreCase(emailRecord.address))
          )))
        }
        case _ => Ok(Json.obj("status" -> "available"))
      }
    }
  }

  def updateUsername() = UserAction(parse.tolerantJson) { implicit request =>
    val newUsername = (request.body \ "username").as[Username]
    userCommander.setUsername(request.userId, newUsername) match {
      case Left(error) => BadRequest(Json.obj("error" -> error))
      case Right(username) => Ok(Json.obj("username" -> username))
    }
  }

  def updateName() = UserAction(parse.tolerantJson) { implicit request =>
    val newFirstName = (request.body \ "firstName").asOpt[String]
    val newLastName = (request.body \ "lastName").asOpt[String]
    userCommander.updateName(request.userId, newFirstName, newLastName)
    Ok(JsString("success"))
  }

  def updateBiography() = UserAction(parse.tolerantJson) { implicit request =>
    val newBio = (request.body \ "biography").as[String]
    userCommander.updateUserBiography(request.userId, newBio)
    Ok(JsString("success"))
  }

  def addEmail() = UserAction(parse.tolerantJson) { implicit request =>
    val newAddress = (request.body \ "email").as[String]
    EmailAddress.validate(newAddress) match {
      case Failure(e) => BadRequest(e.getMessage)
      case Success(newEmail) =>
        userEmailAddressCommander.addEmail(request.userId, newEmail) match {
          case Left(s) => BadRequest(s)
          case Right(_) => Ok(JsString("success"))
        }
    }
  }
  def changePrimaryEmail() = UserAction(parse.tolerantJson) { implicit request =>
    val targetAddress = (request.body \ "email").as[String]
    EmailAddress.validate(targetAddress) match {
      case Failure(e) =>
        BadRequest(e.getMessage)
      case Success(targetEmail) =>
        userEmailAddressCommander.makeEmailPrimary(request.userId, targetEmail) match {
          case Left(s) => BadRequest(s)
          case Right(_) => Ok(JsString("success"))
        }
    }
  }
  def removeEmail() = UserAction(parse.tolerantJson) { implicit request =>
    val targetAddress = (request.body \ "email").as[String]
    EmailAddress.validate(targetAddress) match {
      case Failure(e) =>
        BadRequest(e.getMessage)
      case Success(targetEmail) =>
        userEmailAddressCommander.removeEmail(request.userId, targetEmail) match {
          case Left(s) => BadRequest(s)
          case Right(_) => Ok(JsString("success"))
        }
    }
  }

  //private val emailRegex = """^[a-zA-Z0-9.!#$%&'*+\/=?^_`{|}~-]+@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$""".r
  @deprecated(message = "use addEmail/modifyEmail/removeEmail", since = "2014-08-20")
  def updateCurrentUser() = UserAction(parse.tolerantJson) { implicit request =>
    request.body.validate[UpdatableUserInfo] match {
      case JsSuccess(userData, _) => {
        userCommander.updateUserInfo(request.userId, userData)
        getUserInfo(request.userId)
      }
      case JsError(errors) if errors.exists { case (path, _) => path == __ \ "emails" } =>
        BadRequest(Json.obj("error" -> "bad email addresses"))
      case _ =>
        BadRequest(Json.obj("error" -> "could not parse user info from body"))
    }
  }

  private def getUserInfo[T](userId: Id[User]) = {
    val user = db.readOnlyMaster { implicit session =>
      userRepo.get(userId)
    }

    val experiments = userExperimentCommander.getExperimentsByUser(userId)
    val pimpedUser = userCommander.getUserInfo(user)
    val pendingFriendRequests = db.readOnlyMaster { implicit session =>
      friendRequestRepo.getCountByRecipient(userId)
    }

    val json = {
      implicit val orgViewWrites = OrganizationView.embeddedMembershipWrites
      Json.toJson(pimpedUser.basicUser).as[JsObject] ++
        toJson(pimpedUser.info).as[JsObject] ++
        Json.obj(
          "notAuthed" -> pimpedUser.notAuthed,
          "numLibraries" -> pimpedUser.numLibraries,
          "numConnections" -> pimpedUser.numConnections,
          "numFollowers" -> pimpedUser.numFollowers,
          "experiments" -> experiments.map(_.value),
          "pendingFriendRequests" -> pendingFriendRequests,
          "orgs" -> pimpedUser.orgs,
          "pendingOrgs" -> pimpedUser.pendingOrgs,
          "potentialOrgs" -> pimpedUser.potentialOrgs
        )
    }
    Ok(json)
  }

  private val SitePrefNames = {
    import UserValueName._
    Set(
      AUTO_SHOW_GUIDE,
      SHOW_DELIGHTED_QUESTION,
      HAS_NO_PASSWORD,
      USE_MINIMAL_KEEP_CARD,
      HAS_SEEN_FTUE,
      COMPANY_NAME,
      STORED_CREDIT_CODE
    )
  }

  def getPrefs() = UserAction.async { request =>
    // The prefs endpoint is used as an indicator that the user is active
    userCommander.setLastUserActive(request.userId)

    val checklistF = Future {
      checklistCommander.checklist(request.userId, ChecklistPlatform.Website)
    }.map { chk =>
      if (chk.exists(!_._2)) { // there is an incomplete item
        chk
      } else {
        Seq.empty[(String, Boolean)]
      }
    }.recover {
      case ex: Throwable =>
        Seq.empty[(String, Boolean)]
    }.map { checklist =>
      checklist.map {
        case (name, isComplete) =>
          Json.obj("name" -> name, "complete" -> isComplete)
      } |> JsArray.apply
    }

    val prefsF = userCommander.getPrefs(SitePrefNames, request.userId, request.experiments)

    for {
      prefs <- prefsF
      checklist <- checklistF
    } yield {
      Ok(prefs ++ Json.obj("checklist" -> checklist))
    }
  }

  def savePrefs() = UserAction(parse.tolerantJson) { request =>
    val o = request.request.body.as[JsObject]
    val map = o.value.map(t => UserValueName(t._1) -> t._2).toMap
    val allowedMap = map.filter(m => SitePrefNames.contains(m._1))
    if (allowedMap.nonEmpty) {
      userCommander.savePrefs(request.userId, map)
      Ok(JsObject(allowedMap.toSeq.map(m => m._1.name -> m._2)))
    } else {
      BadRequest(Json.obj("error" -> "no_valid_preferences"))
    }
  }

  def uploadBinaryUserPicture() = MaybeUserAction(parse.maxLength(1024 * 1024 * 15, parse.temporaryFile)) { implicit request =>
    doUploadBinaryUserPicture
  }
  def doUploadBinaryUserPicture(implicit request: Request[Either[MaxSizeExceeded, play.api.libs.Files.TemporaryFile]]) = {
    request.body match {
      case Right(tempFile) =>
        s3ImageStore.uploadTemporaryPicture(tempFile.file) match {
          case Success((token, pictureUrl)) =>
            Ok(Json.obj("token" -> token, "url" -> pictureUrl))
          case Failure(ex) =>
            airbrakeNotifier.notify("Couldn't upload temporary picture (xhr direct)", ex)
            BadRequest(JsNumber(0))
        }
      case Left(err) =>
        BadRequest(s"""{"error": "file_too_large", "size": ${err.length}}""")
    }
  }

  private case class UserPicInfo(
    picToken: Option[String],
    picHeight: Option[Int], picWidth: Option[Int],
    cropX: Option[Int], cropY: Option[Int],
    cropSize: Option[Int])
  private val userPicForm = Form[UserPicInfo](
    mapping(
      "picToken" -> optional(text),
      "picHeight" -> optional(number),
      "picWidth" -> optional(number),
      "cropX" -> optional(number),
      "cropY" -> optional(number),
      "cropSize" -> optional(number)
    )(UserPicInfo.apply)(UserPicInfo.unapply)
  )
  def setUserPicture() = UserAction { implicit request =>
    userPicForm.bindFromRequest.fold(
      formWithErrors => BadRequest(Json.obj("error" -> formWithErrors.errors.head.message)),
      {
        case UserPicInfo(picToken, picHeight, picWidth, cropX, cropY, cropSize) =>
          val cropAttributes = parseCropForm(picHeight, picWidth, cropX, cropY, cropSize)
          picToken.map { token =>
            s3ImageStore.copyTempFileToUserPic(request.user.id.get, request.user.externalId, token, cropAttributes)
          }
          Ok("0")
      })
  }
  private def parseCropForm(picHeight: Option[Int], picWidth: Option[Int], cropX: Option[Int], cropY: Option[Int], cropSize: Option[Int]) = {
    for {
      h <- picHeight
      w <- picWidth
      x <- cropX
      y <- cropY
      s <- cropSize
    } yield ImageCropAttributes(w = w, h = h, x = x, y = y, s = s)
  }

  private val url = fortytwoConfig.applicationBaseUrl

  def resendVerificationEmail(email: EmailAddress) = UserAction.async { implicit request =>
    db.readWrite { implicit s =>
      emailRepo.getByAddressAndUser(request.userId, email) match {
        case Some(emailAddr) => userEmailAddressCommander.sendVerificationEmailHelper(emailAddr).imap(_ => Ok("0"))
        case _ => Future.successful(Forbidden("0"))
      }
    }
  }

  def hideOrganizationDomain(pubOrgId: PublicId[Organization]) = UserAction { request =>
    Organization.decodePublicId(pubOrgId) match {
      case Failure(ex) => OrganizationFail.INVALID_PUBLIC_ID.asErrorResponse
      case Success(orgId: Id[Organization]) => {
        organizationDomainOwnershipCommander.hideOrganizationForUser(request.userId, orgId)
        Ok
      }
    }
  }

  def importStatus() = UserAction.async { implicit request =>
    val networks = Seq("facebook", "linkedin")

    val networkStatuses = Future {
      JsObject(db.readOnlyMaster { implicit session =>
        networks.map { network =>
          userValueRepo.getValueStringOpt(request.userId, UserValueName.importInProgress(network)).flatMap { r =>
            if (r == "false") {
              None
            } else {
              Some(network -> JsString(r))
            }
          }
        }
      }.flatten)
    }

    val abookStatuses = userCommander.getGmailABookInfos(request.userId).map { abooks =>
      JsObject(abooks.map { abookInfo =>
        abookInfo.state match {
          case ABookInfoStates.PENDING | ABookInfoStates.PROCESSING => // we only care if it's actively working. in all other cases, client knows when it refreshes.
            val identifier = (abookInfo.ownerEmail orElse abookInfo.ownerId).map(_.toString).getOrElse(abookInfo.id.get.toString)
            val importantBits = Seq(
              "state" -> Some(abookInfo.state.value),
              "numContacts" -> abookInfo.numContacts.map(_.toString),
              "numProcessed" -> abookInfo.numProcessed.map(_.toString),
              "lastUpdated" -> Some(abookInfo.updatedAt.toStandardTimeString)
            ).filter(_._2.isDefined).map(m => m._1 -> JsString(m._2.get))
            Some(identifier -> JsObject(importantBits))
          case _ => None
        }
      }.flatten)
    }

    for {
      n <- networkStatuses
      a <- abookStatuses
    } yield {
      Ok(JsObject(Seq("network" -> n, "abook" -> a)))
    }
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

}
