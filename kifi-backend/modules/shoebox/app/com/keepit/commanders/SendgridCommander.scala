package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.{ AirbrakeNotifier, SystemAdminMailSender }
import com.keepit.common.logging.Logging
import com.keepit.common.mail.template.EmailTip.toContextData
import com.keepit.common.mail.template.EmailTrackingParam
import com.keepit.common.mail.{ ElectronicMail, ElectronicMailRepo, EmailAddress, SystemEmailAddress }
import com.keepit.common.time.{ DEFAULT_DATE_TIME_ZONE, currentDateTime }
import com.keepit.heimdal.{ HeimdalContext, HeimdalContextBuilder, HeimdalContextBuilderFactory, HeimdalServiceClient, NonUserEvent, NonUserEventTypes, UserEvent, UserEventTypes }
import com.keepit.model.{ EmailOptOutRepo, ExperimentType, NotificationCategory, User, UserEmailAddressRepo, UserEmailAddressStates }
import com.keepit.social.NonUserKinds
import org.joda.time.DateTime

import scala.concurrent.ExecutionContext

class SendgridCommander @Inject() (
    db: Database,
    systemAdminMailSender: SystemAdminMailSender,
    heimdalClient: HeimdalServiceClient,
    emailAddressRepo: UserEmailAddressRepo,
    electronicMailRepo: ElectronicMailRepo,
    emailOptOutRepo: EmailOptOutRepo,
    recoCommander: RecommendationsCommander,
    heimdalContextBuilder: HeimdalContextBuilderFactory,
    userExperimentCommander: RemoteUserExperimentCommander,
    implicit val executionContext: ExecutionContext,
    protected val airbrake: AirbrakeNotifier) extends Logging {

  import com.keepit.commanders.SendgridEventTypes._

  val earliestAcceptableEventTime = new DateTime(2012, 7, 15, 0, 0)
  val unsubscribeEvents: Seq[SendgridEventType] = Seq(UNSUBSCRIBE, BOUNCE, SPAM_REPORT)

  def processNewEvents(events: Seq[SendgridEvent]): Unit = {
    events foreach report
  }

  private def sendHeimdalEvent(event: SendgridEvent, emailOpt: Option[ElectronicMail]): Unit = {
    log.info(s"sendgrid heimdalEvent eventType(${event.event}}) mailId(${event.mailId}}) ")

    for {
      eventType <- event.event
      rawAddress <- event.email
      address <- EmailAddress.validate(rawAddress).toOption
      email <- emailOpt
    } yield {

      lazy val context = {
        val contextBuilder = heimdalContextBuilder()
        contextBuilder += ("action", eventType.name)
        event.url.foreach { url =>
          contextBuilder += ("clicked", clicked(url))
          addTrackingFromUrlParam(url, contextBuilder)
        }
        contextBuilder.addEmailInfo(email)
        contextBuilder.build
      }

      val relevantUsers = if (NotificationCategory.User.reportToAnalytics.contains(email.category)) {
        db.readOnlyReplica { implicit s => emailAddressRepo.getByAddress(address).map(_.userId).toSet }(captureLocation)
      } else Set.empty

      // if the delivered event is over 3 minutes from when we submitted the email, consider this late and raise an alert
      val isLateDelivery = event.event.exists(SendgridEventTypes.DELIVERED ==) &&
        email.timeSubmitted.exists(_.isBefore(event.timestamp.minusMinutes(3)))

      if (isLateDelivery) {
        airbrake.notify(s"late delivery of email_id=${email.id.get}: submitted [${email.timeSubmitted.get}] delivered [${event.timestamp}]")
      }

      def eventTime =
        if (event.timestamp.isBefore(earliestAcceptableEventTime)) {
          log.warn(s"Sendgrid event timestamp rejected! $event")
          currentDateTime
        } else event.timestamp

      if (relevantUsers.nonEmpty) relevantUsers.foreach { userId =>
        userExperimentCommander.getExperimentsByUser(userId).map { experiments =>
          val builder = heimdalContextBuilder()
          builder.addExistingContext(context)
          builder += ("userStatus", ExperimentType.getUserStatus(experiments))
          builder.addExperiments(experiments)
          val userEventContext = builder.build
          heimdalClient.trackEvent(UserEvent(userId, userEventContext, UserEventTypes.WAS_NOTIFIED, eventTime))

          sendUserUsedKifiEvent(userId, email, event, userEventContext)
        }
      }
      else if (NotificationCategory.NonUser.reportToAnalytics.contains(email.category)) {
        heimdalClient.trackEvent(NonUserEvent(address.address, NonUserKinds.email, context, NonUserEventTypes.WAS_NOTIFIED, eventTime))
      }
    }
  }

  private val userUsedKifiCategories = NotificationCategory.User.DIGEST :: NotificationCategory.User.MESSAGE :: Nil

  /*
   * Manually triggers user_used_kifi events for certain types of emails
   */
  private def sendUserUsedKifiEvent(userId: Id[User], email: ElectronicMail, event: SendgridEvent, context: HeimdalContext) = {
    val notification = NotificationCategory.fromElectronicMailCategory(email.category)

    if (userUsedKifiCategories.contains(notification) && event.event.exists(_ == SendgridEventTypes.CLICK)) {

      // rename the `action` property based on what the subaction property is
      val subaction = context.get[String]("subaction")

      val newActionOpt = subaction match {
        case Some(s) if s.startsWith("clicked") => Some(s)
        case _ => None
      }

      newActionOpt foreach { action =>
        val specialCtxBuilder = heimdalContextBuilder()

        // initialize the context with the context passed in
        specialCtxBuilder.data ++= (context.data - "subaction")
        specialCtxBuilder += ("subsource", notification.category)
        specialCtxBuilder += ("action", action)
        val specialCtx = specialCtxBuilder.build

        heimdalClient.trackEvent(UserEvent(userId, specialCtx, UserEventTypes.USED_KIFI))
      }

    }
  }

  private def report(event: SendgridEvent): Unit = {
    val eventName: Option[SendgridEventType] = event.event

    val emailOpt = for {
      mailId <- event.mailId
      mail <- db.readOnlyReplica { implicit s => electronicMailRepo.getOpt(mailId) }(captureLocation)
    } yield mail

    eventName.filter(unsubscribeEvents contains _).foreach(_ => handleUnsubscribeEvent(event, emailOpt))

    eventName.filter(CLICK == _).foreach { _ =>
      emailOpt.foreach { email =>
        verifyEmailAddress(event, email)
      }
    }

    sendHeimdalEvent(event, emailOpt)
  }

  private def clicked(url: String): String = url.toLowerCase match {
    case kifi if kifi.contains("kifi.com") => url
    case facebook if facebook.contains("facebook.com/pages/kifi") => "Kifi Facebook Page"
    case twitter if twitter.contains("twitter.com/kifi") => "Kifi Twitter Page"
    case linkedin if linkedin.contains("linkedin.com/company/fortytwo") => "Kifi LinkedIn Page"
    case _ => "External Page"
  }

  private def verifyEmailAddress(event: SendgridEvent, email: ElectronicMail): Unit =
    db.readWrite { implicit rw =>
      for {
        userEmail <- email.to.headOption
        emailAddr <- emailAddressRepo.getByAddressOpt(userEmail)
        if !emailAddr.verified
      } yield {
        log.info(s"verifying email($userEmail) from SendGrid event($event)")

        emailAddressRepo.save(emailAddr.copy(state = UserEmailAddressStates.VERIFIED,
          verifiedAt = Some(currentDateTime)))
      }
    }

  private def handleUnsubscribeEvent(event: SendgridEvent, emailOpt: Option[ElectronicMail]): Unit =
    db.readWrite { implicit rw =>
      for {
        email <- emailOpt
        userEmail <- email.to.headOption
      } yield {
        log.info(s"SendGrid unsubscribe email($userEmail from SendGrid event($event)")
        emailOptOutRepo.optOut(userEmail, NotificationCategory.ALL)
      }
    }

  private def addTrackingFromUrlParam(url: String, contextBuilder: HeimdalContextBuilder): Unit = {
    try {
      val uri = new java.net.URI(url)
      val queryOpt = Option(uri.getQuery) map { s => s.replace("&amp;", "&") }
      val keyValuesOpt = queryOpt map (_.split('&').map(str => str.splitAt(str.indexOf('='))).toSeq)
      val datParamOpt = keyValuesOpt flatMap { keyValues =>
        keyValues.find(_._1 == EmailTrackingParam.paramName) map (_._2.drop(1))
      }

      datParamOpt.foreach { encodedEmailTrackingParam =>
        EmailTrackingParam.decode(encodedEmailTrackingParam) match {
          case Right(param) => contextBuilder.addDetailedEmailInfo(param)
          case Left(errors) =>
            val errMsg = errors.mkString("; ")
            airbrake.notify(s"failed to decode EmailTrackingParam: $errMsg")
        }
      }
    } catch {
      case e: Throwable => airbrake.notify(s"could not parse Sendgrid event.url($url)", e)
    }
  }
}

