package com.keepit.eliza.commanders

import com.google.inject.Inject
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.mail.template.{ TemplateOptions, EmailToSend }
import com.keepit.eliza.model.SystemMessageData.StartWithEmails
import com.keepit.rover.RoverServiceClient
import com.keepit.rover.model.RoverUriSummary

import scala.concurrent.Future

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.twirl.api.Html

import org.joda.time.DateTime

import com.keepit.common.db.{ Id, ExternalId }
import com.keepit.eliza.model._
import com.keepit.social.NonUserKinds
import com.keepit.model._
import com.keepit.common.concurrent.FutureHelpers
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.common.store.{ S3ImageConfig, ImageSize }
import com.keepit.common.db.slick.Database
import com.keepit.common.mail.{ SystemEmailAddress, PostOffice }
import com.keepit.common.time._
import com.keepit.common.akka.SafeFuture
import play.api.libs.json.JsString
import com.keepit.common.mail.EmailAddress
import com.keepit.common.logging.Logging
import com.keepit.eliza.util.{ MessageFormatter, TextSegment }
import com.keepit.common.strings.AbbreviateString
import com.keepit.common.domain.DomainToNameMapper

object ElizaEmailUriSummaryImageSizes {
  val bigImageSize = ImageSize(620, 200)
  val smallImageSize = ImageSize(183, 96)
}

class ElizaEmailCommander @Inject() (
    shoebox: ShoeboxServiceClient,
    db: Database,
    nonUserThreadRepo: NonUserThreadRepo,
    userThreadRepo: UserThreadRepo,
    messageFetchingCommander: MessageFetchingCommander,
    messageRepo: MessageRepo,
    threadRepo: MessageThreadRepo,
    rover: RoverServiceClient,
    implicit val imageConfig: S3ImageConfig,
    implicit val publicIdConfig: PublicIdConfiguration,
    clock: Clock) extends Logging {

  import ElizaEmailUriSummaryImageSizes._

  case class ProtoEmail(digestHtml: Html, initialHtml: Html, addedHtml: Html, starterName: String, pageTitle: String)

  def getUriSummary(thread: MessageThread): Future[Option[RoverUriSummary]] = {
    val uriId = thread.uriId
    val normalizedUrl = thread.nUrl
    rover.getOrElseFetchUriSummary(uriId, normalizedUrl)
  }

  def getThreadEmailInfo(
    thread: MessageThread,
    uriSummary: Option[RoverUriSummary],
    idealImageSize: ImageSize,
    isInitialEmail: Boolean,
    allUsers: Map[Id[User], User],
    allUserImageUrls: Map[Id[User], String],
    unsubUrl: Option[String] = None,
    nonUserThread: Option[NonUserThread]): ThreadEmailInfo = {

    val (nuts, starterUserId) = db.readOnlyMaster { implicit session =>
      (
        nonUserThreadRepo.getByKeepId(thread.keepId),
        thread.startedBy
      )
    }

    val starterUser = allUsers(starterUserId)
    val participants = allUsers.values.map { _.fullName } ++ nuts.map { _.participant.fullName }

    val invitedByUser = nonUserThread.flatMap(nut => allUsers.get(nut.createdBy))

    val pageName = DomainToNameMapper.getNameFromUrl(thread.nUrl).getOrElse("")

    val muteUrl = nonUserThread.map(nut => "https://www.kifi.com/extmsg/email/mute?publicId=" + nut.accessToken.token)

    ThreadEmailInfo(
      uriId = thread.uriId,
      keepId = thread.pubKeepId,
      pageName = pageName,
      pageTitle = thread.pageTitle.orElse(uriSummary.flatMap(_.article.title)).getOrElse(thread.nUrl).abbreviate(80),
      isInitialEmail = isInitialEmail,
      heroImageUrl = uriSummary.flatMap(_.images.get(idealImageSize).map(_.path.getUrl)),
      pageDescription = uriSummary.flatMap(_.article.description.map(_.take(190) + "...")),
      participants = participants.toSeq,
      conversationStarter = starterUser.firstName + " " + starterUser.lastName,
      invitedByUser = invitedByUser,
      unsubUrl = unsubUrl,
      muteUrl = muteUrl,
      readTimeMinutes = uriSummary.flatMap(_.article.readTime.map(_.toMinutes.toInt)),
      nonUserAccessToken = nonUserThread.map(_.accessToken.token))
  }

  def getExtendedThreadItems(
    thread: MessageThread,
    allUsers: Map[Id[User], User],

    allUserImageUrls: Map[Id[User], String],
    fromTime: Option[DateTime]): Seq[ExtendedThreadItem] = {
    val messages = messageFetchingCommander.getThreadMessages(thread)

    val relevantMessages = messages.filter { m =>
      fromTime.map { dt => m.createdAt.isAfter(dt.minusMillis(100)) } getOrElse true
    }

    relevantMessages.filterNot(_.from.isSystem).map { message =>
      val messageSegments = MessageFormatter.parseMessageSegments(message.messageText)
      message.from match {
        case MessageSender.User(id) => ExtendedThreadItem(allUsers(id).shortName, allUsers(id).fullName, Some(allUserImageUrls(id)), messageSegments)
        case MessageSender.NonUser(nup) => {
          ExtendedThreadItem(nup.shortName, nup.fullName, None, messageSegments)
        }
        case _ => throw new Exception("Impossible")
      }
    }.reverse
  }

  /**
   * Fetches all information that is common to all emails sent relative to a specific MessageThread.
   * This function should be called as few times as possible
   */
  def getThreadEmailData(thread: MessageThread): Future[ThreadEmailData] = {

    val allUserIds: Set[Id[User]] = thread.participants.allUsers
    val allUsersFuture: Future[Map[Id[User], User]] = new SafeFuture(shoebox.getUsers(allUserIds.toSeq).map(s => s.map(u => u.id.get -> u).toMap))
    val allUserImageUrlsFuture: Future[Map[Id[User], String]] = new SafeFuture(FutureHelpers.map(allUserIds.map(u => u -> shoebox.getUserImageUrl(u, 73)).toMap))
    val uriSummaryFuture = getUriSummary(thread)

    for {
      allUsers <- allUsersFuture
      allUserImageUrls <- allUserImageUrlsFuture
      uriSummary <- uriSummaryFuture
    } yield {
      ThreadEmailData(thread, allUserIds, allUsers, allUserImageUrls, uriSummary)
    }
  }

  private def assembleEmail(threadEmailData: ThreadEmailData, nonUserThread: Option[NonUserThread], unsubUrl: Option[String]): ProtoEmail = {
    val ThreadEmailData(thread, _, allUsers, allUserImageUrls, uriSummary) = threadEmailData
    val threadInfoSmall = getThreadEmailInfo(thread, uriSummary, smallImageSize, true, allUsers, allUserImageUrls, unsubUrl, nonUserThread)
    val bigImageUrl = uriSummary.flatMap(_.images.get(bigImageSize).map(_.path.getUrl))
    val smallImageUrl = uriSummary.flatMap(_.images.get(smallImageSize).map(_.path.getUrl))
    val threadInfoBig = threadInfoSmall.copy(heroImageUrl = bigImageUrl orElse smallImageUrl)
    val threadInfoSmallDigest = threadInfoSmall.copy(isInitialEmail = false)
    val threadItems = getExtendedThreadItems(thread, allUsers, allUserImageUrls, None)

    ProtoEmail(
      views.html.discussionEmail(threadInfoSmallDigest, threadItems, false, false, true),
      if (bigImageUrl.isDefined) views.html.discussionEmail(threadInfoBig, threadItems, false, false, false)
      else views.html.discussionEmail(threadInfoSmall, threadItems, false, false, true),
      views.html.discussionEmail(threadInfoSmall, threadItems, false, true, true),
      threadInfoSmall.conversationStarter,
      threadInfoSmall.pageTitle
    )
  }

  def notifyEmailUsers(thread: MessageThread): Unit = if (thread.participants.allNonUsers.nonEmpty) {
    getThreadEmailData(thread) map { threadEmailData =>
      val nuts = db.readOnlyMaster { implicit session =>
        nonUserThreadRepo.getByKeepId(thread.keepId)
      }

      // Intentionally sequential execution
      FutureHelpers.sequentialExec(nuts) { nut =>
        notifyEmailParticipant(nut, threadEmailData)
      }
    }
  }

  def notifyAddedEmailUsers(thread: MessageThread, addedNonUsers: Seq[NonUserParticipant]): Unit = if (thread.participants.allNonUsers.nonEmpty) {
    getThreadEmailData(thread) map { threadEmailData =>
      val nuts = db.readOnlyMaster { implicit session => //redundant right now but I assume we will want to let everyone in the thread know that someone was added?
        nonUserThreadRepo.getByKeepId(thread.keepId).map { nut =>
          nut.participant.identifier -> nut
        }.toMap
      }
      addedNonUsers.map { nup =>
        require(nup.kind == NonUserKinds.email)
        val nut = nuts(nup.identifier)
        if (!nut.muted) {
          safeProcessEmail(threadEmailData, nut, _.addedHtml, NotificationCategory.NonUser.ADDED_TO_DISCUSSION)
        } else Future.successful(())
      }
    }
  }

  def notifyEmailParticipant(emailParticipantThread: NonUserThread, threadEmailData: ThreadEmailData): Future[Unit] = {
    val result = if (emailParticipantThread.muted) Future.successful(()) else {
      require(emailParticipantThread.participant.kind == NonUserKinds.email, s"NonUserThread ${emailParticipantThread.id.get} does not represent an email participant.")
      require(emailParticipantThread.keepId == threadEmailData.thread.keepId, "MessageThread and NonUserThread do not match.")
      val category = if (emailParticipantThread.notifiedCount > 0) NotificationCategory.NonUser.DISCUSSION_UPDATES else NotificationCategory.NonUser.DISCUSSION_STARTED
      val htmlBodyMaker = (protoEmail: ProtoEmail) => if (emailParticipantThread.notifiedCount > 0) protoEmail.digestHtml else protoEmail.initialHtml
      safeProcessEmail(threadEmailData, emailParticipantThread, htmlBodyMaker, category)
    }
    // todo(martin) replace with onSuccess when we have better error handling
    result.onComplete { _ =>
      db.readWrite { implicit session => nonUserThreadRepo.setLastNotifiedAndIncCount(emailParticipantThread.id.get) }
    }
    result
  }

  private def safeProcessEmail(threadEmailData: ThreadEmailData, nonUserThread: NonUserThread, htmlBodyMaker: ProtoEmail => Html, category: NotificationCategory): Future[Unit] = {
    val unsubUrlFut = nonUserThread.participant match {
      case emailParticipant: NonUserEmailParticipant => shoebox.getUnsubscribeUrlForEmail(emailParticipant.address)
      case _ => throw new IllegalArgumentException(s"Unknown non user participant: ${nonUserThread.participant}")
    }

    val protoEmailFut = unsubUrlFut map { unsubUrl =>
      assembleEmail(
        threadEmailData,
        Some(nonUserThread),
        Some(unsubUrl)
      )
    }

    protoEmailFut.flatMap { protoEmail =>
      val magicAddress = SystemEmailAddress.discussion(nonUserThread.accessToken.token)
      val email = EmailToSend(
        from = magicAddress,
        fromName = Some(Right(protoEmail.starterName + " (via Kifi)")),
        to = Right(EmailAddress(nonUserThread.participant.identifier)),
        subject = protoEmail.pageTitle,
        htmlTemplate = htmlBodyMaker(protoEmail),
        category = category,
        extraHeaders = Some(Map(PostOffice.Headers.REPLY_TO -> magicAddress.address)),
        templateOptions = Seq(TemplateOptions.CustomLayout).toMap
      )
      shoebox.processAndSendMail(email) map {
        case true => // all good
        case false => throw new Exception("Shoebox was unable to parse and send the email.")
      }
    }
  }

  def getEmailPreview(msgId: Id[ElizaMessage]): Future[Html] = {
    val (msg, thread) = db.readOnlyReplica { implicit session =>
      val msg = messageRepo.get(msgId)
      val thread = threadRepo.getByKeepId(msg.keepId).get
      (msg, thread)
    }
    val protoEmailFuture = getThreadEmailData(thread) map { assembleEmail(_, None, None) }
    msg.auxData match {
      case Some(_: StartWithEmails) => protoEmailFuture.map(_.initialHtml)
      case Some(_) => protoEmailFuture.map(_.addedHtml)
      case None => protoEmailFuture.map(_.digestHtml)
    }
  }
}

object ElizaEmailCommander {

  /**
   * This function is meant to be used from the console, to see how emails look like without deploying to production
   */
  def makeDummyEmail(isUser: Boolean, isAdded: Boolean, isSmall: Boolean): String = {
    val info = ThreadEmailInfo(
      Id[NormalizedURI](1),
      PublicId[Keep]("kASDF1234"),
      "Wikipedia",
      "The Interesting Page That Everyone Should Read",
      true,
      Some("http:www://example.com/image0.jpg"),
      Some("a cool description a cool description a cool description a cool description a cool description a cool description a cool description a cool description a cool description a cool description a cool description a cool description a cool description a cool description a cool description a cool description a cool description a cool description "),
      Seq("joe", "bob", "jack", "theguywithaverylongname"),
      "bob",
      None,
      Some("http://www.example.com/iwanttounsubscribe.html"),
      Some("http://www.example.com/iwanttomute.html"),
      Some(10),
      None
    )
    val threadItems = Seq(
      new ExtendedThreadItem("bob", "Bob Bob", Some("http:www://example.com/image1.png"), Seq(TextSegment("I say something"), TextSegment("Then something else"))),
      new ExtendedThreadItem("jack", "Jack Jack", Some("http:www://example.com/image2.png"), Seq(TextSegment("I say something"), TextSegment("Then something else")))
    )
    views.html.discussionEmail(info, threadItems, isUser, isAdded, isSmall).body
  }
}
