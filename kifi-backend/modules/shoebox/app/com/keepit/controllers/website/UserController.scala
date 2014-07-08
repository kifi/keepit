package com.keepit.controllers.website

import com.google.inject.Inject
import com.keepit.common.controller.{ShoeboxServiceController, ActionAuthenticator, WebsiteController}
import com.keepit.common.db.{Id, ExternalId}
import com.keepit.commanders.ConnectionInfo
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.db.slick._
import com.keepit.common.mail._
import com.keepit.common.performance.timing
import com.keepit.common.social.BasicUserRepo
import com.keepit.controllers.core.NetworkInfoLoader
import com.keepit.commanders._
import com.keepit.model._
import play.api.libs.json.Json.toJson
import com.keepit.abook.{ABookUploadConf, ABookServiceClient}
import scala.concurrent.Future
import scala.concurrent.duration._
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.concurrent.{Promise => PlayPromise}
import play.api.libs.Comet
import com.keepit.common.time._
import play.api.templates.Html
import play.api.libs.iteratee.Enumerator
import play.api.Play.current
import java.util.concurrent.atomic.AtomicBoolean
import com.keepit.eliza.ElizaServiceClient
import play.api.mvc.{Request, MaxSizeExceeded}
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.store.{ImageCropAttributes, S3ImageStore}
import play.api.data.Form
import play.api.data.Forms._
import play.api.libs.json._
import scala.util.{Failure, Success}
import com.keepit.model.UserEmailAddress
import play.api.libs.json.JsString
import play.api.libs.json.JsBoolean
import scala.Some
import play.api.libs.json.JsUndefined
import play.api.libs.json.JsNumber
import com.keepit.common.mail.EmailAddress
import play.api.libs.json.JsObject
import com.keepit.search.SearchServiceClient
import com.keepit.inject.FortyTwoConfig

class UserController @Inject() (
  db: Database,
  userRepo: UserRepo,
  userExperimentCommander: LocalUserExperimentCommander,
  basicUserRepo: BasicUserRepo,
  userConnectionRepo: UserConnectionRepo,
  emailRepo: UserEmailAddressRepo,
  userValueRepo: UserValueRepo,
  socialConnectionRepo: SocialConnectionRepo,
  socialUserRepo: SocialUserInfoRepo,
  invitationRepo: InvitationRepo,
  networkInfoLoader: NetworkInfoLoader,
  actionAuthenticator: ActionAuthenticator,
  friendRequestRepo: FriendRequestRepo,
  postOffice: LocalPostOffice,
  userCommander: UserCommander,
  elizaServiceClient: ElizaServiceClient,
  clock: Clock,
  s3ImageStore: S3ImageStore,
  abookServiceClient: ABookServiceClient,
  airbrakeNotifier: AirbrakeNotifier,
  authCommander: AuthCommander,
  searchClient: SearchServiceClient,
  abookUploadConf: ABookUploadConf,
  fortytwoConfig: FortyTwoConfig
) extends WebsiteController(actionAuthenticator) with ShoeboxServiceController {

  def friends(page: Int, pageSize: Int) = JsonAction.authenticated { request =>
    val (connectionsPage, total) = userCommander.getConnectionsPage(request.userId, page, pageSize)
    val friendsJsons = db.readOnlyMaster { implicit s =>
      connectionsPage.map { case ConnectionInfo(friend, friendId, unfriended, unsearched) =>
        Json.toJson(friend).asInstanceOf[JsObject] ++ Json.obj(
          "searchFriend" -> unsearched,
          "unfriended" -> unfriended,
          "friendCount" -> userConnectionRepo.getConnectionCount(friendId)
        )
      }
    }
    Ok(Json.obj(
      "friends" -> friendsJsons,
      "total" -> total
    ))
  }

  def friendCount() = JsonAction.authenticated { request =>
    db.readOnlyMaster { implicit s =>
      Ok(Json.obj(
        "friends" -> userConnectionRepo.getConnectionCount(request.userId),
        "requests" -> friendRequestRepo.getCountByRecipient(request.userId)
      ))
    }
  }

  def socialNetworkInfo() = JsonAction.authenticated { request =>
    Ok(Json.toJson(userCommander.socialNetworkInfo(request.userId)))
  }

  def abookInfo() = JsonAction.authenticatedAsync { request =>
    val abookF = abookServiceClient.getABookInfos(request.userId)
    abookF.map { abooks =>
      Ok(Json.toJson(abooks.map(ExternalABookInfo.fromABookInfo _)))
    }
  }

  def friendNetworkInfo(id: ExternalId[User]) = JsonAction.authenticated { request =>
    Ok(toJson(networkInfoLoader.load(request.userId, id)))
  }

  def unfriend(id: ExternalId[User]) = JsonAction.authenticated { request =>
    if (userCommander.unfriend(request.userId, id)) {
      Ok(Json.obj("removed" -> true))
    } else {
      NotFound(Json.obj("error" -> s"User with id $id not found."))
    }
  }

  def friend(id: ExternalId[User]) = JsonAction.authenticated { request =>
    val (success, code) = userCommander.friend(request.userId, id)
    if (success) {
      Ok(Json.obj("success" -> true, code -> true))
    } else {
      NotFound(Json.obj("error" -> code))
    }
  }

  def ignoreFriendRequest(id: ExternalId[User]) = JsonAction.authenticated { request =>
    val (success, code) = userCommander.ignoreFriendRequest(request.userId, id)
    if (success) Ok(Json.obj("success" -> true))
    else if (code == "friend_request_not_found") NotFound(Json.obj("error" -> s"There is no active friend request from user $id."))
    else if (code == "user_not_found") BadRequest(Json.obj("error" -> s"User with id $id not found."))
    else BadRequest(Json.obj("error" -> code))
  }

  def cancelFriendRequest(id: ExternalId[User]) = JsonAction.authenticated { request =>
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

  def incomingFriendRequests = JsonAction.authenticated { request =>
    val users = userCommander.incomingFriendRequests(request.userId)
    Ok(Json.toJson(users))
  }

  def outgoingFriendRequests = JsonAction.authenticated { request =>
    val users = userCommander.outgoingFriendRequests(request.userId)
    Ok(Json.toJson(users))
  }

  def excludeFriend(id: ExternalId[User]) = JsonAction.authenticated { request =>
    userCommander.excludeFriend(request.userId, id) map { changed =>
      Ok(Json.obj("changed" -> changed))
    } getOrElse {
      BadRequest(Json.obj("error" -> s"You are not friends with user $id"))
    }
  }

  def includeFriend(id: ExternalId[User]) = JsonAction.authenticated { request =>
    userCommander.includeFriend(request.userId, id) map { changed =>
      Ok(Json.obj("changed" -> changed))
    } getOrElse {
      BadRequest(Json.obj("error" -> s"You are not friends with user $id"))
    }
  }

  def currentUser = JsonAction.authenticated(allowPending = true) { implicit request =>
    getUserInfo(request.userId)
  }

  def changePassword = JsonAction.authenticatedParseJson(allowPending = true) { implicit request =>
    val oldPassword = (request.body \ "oldPassword").as[String] // todo: use char[]
    val newPassword = (request.body \ "newPassword").as[String]
    if (newPassword.length < 7) {
      BadRequest(Json.obj("error" -> "bad_new_password"))
    } else {
      userCommander.doChangePassword(request.userId, oldPassword, newPassword) match {
        case Failure(e)  => Forbidden(Json.obj("error" -> e.getMessage))
        case Success(_) => Ok(Json.obj("success" -> true))
      }
    }
  }

  def getEmailInfo(email: EmailAddress) = JsonAction.authenticated(allowPending = true) { implicit request =>
    db.readOnlyMaster { implicit session =>
      emailRepo.getByAddressOpt(email) match {
        case Some(emailRecord) =>
          val pendingPrimary = userValueRepo.getValueStringOpt(request.user.id.get, "pending_primary_email")
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


  //private val emailRegex = """^[a-zA-Z0-9.!#$%&'*+\/=?^_`{|}~-]+@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$""".r
  def updateCurrentUser() = JsonAction.authenticatedParseJson(allowPending = true) { implicit request =>
    request.body.validate[UpdatableUserInfo] match {
      case JsSuccess(userData, _) => {
        userData.emails.foreach(userCommander.updateEmailAddresses(request.userId, request.user.firstName, request.user.primaryEmail, _))
        userData.description.foreach{ description =>
          userCommander.updateUserDescription(request.userId, description)
        }
        getUserInfo(request.userId)
      }
      case JsError(errors) if errors.exists { case (path, _) => path == __ \ "emails" } => BadRequest(Json.obj("error" -> "bad email addresses"))
      case _ => BadRequest(Json.obj("error" -> "could not parse user info from body"))
    }
  }

  private def getUserInfo[T](userId: Id[User]) = {
    val user = db.readOnlyMaster { implicit session => userRepo.get(userId) }
    val experiments = userExperimentCommander.getExperimentsByUser(userId)
    val pimpedUser = userCommander.getUserInfo(user)
    val json = toJson(pimpedUser.basicUser).as[JsObject] ++
       toJson(pimpedUser.info).as[JsObject] ++
       Json.obj("notAuthed" -> pimpedUser.notAuthed).as[JsObject] ++
       Json.obj("experiments" -> experiments.map(_.value))
    val (uniqueKeepsClicked, totalClicks) = userCommander.getHelpCounts(userId)
    val (clickCount, rekeepCount, rekeepTotalCount) = userCommander.getKeepAttributionCounts(userId)
    Ok(json ++ Json.obj(
      "uniqueKeepsClicked" -> uniqueKeepsClicked,
      "totalKeepsClicked" -> totalClicks,
      "clickCount" -> clickCount,
      "rekeepCount" -> rekeepCount,
      "rekeepTotalCount" -> rekeepTotalCount
    ))
  }

  private val SitePrefNames = Set("site_left_col_width", "site_welcomed", "onboarding_seen", "show_delighted_question")

  def getPrefs() = JsonAction.authenticatedAsync { request =>
    // Make sure the user's last active date has been updated before returning the result
    userCommander.setLastUserActive(request.userId) map { _ =>
      Ok(userCommander.getPrefs(SitePrefNames, request.userId))
    } recover {
      // todo(martin) - Remove this. This is to make sure I don't break prod for the moment
      case t: Throwable => {
        airbrakeNotifier.notify(s"Exception occurred in setLastUserActive for user ${request.userId}", t)
        Ok(userCommander.getPrefs(SitePrefNames, request.userId))
      }
    }
  }

  def savePrefs() = JsonAction.authenticatedParseJson { request =>
    val o = request.request.body.as[JsObject]
    if (o.keys.subsetOf(SitePrefNames)) {
      userCommander.savePrefs(SitePrefNames, request.userId, o)
      Ok(o)
    } else {
      BadRequest(Json.obj("error" -> ((SitePrefNames -- o.keys).mkString(", ") + " not recognized")))
    }
  }

  def getInviteCounts() = JsonAction.authenticated { request =>
    db.readOnlyMaster { implicit s =>
      val availableInvites = userValueRepo.getValue(request.userId, UserValues.availableInvites)
      val invitesLeft = availableInvites - invitationRepo.getByUser(request.userId).length
      Ok(Json.obj(
        "total" -> availableInvites,
        "left" -> invitesLeft
      )).withHeaders("Cache-Control" -> "private, max-age=300")
    }
  }

  def needMoreInvites() = JsonAction.authenticated { request =>
    db.readWrite { implicit s =>
      postOffice.sendMail(ElectronicMail(
        from = SystemEmailAddress.INVITATION,
        to = Seq(SystemEmailAddress.EFFI),
        subject = s"${request.user.firstName} ${request.user.lastName} wants more invites.",
        htmlBody = s"Go to https://admin.kifi.com/admin/user/${request.userId} to give more invites.",
        category = NotificationCategory.System.ADMIN))
    }
    Ok
  }

  def uploadBinaryUserPicture() = JsonAction(allowPending = true, parser = parse.maxLength(1024*1024*15, parse.temporaryFile))(authenticatedAction = doUploadBinaryUserPicture(_), unauthenticatedAction = doUploadBinaryUserPicture(_))
  def doUploadBinaryUserPicture(implicit request: Request[Either[MaxSizeExceeded,play.api.libs.Files.TemporaryFile]]) = {
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
  def setUserPicture() = JsonAction.authenticated(allowPending = true) { implicit request =>
    userPicForm.bindFromRequest.fold(
    formWithErrors => BadRequest(Json.obj("error" -> formWithErrors.errors.head.message)),
    { case UserPicInfo(picToken, picHeight, picWidth, cropX, cropY, cropSize) =>
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
  def resendVerificationEmail(email: EmailAddress) = HtmlAction.authenticated { implicit request =>
    db.readWrite { implicit s =>
      emailRepo.getByAddressOpt(email) match {
        case Some(emailAddr) if emailAddr.userId == request.userId =>
          val emailAddr = emailRepo.save(emailRepo.getByAddressOpt(email).get.withVerificationCode(clock.now))
          val verifyUrl = s"$url${com.keepit.controllers.core.routes.AuthController.verifyEmail(emailAddr.verificationCode.get)}"
          postOffice.sendMail(ElectronicMail(
            from = SystemEmailAddress.NOTIFICATIONS,
            to = Seq(email),
            subject = "Kifi.com | Please confirm your email address",
            htmlBody = views.html.email.verifyEmail(request.user.firstName, verifyUrl).body,
            category = NotificationCategory.User.EMAIL_CONFIRMATION
          ))
          Ok("0")
        case _ =>
          Forbidden("0")
      }
    }
    Ok
  }

  // todo(ray):removeme
  def getAllConnections(search: Option[String], network: Option[String], after: Option[String], limit: Int) = JsonAction.authenticatedAsync {  request =>
    userCommander.getAllConnections(request.userId, search, network, after, limit) map { r =>
      Ok(Json.toJson(r))
    }
  }

  def importStatus() = JsonAction.authenticatedAsync { implicit request =>
    val networks = Seq("facebook", "linkedin")

    val networkStatuses = Future {
      JsObject(db.readOnlyMaster { implicit session =>
        networks.map { network =>
          userValueRepo.getValueStringOpt(request.userId, s"import_in_progress_${network}").flatMap { r =>
            if (r == "false") {
              None
            } else {
              Some(network -> JsString(r))
            }
          }
        }
      }.flatten)
    }

    val abookStatuses = abookServiceClient.getABookInfos(request.userId).map { abooks =>
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

  // todo(Andrew): Remove when ng is out
  def checkIfImporting(network: String, callback: String) = HtmlAction.authenticated { implicit request =>
    val startTime = clock.now
    val importHasHappened = new AtomicBoolean(false)
    val finishedImportAnnounced = new AtomicBoolean(false)
    def check(): Option[JsValue] = {
      val v = db.readOnlyMaster { implicit session =>
        userValueRepo.getValueStringOpt(request.userId, s"import_in_progress_${network}")
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
          }
          else Some(JsBoolean(v.get.toBoolean))
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
        Status(200).chunked(firstResponse andThen returnEnumerator &> Comet(callback = callback) andThen Enumerator(script(JsString("end"))) andThen Enumerator.eof )
      case None =>
        Ok(script(JsString("network_not_connected")))
    }
  }

  // todo(Andrew): Remove when ng is out
  // status update -- see ScalaComet & Andrew's gist -- https://gist.github.com/andrewconner/f6333839c77b7a1cf2da
  def getABookUploadStatus(id:Id[ABookInfo], callbackOpt:Option[String]) = HtmlAction.authenticated { request =>
    import ABookInfoStates._
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
         res.collect { case Some(s:String) => s }.headOption
      }
    }
    Status(200).chunked(returnEnumerator.andThen(Enumerator.eof))
  }
}
