package com.keepit.commanders


import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.model._
import com.keepit.abook.ABookServiceClient

import play.api.libs.json._
import play.api.libs.json.{Json, JsValue}
import com.google.inject.Inject
import com.keepit.common.social.BasicUserRepo
import com.keepit.social.BasicUser
import scala.concurrent.Future
import com.keepit.common.usersegment.UserSegment
import com.keepit.common.usersegment.UserSegmentFactory

case class BasicSocialUser(network: String, profileUrl: Option[String], pictureUrl: Option[String])


case class EmailInfo(address: String, isPrimary: Boolean, isVerified: Boolean, isPendingPrimary: Boolean)
object EmailInfo {
  implicit val format = new Format[EmailInfo] {
    def reads(json: JsValue): JsResult[EmailInfo] = {
      val arr = json.as[JsArray]
      JsSuccess(EmailInfo(
        address = arr(0).as[String],
        isPrimary = arr(1).as[Boolean],
        isVerified = arr(2).asOpt[Boolean].getOrElse(false),
        isPendingPrimary = arr(3).asOpt[Boolean].getOrElse(false)
      ))
    }
    def writes(ei: EmailInfo): JsValue = {
      Json.arr(JsString(ei.address), JsBoolean(ei.isPrimary), JsBoolean(ei.isVerified), JsBoolean(ei.isPendingPrimary))
    }
  }
}
case class UpdatableUserInfo(
    description: Option[String], emails: Option[Seq[EmailInfo]],
    firstName: Option[String] = None, lastName: Option[String] = None)

case class BasicUserInfo(basicUser: BasicUser, info: UpdatableUserInfo)

object BasicSocialUser {
  implicit val writesBasicSocialUser = Json.writes[BasicSocialUser]
  def from(sui: SocialUserInfo): BasicSocialUser =
    BasicSocialUser(network = sui.networkType.name, profileUrl = sui.getProfileUrl, pictureUrl = sui.getPictureUrl())
}

object UpdatableUserInfo {
  implicit val updatableUserDataFormat = Json.format[UpdatableUserInfo]
}

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
  abook: ABookServiceClient) {

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
    val emailInfos = emails.sortWith { case (a, b) =>
      if (a.id.get.id == primary) true
      else if (b.id.get.id == primary) false
      else if (a.verified && b.verified) a.id.get.id < b.id.get.id
      else if (a.verified) true
      else if (b.verified) false
      else  a.id.get.id < b.id.get.id
    }.map { email =>
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
    val (numBms, numFriends) = db.readOnly{ implicit s =>
      (bookmarkRepo.getCountByUser(userId), userConnectionRepo.getConnectionCount(userId))
    }

    val segment = UserSegmentFactory(numBms, numFriends)
    segment
  }
}
