package com.keepit.commanders


import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.akka.SafeFuture
import com.keepit.common.mail.{PostOffice, LocalPostOffice, ElectronicMail, EmailAddresses, EmailAddressHolder}
import com.keepit.common.social.BasicUserRepo
import com.keepit.social.{UserIdentity, SocialNetworks}
import com.keepit.common.usersegment.UserSegment
import com.keepit.common.usersegment.UserSegmentFactory
import com.keepit.model._
import com.keepit.abook.ABookServiceClient
import com.keepit.heimdal.{UserEventTypes, UserEvent, HeimdalServiceClient, HeimdalContextBuilderFactory}
import com.keepit.social.BasicUser
import com.keepit.common.time._
import com.keepit.eliza.ElizaServiceClient

import play.api.Play.current
import play.api.libs.json._
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import com.google.inject.Inject


import scala.concurrent.Future
import scala.util.Try
import scala.Some

import securesocial.core.{Identity, UserService, Registry, SocialUser}



case class BasicSocialUser(network: String, profileUrl: Option[String], pictureUrl: Option[String])
object BasicSocialUser {
  implicit val writesBasicSocialUser = Json.writes[BasicSocialUser]
  def from(sui: SocialUserInfo): BasicSocialUser =
    BasicSocialUser(network = sui.networkType.name, profileUrl = sui.getProfileUrl, pictureUrl = sui.getPictureUrl())
}

case class EmailInfo(address: String, isPrimary: Boolean, isVerified: Boolean, isPendingPrimary: Boolean)
object EmailInfo {
  implicit val format = new Format[EmailInfo] {
    def reads(json: JsValue): JsResult[EmailInfo] = {
      Try(new EmailInfo(
        (json \ "address").as[String],
        (json \ "isPrimary").asOpt[Boolean].getOrElse(false),
        (json \ "isVerified").asOpt[Boolean].getOrElse(false),
        (json \ "isPendingPrimary").asOpt[Boolean].getOrElse(false)
      )).toOption match {
        case Some(ei) => JsSuccess(ei)
        case None => JsError()
      }
    }

    def writes(ei: EmailInfo): JsValue = {
      Json.obj("address" -> ei.address, "isPrimary" -> ei.isPrimary, "isVerified" -> ei.isVerified, "isPendingPrimary" -> ei.isPendingPrimary)
    }
  }
}

case class UpdatableUserInfo(
    description: Option[String], emails: Option[Seq[EmailInfo]],
    firstName: Option[String] = None, lastName: Option[String] = None)
object UpdatableUserInfo {
  implicit val updatableUserDataFormat = Json.format[UpdatableUserInfo]
}

case class BasicUserInfo(basicUser: BasicUser, info: UpdatableUserInfo)


class UserCommander @Inject() (
  db: Database,
  userRepo: UserRepo,
  emailRepo: EmailAddressRepo,
  userValueRepo: UserValueRepo,
  userConnectionRepo: UserConnectionRepo,
  basicUserRepo: BasicUserRepo,
  bookmarkRepo: BookmarkRepo,
  userExperimentRepo: UserExperimentRepo,
  socialUserInfoRepo: SocialUserInfoRepo,
  eventContextBuilder: HeimdalContextBuilderFactory,
  heimdalServiceClient: HeimdalServiceClient,
  abook: ABookServiceClient,
  postOffice: LocalPostOffice,
  clock: Clock,
  elizaServiceClient: ElizaServiceClient) {

  def getFriends(user: User, experiments: Set[ExperimentType]): Set[BasicUser] = {
    val basicUsers = db.readOnly { implicit s =>
      if (canMessageAllUsers(user.id.get)) {
        userRepo.allExcluding(UserStates.PENDING, UserStates.BLOCKED, UserStates.INACTIVE)
          .collect { case u if u.id.get != user.id.get => BasicUser.fromUser(u) }.toSet
      } else {
        userConnectionRepo.getConnectedUsers(user.id.get).map(basicUserRepo.load)
      }
    }

    // Apologies for this code. "Personal favor" for Danny. Doing it right should be speced and requires
    // two models, service clients, and caches.
    val iNeededToDoThisIn20Minutes = if (experiments.contains(ExperimentType.ADMIN)) {
      Seq(
        BasicUser(ExternalId[User]("42424242-4242-4242-4242-424242424201"), "FortyTwo Engineering", "", "0.jpg"),
        BasicUser(ExternalId[User]("42424242-4242-4242-4242-424242424202"), "FortyTwo Family", "", "0.jpg"),
        BasicUser(ExternalId[User]("42424242-4242-4242-4242-424242424203"), "FortyTwo Product", "", "0.jpg")
      )
    } else {
      Seq()
    }

    // This will eventually be a lot more complex. However, for now, tricking the client is the way to go.
    // ^^^^^^^^^ Unrelated to the offensive code above ^^^^^^^^^
    val kifiSupport = Seq(
      BasicUser(ExternalId[User]("742fa97c-c12a-4dcf-bff5-0f33280ef35a"), "Noah, Kifi Help", "", "Vjy5S.jpg"),
      BasicUser(ExternalId[User]("aa345838-70fe-45f2-914c-f27c865bdb91"), "Tamila, Kifi Help", "", "tmilz.jpg"))
    basicUsers ++ iNeededToDoThisIn20Minutes ++ kifiSupport
  }

  private def canMessageAllUsers(userId: Id[User])(implicit s: RSession): Boolean = {
    userExperimentRepo.hasExperiment(userId, ExperimentType.CAN_MESSAGE_ALL_USERS)
  }

  def socialNetworkInfo(userId: Id[User]) = db.readOnly { implicit s =>
    socialUserInfoRepo.getByUser(userId).map(BasicSocialUser.from)
  }

  def uploadContactsProxy(userId: Id[User], origin: ABookOriginType, payload: JsValue): Future[JsValue] = {
    abook.uploadContacts(userId, origin, payload)
  }

  def getUserInfo(user: User): BasicUserInfo = {
    val (basicUser, description, emails, pendingPrimary) = db.readOnly { implicit session =>
      val basicUser = basicUserRepo.load(user.id.get)
      val description =  userValueRepo.getValue(user.id.get, "user_description")
      val emails = emailRepo.getAllByUser(user.id.get)
      val pendingPrimary = userValueRepo.getValue(user.id.get, "pending_primary_email")
      (basicUser, description, emails, pendingPrimary)
    }

    val primary = user.primaryEmailId.map(_.id).getOrElse(0L)
    val emailInfos = emails.sortBy(e => (e.id.get.id != primary, !e.verified, e.id.get.id)).map { email =>
      EmailInfo(
        address = email.address,
        isVerified = email.verified,
        isPrimary = user.primaryEmailId.isDefined && user.primaryEmailId.get.id == email.id.get.id,
        isPendingPrimary = pendingPrimary.isDefined && pendingPrimary.get == email.address
      )
    }
    BasicUserInfo(basicUser, UpdatableUserInfo(description, Some(emailInfos)))
  }

  def getUserSegment(userId: Id[User]): UserSegment = {
    val (numBms, numFriends) = db.readOnly{ implicit s => //using cache
      (bookmarkRepo.getCountByUser(userId), userConnectionRepo.getConnectionCount(userId))
    }

    val segment = UserSegmentFactory(numBms, numFriends)
    segment
  }

  def createUser(firstName: String, lastName: String, state: State[User])(implicit session: RWSession) = {
    val newUser = userRepo.save(User(firstName = firstName, lastName = lastName, state = state))
    SafeFuture {
      val contextBuilder = eventContextBuilder()
      contextBuilder += ("action", "registered")
      // more properties to be added after some refactoring in SecureSocialUserServiceImpl
      // requestInfo ???
      // val socialUser: SocialUser = ???
      // contextBuilder += ("identityProvider", socialUser.identityId.providerId)
      // contextBuilder += ("authenticationMethod", socialUser.authMethod.method)
      heimdalServiceClient.trackEvent(UserEvent(newUser.id.get, contextBuilder.build, UserEventTypes.JOINED, newUser.createdAt))
    }
    session.conn.commit()

    // Here you can do things with default keeps / tags. See ExtBookmarksController / BookmarkInterner for examples.

    newUser
  }

  def tellAllFriendsAboutNewUser(newUserId: Id[User], additionalRecipients: Seq[Id[User]]): Unit = {
    val guardKey = "friendsNotifiedAboutJoining"
    if (!db.readOnly{ implicit session => userValueRepo.getValue(newUserId, guardKey).exists(_=="true") }) {
      db.readWrite { implicit session => userValueRepo.setValue(newUserId, guardKey, "true") }
      val (newUser, toNotify, id2Email) = db.readOnly { implicit session =>
        val newUser = userRepo.get(newUserId)
        val toNotify = userConnectionRepo.getConnectedUsers(newUserId) ++ additionalRecipients //ZZZ does this include social connections
        val id2Email = toNotify.map { userId =>
          (userId, emailRepo.getByUser(userId))
        }.toMap
        (newUser, toNotify, id2Email)
      }
      toNotify.foreach { userId => //ZZZ update content here to the correct content
        db.readWrite{ implicit session =>
          postOffice.sendMail(ElectronicMail(
            senderUserId = None,
            from = EmailAddresses.CONGRATS,
            fromName = Some("KiFi Team"),
            to = List(id2Email(userId)),
            subject = s"${newUser.firstName} ${newUser.lastName} joined KiFi!",
            htmlBody = views.html.email.invitationFriendJoined(newUser).body,
            category = PostOffice.Categories.User.INVITATION)
          )
        }

      }

      elizaServiceClient.sendGlobalNotification( //ZZZ update this with correct copy, etc.
        userIds = toNotify,
        title = "A New Kifi Friend!",
        body = s"${newUser.firstName} ${newUser.lastName} just joined Kifi!",
        linkText = "Click to see friends",
        linkUrl = "https://www.kifi.com/friends",
        imageUrl = newUser.pictureName.getOrElse("http://www.42go.com/images/favicon.png"), //needs path?
        sticky = false
      )
    }
  }

  def sendWelcomeEmail(newUser: User, withVerification: Boolean = false, targetEmailOpt: Option[EmailAddressHolder] = None): Unit = {
    val guardKey = "welcomeEmailSent"
    if (!db.readOnly{ implicit session => userValueRepo.getValue(newUser.id.get, guardKey).exists(_=="true") }) {
      db.readWrite { implicit session => userValueRepo.setValue(newUser.id.get, guardKey, "true") }

      if (withVerification) {
        val url = current.configuration.getString("application.baseUrl").get
        db.readWrite { implicit session =>
          val emailAddr = emailRepo.save(emailRepo.getByAddressOpt(targetEmailOpt.get.address).get.withVerificationCode(clock.now))
          val verifyUrl = s"$url${com.keepit.controllers.core.routes.AuthController.verifyEmail(emailAddr.verificationCode.get)}"
          userValueRepo.setValue(newUser.id.get, "pending_primary_email", emailAddr.address)

          val (subj, body) = if (newUser.state != UserStates.ACTIVE) {
            ("Kifi.com | Please confirm your email address",
              views.html.email.verifyEmail(newUser.firstName, verifyUrl).body) //ZZZ make this the new template when ready. Remember newIdentity
          } else {
            ("Welcome to Kifi! Please confirm your email address",
              views.html.email.verifyAndWelcomeEmail(newUser, verifyUrl).body) //ZZZ make this the new template when ready
          }
          val mail = ElectronicMail(
            from = EmailAddresses.NOTIFICATIONS,
            to = Seq(targetEmailOpt.get),
            category = PostOffice.Categories.User.EMAIL_CONFIRMATION,
            subject = subj,
            htmlBody = body
          )
          postOffice.sendMail(mail)
        }
      } else {
        db.readWrite{ implicit session =>
          val emailAddr = emailRepo.getByUser(newUser.id.get)
          val mail = ElectronicMail(
            from = EmailAddresses.NOTIFICATIONS,
            to = Seq(emailAddr),
            category = PostOffice.Categories.User.EMAIL_CONFIRMATION,
            subject = "Welcome to Kifi!", //ZZZ add new copy
            htmlBody = views.html.email.verifyAndWelcomeEmail(newUser, "http://www.kifi.com").body //ZZZ make this the new template when ready
            //ZZZ should we have a text only body as well?
          )
          postOffice.sendMail(mail)
        }
      }
    }
  }

  def doChangePassword(userId:Id[User], oldPassword:String, newPassword:String):Try[Identity] = Try {
    val resOpt = db.readOnly { implicit session =>
      socialUserInfoRepo.getByUser(userId).find(_.networkType == SocialNetworks.FORTYTWO)
    } map { sui =>
      val hasher = Registry.hashers.currentHasher
      val identity = sui.credentials.get
      if (!hasher.matches(identity.passwordInfo.get, oldPassword)) throw new IllegalArgumentException("bad_old_password")
      else {
        val pInfo = Registry.hashers.currentHasher.hash(newPassword)
        UserService.save(UserIdentity(
          userId = sui.userId,
          socialUser = sui.credentials.get.copy(passwordInfo = Some(pInfo))
        ))
      }
    }
    resOpt getOrElse { throw new IllegalArgumentException("no_user") }
  }

}
