package com.keepit.eliza.commanders

import com.google.inject.Inject

import scala.concurrent.Future

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.twirl.api.Html

import java.net.URLDecoder

import org.joda.time.DateTime

import com.keepit.common.db.{ Id, ExternalId }
import com.keepit.eliza.model._
import com.keepit.social.NonUserKinds
import com.keepit.model._
import com.keepit.common.concurrent.FutureHelpers
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.common.store.ImageSize
import com.keepit.common.db.slick.Database
import com.keepit.common.mail.{
  ElectronicMail,
  SystemEmailAddress,
  EmailAddress,
  PostOffice
}
import com.keepit.common.time._
import com.keepit.common.net.URI
import com.keepit.common.akka.SafeFuture
import play.api.libs.json.JsString
import com.keepit.common.mail.EmailAddress
import com.keepit.common.logging.Logging
import com.keepit.eliza.util.{ MessageFormatter, TextSegment }
import com.keepit.common.strings.AbbreviateString
import com.keepit.common.domain.DomainToNameMapper
import com.keepit.scraper.ScraperServiceClient
import com.keepit.commanders.TimeToReadCommander

class ElizaEmailCommander @Inject() (
    shoebox: ShoeboxServiceClient,
    db: Database,
    nonUserThreadRepo: NonUserThreadRepo,
    userThreadRepo: UserThreadRepo,
    messageFetchingCommander: MessageFetchingCommander,
    messageRepo: MessageRepo,
    threadRepo: MessageThreadRepo,
    scraper: ScraperServiceClient,
    clock: Clock) extends Logging {

  case class ProtoEmail(digestHtml: Html, initialHtml: Html, addedHtml: Html, starterName: String, pageTitle: String)

  def getSummarySmall(thread: MessageThread) = {
    val fut = new SafeFuture(shoebox.getUriSummary(URISummaryRequest(
      uriId = thread.uriId.get,
      imageType = ImageType.IMAGE,
      minSize = ImageSize(183, 96),
      withDescription = true,
      waiting = true,
      silent = false)))
    fut.recover {
      case t: Throwable => throw new Exception(s"Error fetching small summary for thread: ${thread.id.get}", t)
    }
  }

  def getSummaryBig(thread: MessageThread) = {
    val fut = new SafeFuture(shoebox.getUriSummary(URISummaryRequest(
      uriId = thread.uriId.get,
      imageType = ImageType.IMAGE,
      minSize = ImageSize(620, 200),
      withDescription = false,
      waiting = true,
      silent = false)))
    fut.recover {
      case t: Throwable => throw new Exception(s"Error fetching big summary for thread: ${thread.id.get}", t)
    }
  }

  def getThreadEmailInfo(
    thread: MessageThread,
    uriSummary: URISummary,
    isInitialEmail: Boolean,
    allUsers: Map[Id[User], User],
    allUserImageUrls: Map[Id[User], String],
    invitedByUser: Option[User],
    unsubUrl: Option[String] = None,
    muteUrl: Option[String] = None,
    readTimeMinutes: Option[Int] = None): ThreadEmailInfo = {

    val (nuts, starterUserId) = db.readOnlyMaster { implicit session =>
      (
        nonUserThreadRepo.getByMessageThreadId(thread.id.get),
        userThreadRepo.getThreadStarter(thread.id.get)
      )
    }

    val starterUser = allUsers(starterUserId)
    val participants = allUsers.values.map { _.fullName } ++ nuts.map { _.participant.fullName }

    val pageName = thread.nUrl.flatMap(DomainToNameMapper.getNameFromUrl(_)).getOrElse("")

    ThreadEmailInfo(
      pageUrl = thread.nUrl.get,
      pageName = pageName,
      pageTitle = thread.pageTitle.orElse(uriSummary.title).getOrElse(thread.nUrl.get).abbreviate(80),
      isInitialEmail = isInitialEmail,
      heroImageUrl = uriSummary.imageUrl,
      pageDescription = uriSummary.description.map(_.take(190) + "..."),
      participants = participants.toSeq,
      conversationStarter = starterUser.firstName + " " + starterUser.lastName,
      invitedByUser = invitedByUser,
      unsubUrl = unsubUrl,
      muteUrl = muteUrl,
      readTimeMinutes = readTimeMinutes
    )
  }

  def getExtendedThreadItems(
    thread: MessageThread,
    allUsers: Map[Id[User], User],

    allUserImageUrls: Map[Id[User], String],
    fromTime: Option[DateTime],
    toTime: Option[DateTime]): Seq[ExtendedThreadItem] = {
    val messages = messageFetchingCommander.getThreadMessages(thread)

    val relevantMessages = messages.filter { m =>
      (fromTime.map { dt => m.createdAt.isAfter(dt.minusMillis(100)) } getOrElse (true)) &&
        (toTime.map { dt => m.createdAt.isBefore(dt.plusMillis(100)) } getOrElse (true))
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

  def readTimeMinutesForMessageThread(thread: MessageThread) = {
    (for {
      nUrlId <- thread.uriId
      url <- thread.url
    } yield {
      scraper.getURIWordCount(nUrlId, url) map { cnt => TimeToReadCommander.wordCountToReadTimeMinutes(cnt) }
    }) getOrElse Future.successful(None)
  }

  /**
   * Fetches all information that is common to all emails sent relative to a specific MessageThread.
   * This function should be called as few times as possible
   */
  def getThreadEmailData(thread: MessageThread): Future[ThreadEmailData] = {

    val allUserIds: Set[Id[User]] = thread.participants.map(_.allUsers).getOrElse(Set.empty)
    val allUsersFuture: Future[Map[Id[User], User]] = new SafeFuture(shoebox.getUsers(allUserIds.toSeq).map(s => s.map(u => u.id.get -> u).toMap))
    val allUserImageUrlsFuture: Future[Map[Id[User], String]] = new SafeFuture(FutureHelpers.map(allUserIds.map(u => u -> shoebox.getUserImageUrl(u, 73)).toMap))
    val uriSummaryBigFuture = getSummaryBig(thread)
    val readTimeMinutesOptFuture = readTimeMinutesForMessageThread(thread)

    for {
      allUsers <- allUsersFuture
      allUserImageUrls <- allUserImageUrlsFuture
      uriSummaryBig <- uriSummaryBigFuture
      uriSummarySmall <- getSummarySmall(thread) // Intentionally sequential execution
      readTimeMinutesOpt <- readTimeMinutesOptFuture
    } yield {
      ThreadEmailData(thread, allUserIds, allUsers, allUserImageUrls, uriSummaryBig, uriSummarySmall, readTimeMinutesOpt)
    }
  }

  private def assembleEmail(threadEmailData: ThreadEmailData, fromTime: Option[DateTime], toTime: Option[DateTime], invitedByUserId: Option[Id[User]], unsubUrl: Option[String], muteUrl: Option[String]): ProtoEmail = {
    val ThreadEmailData(thread, _, allUsers, allUserImageUrls, uriSummaryBig, uriSummarySmall, readTimeMinutesOpt) = threadEmailData
    val invitedByUser = invitedByUserId.flatMap(allUsers.get(_))
    val threadInfoSmall = getThreadEmailInfo(thread, uriSummarySmall, true, allUsers, allUserImageUrls, invitedByUser, unsubUrl, muteUrl, readTimeMinutesOpt)
    val threadInfoBig = threadInfoSmall.copy(heroImageUrl = uriSummaryBig.imageUrl.orElse(uriSummarySmall.imageUrl))
    val threadInfoSmallDigest = threadInfoSmall.copy(isInitialEmail = false)
    val threadItems = getExtendedThreadItems(thread, allUsers, allUserImageUrls, fromTime, toTime)

    ProtoEmail(
      views.html.discussionEmail(threadInfoSmallDigest, threadItems, false, false, true),
      if (uriSummaryBig.imageUrl.isDefined) views.html.discussionEmail(threadInfoBig, threadItems, false, false, false)
      else views.html.discussionEmail(threadInfoSmall, threadItems, false, false, true),
      views.html.discussionEmail(threadInfoSmall, threadItems, false, true, true),
      threadInfoSmall.conversationStarter,
      threadInfoSmall.pageTitle
    )
  }

  def notifyEmailUsers(thread: MessageThread): Unit = if (thread.participants.exists(_.allNonUsers.nonEmpty)) {
    getThreadEmailData(thread) map { threadEmailData =>
      val nuts = db.readOnlyMaster { implicit session =>
        nonUserThreadRepo.getByMessageThreadId(thread.id.get)
      }

      // Intentionally sequential execution

      def notify(toBeNotified: Seq[NonUserThread]): Unit = toBeNotified match {
        case Seq() => // done
        case Seq(nut, moreNuts @ _*) => notifyEmailParticipant(nut, threadEmailData) onComplete { _ => notify(moreNuts) }
      }
      notify(nuts)
    }
  }

  def notifyAddedEmailUsers(thread: MessageThread, addedNonUsers: Seq[NonUserParticipant]): Unit = if (thread.participants.exists(!_.allNonUsers.isEmpty)) {
    getThreadEmailData(thread) map { threadEmailData =>
      val nuts = db.readOnlyMaster { implicit session => //redundant right now but I assume we will want to let everyone in the thread know that someone was added?
        nonUserThreadRepo.getByMessageThreadId(thread.id.get).map { nut =>
          nut.participant.identifier -> nut
        }.toMap
      }
      addedNonUsers.map { nup =>
        require(nup.kind == NonUserKinds.email)
        val nut = nuts(nup.identifier)
        if (!nut.muted) {
          safeProcessEmail(threadEmailData, nut, _.addedHtml.body, NotificationCategory.NonUser.ADDED_TO_DISCUSSION)
        } else Future.successful(())
      }
    }
  }

  def notifyEmailParticipant(emailParticipantThread: NonUserThread, threadEmailData: ThreadEmailData): Future[Unit] = {
    val result = if (emailParticipantThread.muted) Future.successful(()) else {
      require(emailParticipantThread.participant.kind == NonUserKinds.email, s"NonUserThread ${emailParticipantThread.id.get} does not represent an email participant.")
      require(emailParticipantThread.threadId == threadEmailData.thread.id.get, "MessageThread and NonUserThread do not match.")
      val category = if (emailParticipantThread.notifiedCount > 0) NotificationCategory.NonUser.DISCUSSION_UPDATES else NotificationCategory.NonUser.DISCUSSION_STARTED
      val htmlBodyMaker = (protoEmail: ProtoEmail) => if (emailParticipantThread.notifiedCount > 0) protoEmail.digestHtml.body else protoEmail.initialHtml.body
      safeProcessEmail(threadEmailData, emailParticipantThread, htmlBodyMaker, category)
    }
    // todo(martin) replace with onSuccess when we have better error handling
    result.onComplete { _ =>
      db.readWrite { implicit session => nonUserThreadRepo.setLastNotifiedAndIncCount(emailParticipantThread.id.get) }
    }
    result
  }

  private def safeProcessEmail(threadEmailData: ThreadEmailData, nonUserThread: NonUserThread, htmlBodyMaker: ProtoEmail => String, category: NotificationCategory): Future[Unit] = {
    val unsubUrlFut = nonUserThread.participant match {
      case emailParticipant: NonUserEmailParticipant => shoebox.getUnsubscribeUrlForEmail(emailParticipant.address)
      case _ => throw new IllegalArgumentException(s"Unknown non user participant: ${nonUserThread.participant}")
    }

    val protoEmailFut = unsubUrlFut map { unsubUrl =>
      assembleEmail(
        threadEmailData,
        None,
        None,
        Some(nonUserThread.createdBy),
        Some(unsubUrl),
        Some("https://www.kifi.com/extmsg/email/mute?publicId=" + nonUserThread.accessToken.token)
      )
    }

    protoEmailFut.flatMap { protoEmail =>
      val magicAddress = SystemEmailAddress.discussion(nonUserThread.accessToken.token)
      val email = ElectronicMail(
        from = magicAddress,
        fromName = Some(protoEmail.starterName + " (via Kifi)"),
        to = Seq[EmailAddress](EmailAddress(nonUserThread.participant.identifier)),
        subject = protoEmail.pageTitle,
        htmlBody = htmlBodyMaker(protoEmail),
        category = category,
        extraHeaders = Some(Map(PostOffice.Headers.REPLY_TO -> magicAddress.address))
      )
      shoebox.sendMail(email) map {
        case true => // all good
        case false => throw new Exception("Shoebox was unable to parse and send the email.")
      }
    }
  }

  def getEmailPreview(msgExtId: ExternalId[Message]): Future[Html] = {
    val (msg, thread) = db.readOnlyReplica { implicit session =>
      val msg = messageRepo.get(msgExtId)
      val thread = threadRepo.get(msg.thread)
      (msg, thread)
    }
    val protoEmailFuture = getThreadEmailData(thread) map { assembleEmail(_, None, None, None, None, None) }
    if (msg.auxData.isDefined) {
      if (msg.auxData.get.value(0) == JsString("start_with_emails")) {
        protoEmailFuture.map(_.initialHtml)
      } else {
        protoEmailFuture.map(_.addedHtml)
      }
    } else {
      protoEmailFuture.map(_.digestHtml)
    }

  }
}

object ElizaEmailCommander {

  /**
   * This function is meant to be used from the console, to see how emails look like without deploying to production
   */
  def makeDummyEmail(isUser: Boolean, isAdded: Boolean, isSmall: Boolean): String = {
    val info = ThreadEmailInfo(
      "http://www.wikipedia.org/aninterstingpage.html",
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
      Some(10)
    )
    val threadItems = Seq(
      new ExtendedThreadItem("bob", "Bob Bob", Some("http:www://example.com/image1.png"), Seq(TextSegment("I say something"), TextSegment("Then something else"))),
      new ExtendedThreadItem("jack", "Jack Jack", Some("http:www://example.com/image2.png"), Seq(TextSegment("I say something"), TextSegment("Then something else")))
    )
    views.html.discussionEmail(info, threadItems, isUser, isAdded, isSmall).body
  }
}
