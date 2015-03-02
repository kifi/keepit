package com.keepit.controllers.website

import java.util.concurrent.atomic.AtomicBoolean

import com.google.inject.Inject
import com.keepit.abook.{ ABookServiceClient, ABookUploadConf }
import com.keepit.commanders.emails.EmailSenderProvider
import com.keepit.commanders.{ ConnectionInfo, _ }
import com.keepit.common.controller._
import com.keepit.common.db.slick._
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.http._
import com.keepit.common.mail.{ EmailAddress, _ }
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.store.{ ImageCropAttributes, S3ImageStore }
import com.keepit.common.time._
import com.keepit.controllers.core.NetworkInfoLoader
import com.keepit.eliza.ElizaServiceClient
import com.keepit.heimdal.{ BasicDelightedAnswer, DelightedAnswerSources }
import com.keepit.inject.FortyTwoConfig
import com.keepit.model._
import com.keepit.search.SearchServiceClient
import com.keepit.social.BasicUser
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
    userValueRepo: UserValueRepo,
    socialConnectionRepo: SocialConnectionRepo,
    socialUserRepo: SocialUserInfoRepo,
    invitationRepo: InvitationRepo,
    networkInfoLoader: NetworkInfoLoader,
    val userActionsHelper: UserActionsHelper,
    friendRequestRepo: FriendRequestRepo,
    postOffice: LocalPostOffice,
    userConnectionsCommander: UserConnectionsCommander,
    userCommander: UserCommander,
    elizaServiceClient: ElizaServiceClient,
    clock: Clock,
    s3ImageStore: S3ImageStore,
    abookServiceClient: ABookServiceClient,
    airbrakeNotifier: AirbrakeNotifier,
    authCommander: AuthCommander,
    searchClient: SearchServiceClient,
    abookUploadConf: ABookUploadConf,
    emailSender: EmailSenderProvider,
    libraryCommander: LibraryCommander,
    libraryInviteRepo: LibraryInviteRepo,
    libraryRepo: LibraryRepo,
    basicUserRepo: BasicUserRepo,
    libraryMembershipRepo: LibraryMembershipRepo,
    fortytwoConfig: FortyTwoConfig) extends UserActions with ShoeboxServiceController {

  //todo(eishay): caching work!
  def loadFullConnectionUser(ownerId: Id[User], owner: BasicUser, connectedOpt: Option[Boolean], viewer: Option[Id[User]]): JsValue = {
    val json = Json.toJson(owner).as[JsObject]
    db.readOnlyMaster { implicit t =>
      //global or mutual
      val libCount = viewer.map(u => libraryRepo.countLibrariesForOtherUser(ownerId, u)).getOrElse(libraryRepo.countLibrariesOfUserFromAnonymous(ownerId)) //not cached
      //global
      val followersCount = libraryMembershipRepo.countFollowersWithOwnerId(ownerId) //cached
      val connectionCount = userConnectionRepo.getConnectionCount(ownerId) //cached
      val jsonWithGlobalCounts = json +
        ("libs" -> JsNumber(libCount)) +
        ("followers" -> JsNumber(followersCount)) +
        ("connections" -> JsNumber(connectionCount))
      //mutual
      viewer.map { u =>
        val connected = connectedOpt.getOrElse(userConnectionRepo.getConnectionOpt(ownerId, u).exists(_.state == UserConnectionStates.ACTIVE)) //not cached
        val followingLibCount = libraryRepo.countLibrariesOfOwnerUserFollow(ownerId, u) //not cached
        val mutualConnectionCount = userConnectionRepo.getMutualConnectionCount(ownerId, u) //cached
        jsonWithGlobalCounts +
          ("connected" -> JsBoolean(connected)) +
          ("mlibs" -> JsNumber(followingLibCount)) +
          ("mConnections" -> JsNumber(mutualConnectionCount))
      }.getOrElse(jsonWithGlobalCounts)
    }
  }

  def fullConnectionByViewer(ownerExternalId: ExternalId[User]) = MaybeUserAction { request =>
    val owner = db.readOnlyReplica { implicit s => userRepo.get(ownerExternalId) }
    val ownerId = owner.id.get
    val viewer = request.userIdOpt
    Ok(loadFullConnectionUser(ownerId, BasicUser.fromUser(owner), None, viewer))
  }

  def profileConnections(username: Username, limit: Int, userExtIds: String) = MaybeUserAction.async { request =>
    userCommander.userFromUsername(username) match {
      case None =>
        log.warn(s"can't find username ${username.value}")
        Future.successful(NotFound(s"username ${username.value}"))
      case Some(owner) =>
        val ownerId = owner.id.get
        val viewer = request.userIdOpt.getOrElse(ownerId)
        if (userExtIds.isEmpty) {
          userConnectionsCommander.getConnectionsSortedByRelationship(viewer, ownerId) map { connections =>
            val head = connections.take(limit)
            val userMap = db.readOnlyMaster { implicit s => basicUserRepo.loadAll(head.map(_.userId).toSet) }
            val users = head.map(u => userMap(u.userId) -> u.connected)
            val usersJson = users.map(u => loadFullConnectionUser(ownerId, u._1, Some(u._2), request.userIdOpt))
            Ok(Json.obj("users" -> usersJson, "count" -> connections.size))
          }
        } else {
          Try(userExtIds.split(',').map(ExternalId[User])) match {
            case Success(userIds) =>
              val users = db.readOnlyMaster { implicit s =>
                userIds.map(userRepo.getOpt).flatten
              }
              val jsons = users.map { user =>
                loadFullConnectionUser(ownerId, BasicUser.fromUser(user), None, request.userIdOpt)
              }
              Future.successful(Ok(Json.obj("users" -> JsArray(jsons))))
            case _ =>
              Future.successful(BadRequest("ids invalid"))
          }
        }
    }
  }

  def profileConnectionIds(username: Username, limit: Int) = MaybeUserAction.async { request =>
    userCommander.userFromUsername(username) match {
      case None =>
        log.warn(s"can't find username ${username.value}")
        Future.successful(NotFound(s"username ${username.value}"))
      case Some(user) =>
        val viewerId = request.userIdOpt.getOrElse(user.id.get)
        userConnectionsCommander.getConnectionsSortedByRelationship(viewerId, user.id.get) map { connections =>
          val userMap = db.readOnlyMaster { implicit s =>
            basicUserRepo.loadAll(connections.take(limit).map(_.userId).toSet)
          }
          val ids = connections.flatMap(u => userMap.get(u.userId)).map(_.externalId)
          Ok(Json.obj("ids" -> ids))
        }
    }
  }

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
    val (success, code) = userConnectionsCommander.friend(request.userId, id)
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

  def currentUser = UserAction.async { implicit request =>
    getUserInfo(request.userId)
  }

  def changePassword = UserAction(parse.tolerantJson) { implicit request =>
    val oldPasswordOpt = (request.body \ "oldPassword").asOpt[String] // todo: use char[]
    val newPassword = (request.body \ "newPassword").as[String]
    if (newPassword.length < 7) {
      BadRequest(Json.obj("error" -> "bad_new_password"))
    } else {
      userCommander.doChangePassword(request.userId, oldPasswordOpt, newPassword) match {
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
      emailRepo.getByAddressOpt(email) match {
        case Some(emailRecord) =>
          val pendingPrimary = userValueRepo.getValueStringOpt(request.user.id.get, UserValueName.PENDING_PRIMARY_EMAIL)
          if (emailRecord.userId == request.userId) {
            Ok(Json.toJson(EmailInfo(
              address = emailRecord.address,
              isVerified = emailRecord.verified,
              isPrimary = request.user.primaryEmail.isDefined && request.user.primaryEmail.get == emailRecord.address,
              isPendingPrimary = pendingPrimary.isDefined && pendingPrimary.get == emailRecord.address
            )))
          } else {
            Forbidden(Json.obj("error" -> "email_belongs_to_other_user"))
          }
        case None =>
          Ok(Json.obj("status" -> "available"))
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

  def updateDescription() = UserAction(parse.tolerantJson) { implicit request =>
    val newDescription = (request.body \ "description").as[String]
    userCommander.updateUserDescription(request.userId, newDescription)
    Ok(JsString("success"))
  }

  def addEmail() = UserAction.async(parse.tolerantJson) { implicit request =>
    val newAddress = (request.body \ "email").as[String]
    val isPrimary = (request.body \ "isPrimary").as[Boolean]
    EmailAddress.validate(newAddress) match {
      case Failure(e) =>
        Future.successful(BadRequest(e.getMessage))
      case Success(newEmail) =>
        userCommander.addEmail(request.userId, newEmail, isPrimary) map {
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
        userCommander.makeEmailPrimary(request.userId, targetEmail) match {
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
        userCommander.removeEmail(request.userId, targetEmail) match {
          case Left(s) => BadRequest(s)
          case Right(_) => Ok(JsString("success"))
        }
    }
  }

  //private val emailRegex = """^[a-zA-Z0-9.!#$%&'*+\/=?^_`{|}~-]+@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$""".r
  @deprecated(message = "use addEmail/modifyEmail/removeEmail", since = "2014-08-20")
  def updateCurrentUser() = UserAction.async(parse.tolerantJson) { implicit request =>
    request.body.validate[UpdatableUserInfo] match {
      case JsSuccess(userData, _) => {
        userCommander.updateUserInfo(request.userId, userData)
        getUserInfo(request.userId)
      }
      case JsError(errors) if errors.exists { case (path, _) => path == __ \ "emails" } =>
        Future.successful(BadRequest(Json.obj("error" -> "bad email addresses")))
      case _ =>
        Future.successful(BadRequest(Json.obj("error" -> "could not parse user info from body")))
    }
  }

  private def getUserInfo[T](userId: Id[User]) = {
    val user = db.readOnlyMaster { implicit session =>
      userRepo.get(userId)
    }
    val experiments = userExperimentCommander.getExperimentsByUser(userId)
    val pimpedUser = userCommander.getUserInfo(user)
    val json = toJson(pimpedUser.basicUser).as[JsObject] ++
      toJson(pimpedUser.info).as[JsObject] ++
      Json.obj("notAuthed" -> pimpedUser.notAuthed).as[JsObject] ++
      Json.obj("experiments" -> experiments.map(_.value))
    userCommander.getKeepAttributionInfo(userId) map { info =>
      Ok(json ++ Json.obj(
        "uniqueKeepsClicked" -> info.uniqueKeepsClicked,
        "totalKeepsClicked" -> info.totalClicks,
        "clickCount" -> info.clickCount,
        "rekeepCount" -> info.rekeepCount,
        "rekeepTotalCount" -> info.rekeepTotalCount
      ))
    }
  }

  private val SitePrefNames = {
    import UserValueName._
    Set(
      AUTO_SHOW_GUIDE,
      AUTO_SHOW_PERSONA,
      SHOW_DELIGHTED_QUESTION,
      LIBRARY_SORTING_PREF,
      SITE_INTRODUCE_LIBRARY_MENU,
      HAS_NO_PASSWORD)
  }

  def getPrefs() = UserAction.async { request =>
    // The prefs endpoint is used as an indicator that the user is active
    userCommander.setLastUserActive(request.userId)
    userCommander.getPrefs(SitePrefNames, request.userId, request.experiments) map (Ok(_))
  }

  def savePrefs() = UserAction(parse.tolerantJson) { request =>
    val o = request.request.body.as[JsObject]
    val map = o.value.map(t => UserValueName(t._1) -> t._2).toMap
    val keyNames = map.keys.toSet
    if (keyNames.subsetOf(SitePrefNames)) {
      userCommander.savePrefs(request.userId, map)
      Ok(o)
    } else {
      BadRequest(Json.obj("error" -> ((SitePrefNames -- keyNames).mkString(", ") + " not recognized")))
    }
  }

  def getInviteCounts() = UserAction { request =>
    db.readOnlyMaster { implicit s =>
      val availableInvites = userValueRepo.getValue(request.userId, UserValues.availableInvites)
      val invitesLeft = availableInvites - invitationRepo.getByUser(request.userId).length
      Ok(Json.obj(
        "total" -> availableInvites,
        "left" -> invitesLeft
      )).withHeaders("Cache-Control" -> "private, max-age=300")
    }
  }

  def needMoreInvites() = UserAction { request =>
    db.readWrite { implicit s =>
      postOffice.sendMail(ElectronicMail(
        from = SystemEmailAddress.INVITATION,
        to = Seq(SystemEmailAddress.EISHAY),
        subject = s"${request.user.firstName} ${request.user.lastName} wants more invites.",
        htmlBody = s"Go to https://admin.kifi.com/admin/user/${request.userId} to give more invites.",
        category = NotificationCategory.System.ADMIN))
    }
    Ok
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
      emailRepo.getByAddressOpt(email) match {
        case Some(emailAddr) if emailAddr.userId == request.userId =>
          val emailAddr = emailRepo.save(emailRepo.getByAddressOpt(email).get.withVerificationCode(clock.now))
          emailSender.confirmation(emailAddr) map { f =>
            Ok("0")
          }
        case _ =>
          Future.successful(Forbidden("0"))
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

  // todo(Andrew): Remove when ng is out
  def checkIfImporting(network: String, callback: String) = UserAction { implicit request =>
    val startTime = clock.now
    val importHasHappened = new AtomicBoolean(false)
    val finishedImportAnnounced = new AtomicBoolean(false)
    def check(): Option[JsValue] = {
      val v = db.readOnlyMaster { implicit session =>
        userValueRepo.getValueStringOpt(request.userId, UserValueName.importInProgress(network))
      }
      if (v.isEmpty && clock.now.minusSeconds(20).compareTo(startTime) > 0) {
        None
      } else if (clock.now.minusMinutes(2).compareTo(startTime) > 0) {
        None
      } else if (v.isDefined) {
        if (v.get == "false") {
          if (finishedImportAnnounced.get) None
          else if (importHasHappened.get) {
            finishedImportAnnounced.set(true)
            Some(JsString("finished"))
          } else Some(JsBoolean(v.get.toBoolean))
        } else {
          importHasHappened.set(true)
          Some(JsString(v.get))
        }
      } else {
        Some(JsBoolean(false))
      }
    }
    def poller(): Future[Option[JsValue]] = PlayPromise.timeout(check, 2 seconds)
    def script(msg: JsValue) = Html(s"<script>$callback(${msg.toString});</script>")

    db.readOnlyMaster { implicit session =>
      socialUserRepo.getByUser(request.userId).find(_.networkType.name == network)
    } match {
      case Some(sui) =>
        val firstResponse = Enumerator.enumerate(check().map(script).toSeq)
        val returnEnumerator = Enumerator.generateM(poller)
        Status(200).chunked(firstResponse andThen returnEnumerator &> Comet(callback = callback) andThen Enumerator(script(JsString("end"))) andThen Enumerator.eof)
      case None =>
        Ok(script(JsString("network_not_connected")))
    }
  }

  // todo(Andrew): Remove when ng is out
  // status update -- see ScalaComet & Andrew's gist -- https://gist.github.com/andrewconner/f6333839c77b7a1cf2da
  def getABookUploadStatus(id: Id[ABookInfo], callbackOpt: Option[String]) = UserAction { request =>
    import com.keepit.model.ABookInfoStates._
    val ts = System.currentTimeMillis
    val callback = callbackOpt.getOrElse("parent.updateABookProgress")
    val done = new AtomicBoolean(false)
    def timeoutF = play.api.libs.concurrent.Promise.timeout(None, 500)
    def reqF = abookServiceClient.getABookInfo(request.userId, id) map { abookInfoOpt =>
      log.info(s"[getABookUploadStatus($id)] ... ${abookInfoOpt.map(_.state)}")
      if (done.get) None
      else {
        val (state, numContacts, numProcessed) = abookInfoOpt match {
          case None => ("notAvail", -1, -1)
          case Some(abookInfo) =>
            val resp = (abookInfo.state, abookInfo.numContacts.getOrElse(-1), abookInfo.numProcessed.getOrElse(-1))
            abookInfo.state match {
              case ACTIVE => { done.set(true); resp }
              case UPLOAD_FAILURE => { done.set(true); resp }
              case PENDING => resp
            }
        }
        if ((System.currentTimeMillis - ts) > abookUploadConf.timeoutThreshold * 1000) {
          done.set(true)
          Some(s"<script>$callback($id,'timeout',${numContacts},${numProcessed})</script>")
        } else Some(s"<script>$callback($id,'${state}',${numContacts},${numProcessed})</script>")
      }
    }
    val returnEnumerator = Enumerator.generateM {
      Future.sequence(Seq(timeoutF, reqF)).map { res =>
        res.collect { case Some(s: String) => s }.headOption
      }
    }
    Status(200).chunked(returnEnumerator.andThen(Enumerator.eof))
  }

  def profile(username: Username) = MaybeUserAction { request =>
    val viewer = request.userOpt
    userCommander.profile(username, viewer) match {
      case None =>
        log.warn(s"can't find username ${username.value}")
        NotFound(s"username ${username.value}")
      case Some(profile) =>
        val (numLibraries, numInvitedLibs) = libraryCommander.countLibraries(profile.userId, viewer.map(_.id.get))

        val json = Json.toJson(profile.basicUserWithFriendStatus).as[JsObject] ++ Json.obj(
          "numLibraries" -> numLibraries,
          "numKeeps" -> profile.numKeeps
        )
        numInvitedLibs match {
          case Some(numInvited) =>
            Ok(json ++ Json.obj("numInvitedLibraries" -> numInvited))
          case _ =>
            Ok(json)
        }
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
