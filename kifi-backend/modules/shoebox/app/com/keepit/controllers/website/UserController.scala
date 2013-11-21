package com.keepit.controllers.website

import java.text.Normalizer

import com.google.inject.Inject
import com.keepit.common.controller.{AuthenticatedRequest, ActionAuthenticator, WebsiteController}
import com.keepit.common.db.{Id, ExternalId}
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick._
import com.keepit.common.mail.{PostOffice, EmailAddresses, ElectronicMail, LocalPostOffice}
import com.keepit.common.social.BasicUserRepo
import com.keepit.controllers.core.NetworkInfoLoader
import com.keepit.commanders.UserCommander
import com.keepit.model._
import play.api.libs.json.Json.toJson
import com.keepit.abook.ABookServiceClient
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import play.api.libs.json._
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.concurrent.{Promise => PlayPromise}
import play.api.libs.Comet
import com.keepit.common.time._
import play.api.templates.Html
import play.api.libs.iteratee.Enumerator
import play.api.Play.current

import java.util.concurrent.atomic.AtomicBoolean
import com.keepit.heimdal.HeimdalServiceClient
import com.keepit.common.akka.SafeFuture
import play.api.Play

class UserController @Inject() (
  db: Database,
  userRepo: UserRepo,
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
  clock: Clock,
  abookServiceClient: ABookServiceClient
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
            userConnectionRepo.addConnections(friendReq.senderId, Set(friendReq.recipientId), requested = true)
            // TODO(greg): trigger notification?
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

  def currentUser = AuthenticatedJsonAction(true) { implicit request => getUserInfo(request) }

  private case class UpdatableUserInfo(
      description: Option[String], emails: Option[Seq[String]],
      firstName: Option[String] = None, lastName: Option[String] = None)
  private implicit val updatableUserDataFormat = Json.format[UpdatableUserInfo]

  def updateCurrentUser() = AuthenticatedJsonToJsonAction(true) { implicit request =>
    request.body.asOpt[UpdatableUserInfo] map { userData =>
      db.readWrite { implicit session =>
        userData.description foreach { userValueRepo.setValue(request.userId, "user_description", _) }
        if (userData.firstName.isDefined || userData.lastName.isDefined) {
          val user = userRepo.get(request.userId)
          val cleanFirst = User.sanitizeName(userData.firstName getOrElse user.firstName)
          val cleanLast = User.sanitizeName(userData.lastName getOrElse user.lastName)
          val updatedUser = user.copy(firstName = cleanFirst, lastName = cleanLast)
          userRepo.save(updatedUser)
        }
        for (emails <- userData.emails) {
          val (existing, toRemove) = emailRepo.getAllByUser(request.user.id.get).partition(emails contains _.address)
          for (email <- toRemove) {
            emailRepo.save(email.withState(EmailAddressStates.INACTIVE))
          }
          for (address <- emails.toSet -- existing.map(_.address)) {
            emailRepo.save(EmailAddress(userId = request.userId, address = address))
          }
        }
      }
      getUserInfo(request)
    } getOrElse {
      BadRequest(Json.obj("error" -> "could not parse user info from body"))
    }
  }

  private def getUserInfo[T](request: AuthenticatedRequest[T]) = {
    val basicUser = db.readOnly { implicit s => basicUserRepo.load(request.userId) }
    val info = db.readOnly { implicit s =>
      UpdatableUserInfo(
        description = Some(userValueRepo.getValue(request.userId, "user_description").getOrElse("")),
        emails = Some(emailRepo.getAllByUser(request.userId).map(_.address))
      )
    }
    Ok(toJson(basicUser).as[JsObject] ++ toJson(info).as[JsObject] ++ Json.obj("experiments" -> request.experiments.map(_.value)))
  }

  private val SitePrefNames = Set("site_left_col_width", "site_welcomed")

  def getPrefs() = AuthenticatedJsonAction { request =>
    Ok(db.readOnly { implicit s =>
      JsObject(SitePrefNames.toSeq.map { name =>
        name -> userValueRepo.getValue(request.userId, name).map(JsString).getOrElse(JsNull)
      })
    })
  }

  def savePrefs() = AuthenticatedJsonToJsonAction { request =>
    val o = request.request.body.as[JsObject]
    if (o.keys.subsetOf(SitePrefNames)) {
      db.readWrite { implicit s =>
        o.fields.foreach { case (name, value) =>
          userValueRepo.setValue(request.userId, name, value.as[String])  // TODO: deactivate pref if JsNull
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

    val res = abookServiceClient.getEContacts(userId, 40000000).map { contacts =>
      val filtered = contacts.filter(e => ((searchScore(e.name.getOrElse("")) > 0) || (searchScore(e.email) > 0)))
      val paged = after match {
        case Some(a) => filtered.dropWhile(e => (mkId(e.email) != a)) match {
          case hd +: tl => tl
          case tl => tl
        }
        case None => filtered
      }
      val objs = paged.take(limit).map { e =>
        Json.obj("label" -> JsString(e.name.getOrElse("")), "value" -> mkId(e.email), "status" -> getEInviteStatus(e.id))
      }
      log.info(s"[queryContacts(id=$userId)] res(len=${objs.length}):${objs.mkString.take(200)}")
      objs
    }
    res
  }

  def getAllConnections(search: Option[String], network: Option[String], after: Option[String], limit: Int) = AuthenticatedJsonAction { request =>
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
      socialConnectionRepo.getSocialConnectionInfo(sui.id.get) filter (searchScore(_) > 0)

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

  private val domainName = if (Play.isDev) "dev.ezkeep.com" else "kifi.com"
  private val domain = Html(s"<script>document.domain='$domainName';</script>")

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
        val firstResponse = Enumerator.enumerate(domain +: check().map(script).toSeq)
        val returnEnumerator = Enumerator.generateM(poller)
        Ok.stream(firstResponse andThen returnEnumerator &> Comet(callback = callback) andThen Enumerator(script(JsString("end"))) andThen Enumerator.eof )
      case None =>
        Ok(domain += script(JsString("network_not_connected")))
    }
  }

  // status update -- see ScalaComet & Andrew's gist -- https://gist.github.com/andrewconner/f6333839c77b7a1cf2da
  def getABookUploadStatus(id:Id[ABookInfo], callbackOpt:Option[String]) = AuthenticatedHtmlAction { request =>
    val callback = callbackOpt.getOrElse("parent.updateABookProgress")
    val done = new AtomicBoolean(false)
    def timeoutF = play.api.libs.concurrent.Promise.timeout(None, 500)
    def reqF = abookServiceClient.getABookInfo(request.userId, id) map { abookInfoOpt =>
      abookInfoOpt match {
        case Some(abookInfo) if abookInfo.state == ABookInfoStates.ACTIVE => {
          if (done.get) None else {
            log.info(s"[getABookUploadStatus($id)] available!")
            done.set(true)
            Some(s"<script>$callback($id,'${abookInfo.state}',${abookInfo.numContacts.getOrElse(-1)},${abookInfo.numProcessed.getOrElse(-1)})</script>")
          }
        }
        case waitingOpt => waitingOpt match {
          case Some(processing) => {
            log.info(s"[getABookUploadStatus($id)] waiting ... '${processing.state}'")
            Some(s"<script>$callback($id,'${processing.state}',${processing.numContacts.getOrElse(-1)},${processing.numProcessed.getOrElse(-1)})</script>")
          }
          case None => {
            log.info(s"[getABookUploadStatus($id)] waiting ... 'notAvail'") // can be an error
            Some(s"<script>$callback($id,'notAvail',-1,-1)</script>")
          }
        }
      }
    }
    val firstResponse = Enumerator(domain.body)
    val returnEnumerator = Enumerator.generateM {
      Future.sequence(Seq(timeoutF, reqF)).map { res =>
         res.collect { case Some(s:String) => s }.headOption
      }
    }
    Ok.stream(firstResponse andThen returnEnumerator.andThen(Enumerator.eof))
  }
}
