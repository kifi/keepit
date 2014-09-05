package com.keepit.commanders.emails

import com.google.inject.{ ImplementedBy, Inject }
import com.keepit.commanders.UserCommander
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.mail.{ EmailToSend, EmailAddress }
import com.keepit.inject.FortyTwoConfig
import com.keepit.model.Email.{ TagWrapper, tags }
import com.keepit.model.{ UserEmailAddressRepo, UserRepo, User }
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.social.BasicUser
import play.api.libs.json.{ Json, JsValue }
import play.api.templates.Html
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future

@ImplementedBy(classOf[EmailTemplateProcessorImpl])
trait EmailTemplateProcessor {
  def process(module: EmailToSend): Future[Html]
}

class EmailTemplateProcessorImpl @Inject() (
    shoebox: ShoeboxServiceClient,
    db: Database, userRepo: UserRepo,
    userCommander: UserCommander,
    emailAddressRepo: UserEmailAddressRepo,
    config: FortyTwoConfig,
    emailOptOutCommander: EmailOptOutCommander) extends EmailTemplateProcessor {
  import com.keepit.model.Email.tagRegex

  /* for the lack of a better name, this is just a trait that encapsulates
     the type of object(s) needed to replace a placeholder */
  trait NeededObject

  object NothingNeeded extends NeededObject

  case class AvatarUrlNeeded(id: Id[User]) extends NeededObject

  case class UserNeeded(id: Id[User]) extends NeededObject

  case class DataNeededResult(users: Map[Id[User], User], imageUrls: Map[Id[User], String])

  def process(emailToSend: EmailToSend) = {
    val html = views.html.email.black.layout(emailToSend.htmlTemplates)
    val needs = getNeededObjects(html, emailToSend)

    val userIds = needs.collect { case UserNeeded(id) => id }
    val avatarUrlUserIds = needs.collect { case AvatarUrlNeeded(id) => id }

    val usersF = getUsers(userIds.toSeq)
    val userImageUrlsF = getUserImageUrls(avatarUrlUserIds.toSeq)

    for {
      users <- usersF
      userImageUrls <- userImageUrlsF
    } yield {
      val input = DataNeededResult(users = users, imageUrls = userImageUrls)
      evalHtml(html, input, emailToSend)
    }
  }

  private def evalHtml(html: Html, input: DataNeededResult, emailToSend: EmailToSend) = {
    Html(tagRegex.replaceAllIn(html.body, { rMatch =>
      val tagWrapper = Json.parse(rMatch.group(1)).as[TagWrapper]
      val tagArgs = tagWrapper.args

      // only call if Id[User] is expected as the first argument
      @inline def userId = jsValueAsUserId(tagArgs(0))
      @inline def user = input.users(userId)
      @inline def basicUser = BasicUser.fromUser(user)

      tagWrapper.label match {
        case tags.firstName => basicUser.firstName
        case tags.lastName => basicUser.lastName
        case tags.fullName => basicUser.firstName + " " + basicUser.lastName
        case tags.avatarUrl => input.imageUrls(userId)
        case tags.unsubscribeUrl if emailToSend.to.isLeft =>
          val address = db.readOnlyReplica { implicit s => emailAddressRepo.getByUser(userId) }
          getUnsubUrl(address)
        case tags.unsubscribeUrl if emailToSend.to.isRight => getUnsubUrl(emailToSend.to.right.get)
        case tags.unsubscribeUserUrl =>
          val address = db.readOnlyReplica { implicit s => emailAddressRepo.getByUser(userId) }
          getUnsubUrl(address)
        case tags.unsubscribeEmailUrl => getUnsubUrl(tagWrapper.args(0).as[EmailAddress])
        case tags.userExternalId => user.externalId.toString()
        case tags.title => emailToSend.title
      }
    }))
  }

  // used to gather the types of objects we need to replace the tags with real values
  private def getNeededObjects(html: Html, emailToSend: EmailToSend) = {
    tagRegex.findAllMatchIn(html.body).map[NeededObject] { rMatch =>
      val tagWrapper = Json.parse(rMatch.group(1)).as[TagWrapper]
      val tagArgs = tagWrapper.args

      // only call if Id[User] is expected as the first argument
      @inline def userId = jsValueAsUserId(tagArgs(0))

      tagWrapper.label match {
        case tags.firstName | tags.lastName | tags.fullName |
          tags.unsubscribeUserUrl | tags.userExternalId => UserNeeded(userId)
        case tags.avatarUrl => AvatarUrlNeeded(userId)
        case _ => NothingNeeded
      }
    }.toSet
  }

  private def getUsers(userIds: Seq[Id[User]]) = {
    db.readOnlyReplicaAsync { implicit s => userRepo.getUsers(userIds) }
  }

  private def getUserImageUrls(userIds: Seq[Id[User]], width: Int = 100) = {
    Future.sequence(
      userIds map (userId => userCommander.getUserImageUrl(userId, width).map(url => (userId, url)))
    ) map (_.toMap)
  }

  private def getUnsubUrl(emailAddr: EmailAddress) = {
    val token = emailOptOutCommander.generateOptOutToken(emailAddr)
    s"https://www.kifi.com${com.keepit.controllers.website.routes.EmailOptOutController.optOut(token)}"
  }

  private def jsValueAsUserId(arg: JsValue) = arg.as[Id[User]]

}

