package com.keepit.controllers.website

import java.text.Normalizer

import com.google.inject.Inject
import com.keepit.common.controller.{ActionAuthenticator, WebsiteController}
import com.keepit.common.db.{Id, ExternalId}
import com.keepit.common.db.slick.DBSession.{RWSession, RSession}
import com.keepit.common.db.slick._
import com.keepit.common.mail._
import com.keepit.common.social.BasicUserRepo
import com.keepit.controllers.core.NetworkInfoLoader
import com.keepit.commanders._
import com.keepit.model._
import play.api.libs.json.Json.toJson
import com.keepit.abook.ABookServiceClient
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.concurrent.{Promise => PlayPromise}
import play.api.libs.Comet
import com.keepit.common.time._
import play.api.templates.Html
import play.api.libs.iteratee.Enumerator
import play.api.Play.current

import java.util.concurrent.atomic.AtomicBoolean
import play.api.Play
import com.keepit.social.SocialNetworks
import com.keepit.eliza.ElizaServiceClient
import play.api.mvc.{MaxSizeExceeded, Request}
import com.keepit.common.healthcheck.{AirbrakeNotifier, AirbrakeError}
import com.keepit.common.store.{ImageCropAttributes, S3ImageStore}
import play.api.data.Form
import play.api.data.Forms._
import com.keepit.model.SocialConnection
import scala.util.Failure
import com.keepit.model.EmailAddress
import play.api.libs.json._
import scala.util.Success
import com.keepit.common.controller.AuthenticatedRequest
import com.keepit.model.SocialConnection
import scala.util.Failure
import com.keepit.model.EmailAddress
import play.api.libs.json.JsString
import play.api.libs.json.JsBoolean
import scala.Some
import com.keepit.common.mail.ElectronicMailCategory
import play.api.libs.json.JsArray
import play.api.mvc.MaxSizeExceeded
import play.api.libs.json.JsNumber
import scala.util.Success
import com.keepit.common.mail.GenericEmailAddress
import play.api.libs.json.JsObject

class UserController @Inject() (
  db: Database,
  userRepo: UserRepo,
  userExperimentRepo: UserExperimentRepo,
  basicUserRepo: BasicUserRepo,
  userConnectionRepo: UserConnectionRepo,
  emailRepo: EmailAddressRepo,
  userValueRepo: UserValueRepo,
  socialConnectionRepo: SocialConnectionRepo,
  socialUserRepo: SocialUserInfoRepo,
  invitationRepo: InvitationRepo,
  networkInfoLoader: NetworkInfoLoader,
  actionAuthenticator: ActionAuthenticator,
  friendRequestRepo: FriendRequestRepo,
  searchFriendRepo: SearchFriendRepo,
  postOffice: LocalPostOffice,
  userCommander: UserCommander,
  elizaServiceClient: ElizaServiceClient,
  clock: Clock,
  s3ImageStore: S3ImageStore,
  abookServiceClient: ABookServiceClient,
  airbrakeNotifier: AirbrakeNotifier,
  emailAddressRepo: EmailAddressRepo
) extends WebsiteController(actionAuthenticator) {

  def friends() = AuthenticatedJsonAction { request =>
    Ok(Json.obj(
      "friends" -> db.readOnly { implicit s =>
        val searchFriends = searchFriendRepo.getSearchFriends(request.userId)
        val socialUsers = socialUserRepo.getByUser(request.userId)
        val connectionIds = userConnectionRepo.getConnectedUsers(request.userId)
        val unfriendedIds = userConnectionRepo.getUnfriendedUsers(request.userId)
        (connectionIds.map(_ -> false).toSeq ++ unfriendedIds.map(_ -> true).toSeq).map { case (userId, unfriended) =>
          Json.toJson(basicUserRepo.load(userId)).asInstanceOf[JsObject] ++ Json.obj(
            "searchFriend" -> searchFriends.contains(userId),
            "networks" -> networkInfoLoader.load(socialUsers, userId),
            "unfriended" -> unfriended,
            "description" -> userValueRepo.getValue(userId, "user_description"),
            "friendCount" -> userConnectionRepo.getConnectionCount(userId)
          )
        }
      }
    ))
  }

  def friendCount() = AuthenticatedJsonAction { request =>
    db.readOnly { implicit s =>
      Ok(Json.obj(
        "friends" -> userConnectionRepo.getConnectionCount(request.userId),
        "requests" -> friendRequestRepo.getCountByRecipient(request.userId)
      ))
    }
  }

  def socialNetworkInfo() = AuthenticatedJsonAction { request =>
    Ok(Json.toJson(userCommander.socialNetworkInfo(request.userId)))
  }

  def abookInfo() = AuthenticatedJsonAction { request =>
    val abookF = abookServiceClient.getABookInfos(request.userId)
    Async {
      abookF.map { abooks =>
        Ok(Json.toJson(abooks))
      }
    }
  }

  def friendNetworkInfo(id: ExternalId[User]) = AuthenticatedJsonAction { request =>
    Ok(toJson(networkInfoLoader.load(request.userId, id)))
  }

  def unfriend(id: ExternalId[User]) = AuthenticatedJsonAction { request =>
    db.readOnly { implicit s => userRepo.getOpt(id) } map { user =>
      val removed = db.readWrite { implicit s =>
        userConnectionRepo.unfriendConnections(request.userId, user.id.toSet) > 0
      }
      Ok(Json.obj("removed" -> removed))
    } getOrElse {
      NotFound(Json.obj("error" -> s"User with id $id not found."))
    }
  }

  def friend(id: ExternalId[User]) = AuthenticatedJsonAction { request =>
    db.readWrite { implicit s =>
      userRepo.getOpt(id) map { user =>
        if (friendRequestRepo.getBySenderAndRecipient(request.userId, user.id.get).isDefined) {
          Ok(Json.obj("success" -> true, "alreadySent" -> true))
        } else {
          friendRequestRepo.getBySenderAndRecipient(user.id.get, request.userId) map { friendReq =>
            val socialUser1 = socialUserRepo.getByUser(friendReq.senderId).find(_.networkType == SocialNetworks.FORTYTWO)
            val socialUser2 = socialUserRepo.getByUser(friendReq.recipientId).find(_.networkType == SocialNetworks.FORTYTWO)
            for {
              su1 <- socialUser1
              su2 <- socialUser2
            } yield {
              socialConnectionRepo.getConnectionOpt(su1.id.get, su2.id.get) match {
                case Some(sc) =>
                  socialConnectionRepo.save(sc.withState(SocialConnectionStates.ACTIVE))
                case None =>
                  socialConnectionRepo.save(SocialConnection(socialUser1 = su1.id.get, socialUser2 = su2.id.get, state = SocialConnectionStates.ACTIVE))
              }
            }
            userConnectionRepo.addConnections(friendReq.senderId, Set(friendReq.recipientId), requested = true)

            elizaServiceClient.sendToUser(friendReq.senderId, Json.arr("new_friends", Set(basicUserRepo.load(friendReq.recipientId))))
            elizaServiceClient.sendToUser(friendReq.recipientId, Json.arr("new_friends", Set(basicUserRepo.load(friendReq.senderId))))

            Ok(Json.obj("success" -> true, "acceptedRequest" -> true))
          } getOrElse {
            friendRequestRepo.save(FriendRequest(senderId = request.userId, recipientId = user.id.get))
            Ok(Json.obj("success" -> true, "sentRequest" -> true))
          }
        }
      } getOrElse NotFound(Json.obj("error" -> s"User with id $id not found."))
    }
  }

  def ignoreFriendRequest(id: ExternalId[User]) = AuthenticatedJsonAction { request =>
    db.readWrite { implicit s =>
      userRepo.getOpt(id) map { sender =>
        friendRequestRepo.getBySenderAndRecipient(sender.id.get, request.userId) map { friendRequest =>
          friendRequestRepo.save(friendRequest.copy(state = FriendRequestStates.IGNORED))
          Ok(Json.obj("success" -> true))
        } getOrElse NotFound(Json.obj("error" -> s"There is no active friend request from user $id."))
      } getOrElse BadRequest(Json.obj("error" -> s"User with id $id not found."))
    }
  }

  def cancelFriendRequest(id: ExternalId[User]) = AuthenticatedJsonAction { request =>
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

  def incomingFriendRequests = AuthenticatedJsonAction { request =>
    db.readOnly { implicit s =>
      val users = friendRequestRepo.getByRecipient(request.userId) map { fr => basicUserRepo.load(fr.senderId) }
      Ok(Json.toJson(users))
    }
  }

  def outgoingFriendRequests = AuthenticatedJsonAction { request =>
    db.readOnly { implicit s =>
      val users = friendRequestRepo.getBySender(request.userId) map { fr => basicUserRepo.load(fr.recipientId) }
      Ok(Json.toJson(users))
    }
  }

  def excludeFriend(id: ExternalId[User]) = AuthenticatedJsonAction { request =>
    db.readWrite { implicit s =>
      val friendIdOpt = userRepo.getOpt(id) collect {
        case user if userConnectionRepo.getConnectionOpt(request.userId, user.id.get).isDefined => user.id.get
      }
      friendIdOpt map { friendId =>
        val changed = searchFriendRepo.excludeFriend(request.userId, friendId)
        Ok(Json.obj("changed" -> changed))
      } getOrElse {
        BadRequest(Json.obj("error" -> s"You are not friends with user $id"))
      }
    }
  }

  def includeFriend(id: ExternalId[User]) = AuthenticatedJsonAction { request =>
    db.readWrite { implicit s =>
      val friendIdOpt = userRepo.getOpt(id) collect {
        case user if userConnectionRepo.getConnectionOpt(request.userId, user.id.get).isDefined => user.id.get
      }
      friendIdOpt map { friendId =>
        val changed = searchFriendRepo.includeFriend(request.userId, friendId)
        Ok(Json.obj("changed" -> changed))
      } getOrElse {
        BadRequest(Json.obj("error" -> s"You are not friends with user $id"))
      }
    }
  }

  def currentUser = AuthenticatedJsonAction(true) { implicit request =>
    getUserInfo(request.userId)
  }

  def getEmailInfo(email: String) = AuthenticatedJsonAction(allowPending = true) { implicit request =>
    db.readOnly { implicit session =>
      emailRepo.getByAddressOpt(email) match {
        case Some(emailRecord) =>
          val pendingPrimary = userValueRepo.getValue(request.user.id.get, "pending_primary_email")
          if (emailRecord.userId == request.userId) {
            Ok(Json.toJson(EmailInfo(
              address = emailRecord.address,
              isVerified = emailRecord.verified,
              isPrimary = request.user.primaryEmailId.isDefined && request.user.primaryEmailId.get.id == emailRecord.id.get.id,
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

  private val siteUrl = current.configuration.getString("application.baseUrl").get
  private val emailRegex = """^[a-zA-Z0-9.!#$%&'*+\/=?^_`{|}~-]+@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$""".r
  def updateCurrentUser() = AuthenticatedJsonToJsonAction(true) { implicit request =>
    request.body.asOpt[UpdatableUserInfo] map { userData =>
      val hasInvalidEmails = userData.emails.exists { addresses =>
        addresses.map(em => emailRegex.findFirstIn(em.address).isEmpty).contains(true)
      }
      if (hasInvalidEmails) {
        BadRequest(Json.obj("error" -> "bad email addresses"))
      } else {
        db.readWrite { implicit session =>
          val pendingPrimary = userValueRepo.getValue(request.userId, "pending_primary_email")
          for (emails <- userData.emails.map(_.toSet)) {
            val emailStrings = emails.map(_.address)
            val (existing, toRemove) = emailRepo.getAllByUser(request.user.id.get).partition(em => emailStrings contains em.address)
            // Remove missing emails
            for (email <- toRemove) {
              val isPrimary = request.user.primaryEmailId.isDefined && (request.user.primaryEmailId.get == email.id.get)
              val isLast = existing.isEmpty
              val isLastVerified = !existing.exists(em => em != email && em.verified)
              if (!isPrimary && !isLast && !isLastVerified) {
                if (pendingPrimary.isDefined && email.address == pendingPrimary.get) {
                  userValueRepo.clearValue(request.userId, "pending_primary_email")
                }
                emailRepo.save(email.withState(EmailAddressStates.INACTIVE))
              }
            }
            // Add new emails
            for (address <- emailStrings -- existing.map(_.address)) {
              if (emailRepo.getByAddressOpt(address).isEmpty) {
                val emailAddr = emailAddressRepo.save(EmailAddress(userId = request.userId, address = address).withVerificationCode(clock.now))
                val verifyUrl = s"$siteUrl${com.keepit.controllers.core.routes.AuthController.verifyEmail(emailAddr.verificationCode.get)}"

                postOffice.sendMail(ElectronicMail(
                  from = EmailAddresses.NOTIFICATIONS,
                  to = Seq(GenericEmailAddress(address)),
                  subject = "Kifi.com | Please confirm your email address",
                  htmlBody = views.html.email.verifyEmail(request.user.firstName, verifyUrl).body,
                  category = ElectronicMailCategory("email_confirmation")
                ))
              }
            }
            // Set the correct email as primary
            for (emailInfo <- emails) {
              if (emailInfo.isPrimary || emailInfo.isPendingPrimary) {
                val emailRecordOpt = emailRepo.getByAddressOpt(emailInfo.address)
                emailRecordOpt.collect { case emailRecord if emailRecord.userId == request.user.id.get =>
                  if (emailRecord.verified) {
                    if (request.user.primaryEmailId.isEmpty || request.user.primaryEmailId.get != emailRecord.id.get) {
                      updateUserPrimaryEmail(request.userId, emailRecord.id.get)
                    }
                  } else {
                    userValueRepo.setValue(request.userId, "pending_primary_email", emailInfo.address)
                  }
                }
              }
            }
          }
          userValueRepo.getValue(request.userId, "pending_primary_email").map { pp =>
            emailRepo.getByAddressOpt(pp) match {
              case Some(em) =>
                if (em.verified && em.address == pp) {
                  updateUserPrimaryEmail(request.userId, em.id.get)
                }
              case None => userValueRepo.clearValue(request.userId, "pending_primary_email")
            }
          }
          userData.description foreach { description =>
            val trimmed = description.trim
            if (trimmed != "") {
              userValueRepo.setValue(request.userId, "user_description", trimmed)
            } else {
              userValueRepo.clearValue(request.userId, "user_description")
            }
          }
          // Users cannot change their name for now. When we're ready, use the code below:
//          if ((userData.firstName.isDefined && userData.firstName.get.trim != "") || (userData.lastName.isDefined && userData.lastName.get.trim != "")) {
//            val user = userRepo.get(request.userId)
//            val cleanFirst = User.sanitizeName(userData.firstName getOrElse user.firstName)
//            val cleanLast = User.sanitizeName(userData.lastName getOrElse user.lastName)
//            val updatedUser = user.copy(firstName = cleanFirst, lastName = cleanLast)
//            userRepo.save(updatedUser)
//          }
        }
        getUserInfo(request.userId)
      }
    } getOrElse {
      BadRequest(Json.obj("error" -> "could not parse user info from body"))
    }
  }

  private def updateUserPrimaryEmail(userId: Id[User], emailId: Id[EmailAddress])(implicit session: RWSession) = {
    userValueRepo.clearValue(userId, "pending_primary_email")
    val currentUser = userRepo.get(userId)
    userRepo.save(currentUser.copy(primaryEmailId = Some(emailId)))
  }

  private def getUserInfo[T](userId: Id[User]) = {
    val (user, experiments) = db.readOnly { implicit session =>
      (userRepo.get(userId), userExperimentRepo.getUserExperiments(userId))
    }
    val pimpedUser = userCommander.getUserInfo(user)
    Ok(toJson(pimpedUser.basicUser).as[JsObject] ++
       toJson(pimpedUser.info).as[JsObject] ++
       Json.obj("experiments" -> experiments.map(_.value)))
  }

  private val SitePrefNames = Set("site_left_col_width", "site_welcomed")
  private val DynamicSitePrefNames = Set("do_not_import")

  def getPrefs() = AuthenticatedJsonAction { request =>
    Ok(db.readOnly { implicit s =>
      val shouldPromptForImport = request.kifiInstallationId match {
        case Some(inst) =>
          val pref = userValueRepo.getValue(request.userId, "has_imported_from_" + inst)
          if (pref.isDefined && pref.get == "false") true
          else false
        case None => false
      }
      JsObject(SitePrefNames.toSeq.map { name =>
        name -> userValueRepo.getValue(request.userId, name).map(JsString).getOrElse(JsNull)
      } ++ Seq("prompt_for_import" -> JsBoolean(shouldPromptForImport)))
    })
  }

  def savePrefs() = AuthenticatedJsonToJsonAction { request =>
    val o = request.request.body.as[JsObject]
    if (o.keys.subsetOf(SitePrefNames ++ DynamicSitePrefNames)) {
      db.readWrite { implicit s =>
        o.fields.foreach { case (name, value) =>
          if (value == JsNull || value == JsUndefined) {
            userValueRepo.clearValue(request.userId, name)
          } else if (name == "do_not_import" && request.kifiInstallationId.isDefined) {
            // User selected not to import Léo
            userValueRepo.setValue(request.userId, "has_imported_from_" + request.kifiInstallationId.get, "opt_out")
          } else {
            userValueRepo.setValue(request.userId, name, value.as[String])
          }
        }
      }
      Ok(o)
    } else {
      BadRequest(Json.obj("error" -> ((SitePrefNames -- o.keys).mkString(", ") + " not recognized")))
    }
  }

  def getInviteCounts() = AuthenticatedJsonAction { request =>
    db.readOnly { implicit s =>
      val availableInvites = userValueRepo.getValue(request.userId, "availableInvites").map(_.toInt).getOrElse(20)
      val invitesLeft = availableInvites - invitationRepo.getByUser(request.userId).length
      Ok(Json.obj(
        "total" -> availableInvites,
        "left" -> invitesLeft
      )).withHeaders("Cache-Control" -> "private, max-age=300")
    }
  }

  def needMoreInvites() = AuthenticatedJsonAction { request =>
    db.readWrite { implicit s =>
      postOffice.sendMail(ElectronicMail(
        from = EmailAddresses.INVITATION,
        to = Seq(EmailAddresses.EFFI),
        subject = s"${request.user.firstName} ${request.user.lastName} wants more invites.",
        htmlBody = s"Go to https://admin.kifi.com/admin/user/${request.userId} to give more invites.",
        category = PostOffice.Categories.User.INVITATION))
    }
    Ok
  }

  @inline def normalize(str: String) = Normalizer.normalize(str, Normalizer.Form.NFD).replaceAll("\\p{InCombiningDiacriticalMarks}+", "").toLowerCase

  val queryWithABook = sys.props.getOrElse("query.contacts.abook", "true").toBoolean
  private def queryContacts(userId:Id[User], search: Option[String], after:Option[String], limit: Int):Future[Seq[JsObject]] = { // TODO: optimize
    @inline def mkId(email:String) = s"email/$email"
    val searchTerms = search.toSeq.map(_.split("[@\\s+]")).flatten.filterNot(_.isEmpty).map(normalize)
    @inline def searchScore(s: String): Int = {
      if (s.isEmpty) 0
      else if (searchTerms.isEmpty) 1
      else {
        val name = normalize(s)
        if (searchTerms.exists(!name.contains(_))) 0
        else {
          val names = name.split("\\s+").filterNot(_.isEmpty)
          names.count(n => searchTerms.exists(n.startsWith))*2 +
            names.count(n => searchTerms.exists(n.contains)) +
            (if (searchTerms.exists(name.startsWith)) 1 else 0)
        }
      }
    }
    @inline def getEInviteStatus(contactIdOpt:Option[Id[EContact]]):String = { // todo: batch
      contactIdOpt flatMap { contactId =>
        db.readOnly { implicit s =>
          invitationRepo.getBySenderIdAndRecipientEContactId(userId, contactId) map { inv =>
            if (inv.state != InvitationStates.INACTIVE) "invited" else ""
          }
        }
      } getOrElse ""
    }

    val pagedF = if (queryWithABook) abookServiceClient.queryEContacts(userId, limit, search, after)
    else abookServiceClient.getEContacts(userId, 40000000).map { contacts =>
      val filtered = contacts.filter(e => ((searchScore(e.name.getOrElse("")) > 0) || (searchScore(e.email) > 0)))
      val paged = after match {
        case Some(a) => filtered.dropWhile(e => (mkId(e.email) != a)) match {
          case hd +: tl => tl
          case tl => tl
        }
        case None => filtered
      }
      paged
    }

    pagedF.map { paged =>
      val objs = paged.take(limit).map { e =>
        Json.obj("label" -> JsString(e.name.getOrElse("")), "value" -> mkId(e.email), "status" -> getEInviteStatus(e.id))
      }
      log.info(s"[queryContacts(id=$userId)] res(len=${objs.length}):${objs.mkString.take(200)}")
      objs
    }
  }

  def uploadBinaryUserPicture() = JsonAction(allowPending = true, parser = parse.maxLength(1024*1024*15, parse.temporaryFile))(authenticatedAction = doUploadBinaryUserPicture(_), unauthenticatedAction = doUploadBinaryUserPicture(_))
  def doUploadBinaryUserPicture(implicit request: Request[Either[MaxSizeExceeded,play.api.libs.Files.TemporaryFile]]) = {
    request.body match {
      case Right(tempFile) =>
        s3ImageStore.uploadTemporaryPicture(tempFile.file) match {
          case Success((token, pictureUrl)) =>
            Ok(Json.obj("token" -> token, "url" -> pictureUrl))
          case Failure(ex) =>
            airbrakeNotifier.notify(AirbrakeError(ex, Some("Couldn't upload temporary picture (xhr direct)")))
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
  def setUserPicture() = AuthenticatedJsonAction(allowPending = false) { implicit request =>
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

  private val url = current.configuration.getString("application.baseUrl").get
  def resendVerificationEmail(email: String) = AuthenticatedHtmlAction { implicit request =>
    db.readWrite { implicit s =>
      emailAddressRepo.getByAddressOpt(email) match {
        case Some(emailAddr) if emailAddr.userId == request.userId =>
          val emailAddr = emailAddressRepo.save(emailAddressRepo.getByAddressOpt(email).get.withVerificationCode(clock.now))
          val verifyUrl = s"$url${com.keepit.controllers.core.routes.AuthController.verifyEmail(emailAddr.verificationCode.get)}"
          postOffice.sendMail(ElectronicMail(
            from = EmailAddresses.NOTIFICATIONS,
            to = Seq(GenericEmailAddress(email)),
            subject = "Kifi.com | Please confirm your email address",
            htmlBody = views.html.email.verifyEmail(request.user.firstName, verifyUrl).body,
            category = ElectronicMailCategory("email_confirmation")
          ))
          Ok("0")
        case _ =>
          Forbidden("0")
      }
    }
    Ok
  }

  def getAllConnections(search: Option[String], network: Option[String], after: Option[String], limit: Int) = AuthenticatedJsonAction {  request =>
    val contactsF = if (network.isDefined && network.get == "email") { // todo: revisit
      queryContacts(request.userId, search, after, limit)
    } else Future.successful(Seq.empty[JsObject])
    @inline def socialIdString(sci: SocialConnectionInfo) = s"${sci.networkType}/${sci.socialId.id}"
    val searchTerms = search.toSeq.map(_.split("\\s+")).flatten.filterNot(_.isEmpty).map(normalize)
    @inline def searchScore(sci: SocialConnectionInfo): Int = {
      if (network.exists(sci.networkType.name !=)) 0
      else if (searchTerms.isEmpty) 1
      else {
        val name = normalize(sci.fullName)
        if (searchTerms.exists(!name.contains(_))) 0
        else {
          val names = name.split("\\s+").filterNot(_.isEmpty)
          names.count(n => searchTerms.exists(n.startsWith))*2 +
              names.count(n => searchTerms.exists(n.contains)) +
              (if (searchTerms.exists(name.startsWith)) 1 else 0)
        }
      }
    }

    def getWithInviteStatus(sci: SocialConnectionInfo)(implicit s: RSession): (SocialConnectionInfo, String) =
      sci -> sci.userId.map(_ => "joined").getOrElse {
        invitationRepo.getByRecipientSocialUserId(sci.id) collect {
          case inv if inv.state != InvitationStates.INACTIVE => "invited"
        } getOrElse ""
      }

    def getFilteredConnections(sui: SocialUserInfo)(implicit s: RSession): Seq[SocialConnectionInfo] =
      if (sui.networkType == SocialNetworks.FORTYTWO) Nil
      else socialConnectionRepo.getSocialConnectionInfo(sui.id.get) filter (searchScore(_) > 0)

    val connections = db.readOnly { implicit s =>
      val filteredConnections = socialUserRepo.getByUser(request.userId)
        .flatMap(getFilteredConnections)
        .sortBy { case sui => (-searchScore(sui), normalize(sui.fullName)) }

      (after match {
        case Some(id) => filteredConnections.dropWhile(socialIdString(_) != id) match {
          case hd +: tl => tl
          case tl => tl
        }
        case None => filteredConnections
      }).take(limit).map(getWithInviteStatus)
    }

    val jsConns: Seq[JsObject] = connections.map { conn =>
      Json.obj(
        "label" -> conn._1.fullName,
        "image" -> toJson(conn._1.getPictureUrl(75, 75)),
        "value" -> socialIdString(conn._1),
        "status" -> conn._2
      )
    }
    val jsContacts: Seq[JsObject] = Await.result(contactsF, 10 seconds)
    val jsCombined = jsConns ++ jsContacts
    log.info(s"[getAllConnections(${request.userId})] jsContacts(sz=${jsContacts.size}) jsConns(sz=${jsConns.size})")
    val jsArray = JsArray(jsCombined)
    Ok(jsArray).withHeaders("Cache-Control" -> "private, max-age=300")
  }

  // todo: Combine this and next (abook import)
  def checkIfImporting(network: String, callback: String) = AuthenticatedHtmlAction { implicit request =>
    val startTime = clock.now
    var importHasHappened = new AtomicBoolean(false)
    var finishedImportAnnounced = new AtomicBoolean(false)
    def check(): Option[JsValue] = {
      val v = db.readOnly { implicit session =>
        userValueRepo.getValue(request.userId, s"import_in_progress_${network}")
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

    db.readOnly { implicit session =>
      socialUserRepo.getByUser(request.userId).find(_.networkType.name == network)
    } match {
      case Some(sui) =>
        val firstResponse = Enumerator.enumerate(check().map(script).toSeq)
        val returnEnumerator = Enumerator.generateM(poller)
        Ok.stream(firstResponse andThen returnEnumerator &> Comet(callback = callback) andThen Enumerator(script(JsString("end"))) andThen Enumerator.eof )
      case None =>
        Ok(script(JsString("network_not_connected")))
    }
  }

  // status update -- see ScalaComet & Andrew's gist -- https://gist.github.com/andrewconner/f6333839c77b7a1cf2da
  val abookUploadTimeoutThreshold = sys.props.getOrElse("abook.upload.timeout.threshold", "30").toInt * 1000
  def getABookUploadStatus(id:Id[ABookInfo], callbackOpt:Option[String]) = AuthenticatedHtmlAction { request =>
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
        if ((System.currentTimeMillis - ts) > abookUploadTimeoutThreshold) {
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
    Ok.stream(returnEnumerator.andThen(Enumerator.eof))
  }
}
