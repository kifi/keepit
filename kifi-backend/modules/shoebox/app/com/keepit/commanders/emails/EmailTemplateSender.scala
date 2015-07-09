package com.keepit.commanders.emails

import com.google.inject.{ ImplementedBy, Inject }
import com.keepit.common.db.{ Id }
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.mail.template.{ EmailTrackingParam, EmailTip, EmailToSend }
import com.keepit.common.mail.{ SystemEmailAddress, PostOffice, ElectronicMail, ElectronicMailRepo, LocalPostOffice }
import com.keepit.heimdal.{ HeimdalServiceClient, UserEventTypes, UserEvent, HeimdalContextBuilder }
import com.keepit.inject.FortyTwoConfig
import com.keepit.model.{ User, UserEmailAddressRepo, UserValueName, UserValueRepo }
import play.api.libs.json.Json

import scala.concurrent.{ ExecutionContext, Future }

/*
This class is involved in a circular dependency:

com.keepit.commanders.emails.EmailTemplateSender (ImplementedBy) ->
com.keepit.commanders.emails.EmailTemplateSenderImpl (Inject) ->
com.keepit.commanders.emails.EmailTemplateProcessor (ImplementedBy) ->
com.keepit.commanders.emails.EmailTemplateProcessorImpl (Inject) ->
com.keepit.commanders.emails.tips.EmailTipProvider (Inject) ->
com.keepit.commanders.emails.FriendRecommendationsEmailTip (Inject) ->
com.keepit.commanders.UserConnectionsCommander (Inject) ->
com.keepit.social.SocialGraphPlugin (bound by ProdSocialGraphModule) -> $$$ See note below. $$$
com.keepit.social.SocialGraphPluginImpl (Inject) ->
com.keepit.common.social.SocialGraphActor (Inject) ->
com.keepit.common.social.UserConnectionCreator (Inject) ->
com.keepit.commanders.FriendConnectionNotifier (Inject) ->
com.keepit.commanders.emails.FriendConnectionMadeEmailSender (Inject) ->
com.keepit.commanders.emails.EmailTemplateSender

$$$ ProdSocialGraphModule is the default implementation of SocialGraphModule defined in ShoeboxModule.   $$$
$$$ In tests, this circular dependency can be broken at this step by using FakeSocialGraphModule instead. $$$
*/

@ImplementedBy(classOf[EmailTemplateSenderImpl])
trait EmailTemplateSender {
  def send(emailPartial: EmailToSend): Future[ElectronicMail]
}

class EmailTemplateSenderImpl @Inject() (
    db: Database,
    htmlPreProcessor: EmailTemplateProcessor,
    heimdal: HeimdalServiceClient,
    emailRepo: ElectronicMailRepo,
    postOffice: LocalPostOffice,
    emailAddrRepo: UserEmailAddressRepo,
    userValueRepo: UserValueRepo,
    implicit val defaultContext: ExecutionContext,
    config: FortyTwoConfig) extends EmailTemplateSender with Logging {

  def send(emailToSend: EmailToSend) = {
    htmlPreProcessor.process(emailToSend) map { result =>
      val toAddresses = Seq(emailToSend.to match {
        case Left(userId) => db.readOnlyReplica { implicit sess => emailAddrRepo.getByUser(userId) }
        case Right(address) => address
      })

      val headers = if (emailToSend.from == SystemEmailAddress.NOTIFICATIONS) {
        // emails from Notifications should default reply-to support
        Some(Map(PostOffice.Headers.REPLY_TO -> SystemEmailAddress.SUPPORT.address) ++ emailToSend.extraHeaders.getOrElse(Map.empty))
      } else {
        emailToSend.extraHeaders
      }

      val email = ElectronicMail(
        from = emailToSend.from,
        to = toAddresses,
        cc = emailToSend.cc,
        subject = result.subject,
        htmlBody = result.htmlBody,
        textBody = result.textBody,
        fromName = result.fromName,
        category = emailToSend.category,
        extraHeaders = headers
      )

      result.toUser foreach { userId =>
        trackUserEvent(userId, result, email, emailToSend)
        result.includedTip foreach { tip => recordSentTip(tip, userId) }
      }

      db.readWrite { implicit rw => postOffice.sendMail(email) }
    }
  }

  private def trackUserEvent(userId: Id[User], processedResult: ProcessedEmailResult, email: ElectronicMail, emailToSend: EmailToSend) = {
    // todo(josh) track the optional variableComponents
    val param = EmailTrackingParam(tip = processedResult.includedTip)
    val context = {
      val ctxBuilder = new HeimdalContextBuilder
      emailToSend.auxiliaryData.foreach { ctx =>
        ctxBuilder.data ++= ctx.data
      }
      ctxBuilder += ("action", "prepared")
      ctxBuilder.addEmailInfo(email)
      ctxBuilder.addDetailedEmailInfo(param)
      ctxBuilder.build
    }
    val event = UserEvent(userId = userId, context = context, eventType = UserEventTypes.WAS_NOTIFIED)
    heimdal.trackEvent(event)
  }

  private def recordSentTip(tip: EmailTip, userId: Id[User]) = {
    db.readWrite { implicit rw =>
      val userValueOpt = userValueRepo.getUserValue(userId, UserValueName.LATEST_EMAIL_TIPS_SENT)
      val latestTipsSent = userValueOpt flatMap { userValue =>
        val jsValue = Json.parse(userValue.value)
        Json.fromJson[Seq[EmailTip]](jsValue).asOpt
      } getOrElse Seq.empty

      // only persist the latest 10 tips
      val updatedTipsJson = Json.toJson((tip +: latestTipsSent).take(10))
      userValueRepo.setValue(userId, UserValueName.LATEST_EMAIL_TIPS_SENT, Json.stringify(updatedTipsJson))
    }
  }
}
