package com.keepit.eliza.commanders

import com.google.inject.Inject

import scala.concurrent.Future

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.templates.Html

import java.net.URLDecoder

import org.joda.time.DateTime

import com.keepit.common.db.{Id, ExternalId}
import com.keepit.eliza.model._
import com.keepit.social.NonUserKinds
import com.keepit.model._
import com.keepit.common.concurrent.FutureHelpers
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.common.store.ImageSize
import com.keepit.common.db.slick.Database
import com.keepit.common.mail.{
  ElectronicMail,
  EmailAddresses,
  EmailAddressHolder,
  PostOffice
}
import com.keepit.common.time._
import com.keepit.common.net.URI
import com.keepit.common.akka.SafeFuture
import play.api.libs.json.JsString
import com.keepit.common.mail.GenericEmailAddress
import com.keepit.eliza.mail.DomainToNameMapper
import scala.util.{Failure, Success}
import com.keepit.common.logging.Logging

abstract class MessageSegment(val kind: String, val txt: String) //for use in templates since you can't match on type (it seems)
case class TextLookHereSegment(override val txt: String, pageText: String) extends MessageSegment("tlh", txt)
case class ImageLookHereSegment(override val txt: String, imgUrl: String) extends MessageSegment("ilh", txt)
case class TextSegment(override val txt: String) extends MessageSegment("txt", txt)

class ElizaEmailCommander @Inject() (
    shoebox: ShoeboxServiceClient,
    db: Database,
    nonUserThreadRepo: NonUserThreadRepo,
    userThreadRepo: UserThreadRepo,
    messageFetchingCommander: MessageFetchingCommander,
    messageRepo: MessageRepo,
    threadRepo: MessageThreadRepo,
    wordCountCommander: WordCountCommander,
    clock: Clock
  ) extends Logging {

  case class ProtoEmail(digestHtml: Html, initialHtml: Html, addedHtml: Html, starterName: String, pageTitle: String)

  def getSummarySmall(thread: MessageThread) = {
    val fut = new SafeFuture(shoebox.getUriSummary(URISummaryRequest(
      url = thread.nUrl.get,
      imageType = ImageType.ANY,
      minSize = ImageSize(183, 96),
      withDescription = true,
      waiting = true,
      silent = false)))
    fut.recover {
      case t: Throwable => throw new Exception(s"Error fetching small summary for thread: ${thread.id.get}. Exception was: $t")
    }
  }

  def getSummaryBig(thread: MessageThread) = {
    val fut = new SafeFuture(shoebox.getUriSummary(URISummaryRequest(
      url = thread.nUrl.get,
      imageType = ImageType.IMAGE,
      minSize = ImageSize(620, 200),
      withDescription = false,
      waiting = true,
      silent = false)))
    fut.recover {
      case t: Throwable => throw new Exception(s"Error fetching big summary for thread: ${thread.id.get}. Exception was: $t")
    }
  }

  def getThreadEmailInfo(
    thread: MessageThread,
    uriSummary: URISummary,
    isInitialEmail: Boolean,
    allUsers: Map[Id[User], User],
    allUserImageUrls: Map[Id[User], String],
    unsubUrl: Option[String] = None,
    muteUrl: Option[String] = None,
    readTimeMinutes: Option[Int] = None): ThreadEmailInfo = {

    val (nuts, starterUserId) = db.readOnly { implicit session => (
      nonUserThreadRepo.getByMessageThreadId(thread.id.get),
      userThreadRepo.getThreadStarter(thread.id.get)
    )}

    val starterUser = allUsers(starterUserId)
    val participants = allUsers.values.map { _.fullName } ++ nuts.map { _.participant.fullName }

    val pageName = thread.nUrl.flatMap { url =>
      val hostOpt = URI.parse(url).toOption.flatMap(_.host)
      hostOpt map { host =>
        def nameForSuffixLength(n: Int) = DomainToNameMapper.getName(host.domain.take(n).reverse.mkString("."))
        // Attempts to map more restrictive subdomains first
        val candidates = (host.domain.length to 2 by -1).toStream map nameForSuffixLength
        candidates.collectFirst { case Some(name) => name }.getOrElse(host.name)
      }
    }.getOrElse("")

    ThreadEmailInfo(
      pageUrl = thread.nUrl.get,
      pageName = pageName,
      pageTitle = uriSummary.title.getOrElse(thread.nUrl.get),
      isInitialEmail = isInitialEmail,
      heroImageUrl = uriSummary.imageUrl,
      pageDescription = uriSummary.description.map(_.take(190) + "..."),
      participants = participants.toSeq,
      conversationStarter = starterUser.firstName + " " + starterUser.lastName,
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

    val relevantMessages = messages.filter{ m =>
      (fromTime.map{ dt => m.createdAt.isAfter(dt.minusMillis(100)) } getOrElse(true)) &&
      (toTime.map{ dt => m.createdAt.isBefore(dt.plusMillis(100)) } getOrElse(true))
    }

    relevantMessages.filterNot(_.from.isSystem).map{ message =>
      val messageSegments = ElizaEmailCommander.parseMessage(message.messageText)
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
      wordCountCommander.getReadTimeMinutes(nUrlId, url)
    }) getOrElse Future.successful(None)
  }

  private def assembleEmail(thread: MessageThread, fromTime: Option[DateTime], toTime: Option[DateTime], unsubUrl: Option[String], muteUrl: Option[String]): Future[ProtoEmail] = {

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
      val threadInfoSmall = getThreadEmailInfo(thread, uriSummarySmall, true, allUsers, allUserImageUrls, unsubUrl, muteUrl, readTimeMinutesOpt)
      val threadInfoBig = threadInfoSmall.copy(heroImageUrl = uriSummaryBig.imageUrl.orElse(uriSummarySmall.imageUrl))
      val threadInfoSmallDigest = threadInfoSmall.copy(isInitialEmail = false)
      val threadItems = getExtendedThreadItems(thread, allUsers, allUserImageUrls, fromTime, toTime)

      ProtoEmail(
        views.html.next.nonUserEmailImageSmall(threadInfoSmallDigest, threadItems),
        /*if (uriSummaryBig.imageUrl.isDefined) views.html.nonUserEmailImageBig(threadInfoBig, threadItems)
        else*/ views.html.next.nonUserEmailImageSmall(threadInfoSmall, threadItems),
        views.html.nonUserAddedDigestEmail(threadInfoSmall, threadItems),
        threadInfoSmall.conversationStarter,
        uriSummarySmall.title.getOrElse(threadInfoSmall.pageName)
      )
    }
  }

  def notifyEmailUsers(thread: MessageThread): Unit = if (thread.participants.exists(!_.allNonUsers.isEmpty)) {
    val nuts = db.readOnly { implicit session =>
      nonUserThreadRepo.getByMessageThreadId(thread.id.get)
    }
    // Intentionally sequential execution
    nuts.foldLeft(Future.successful()){ (fut,nut) =>
      fut.flatMap(_ => notifyEmailParticipant(nut, thread))
    }
  }

  def notifyAddedEmailUsers(thread: MessageThread, addedNonUsers: Seq[NonUserParticipant]): Unit = if (thread.participants.exists(!_.allNonUsers.isEmpty)) {
    val nuts = db.readOnly { implicit session => //redundant right now but I assume we will want to let everyone in the thread know that someone was added?
      nonUserThreadRepo.getByMessageThreadId(thread.id.get).map { nut =>
        nut.participant.identifier -> nut
      }.toMap
    }

    // Intentionally sequential execution
    addedNonUsers.foldLeft(Future.successful()){ (fut,nup) =>
      require(nup.kind == NonUserKinds.email)
      val nut = nuts(nup.identifier)
      fut.flatMap { _ =>
        if (!nut.muted) {
          safeProcessEmail(thread, nut, _.addedHtml.body, NotificationCategory.NonUser.ADDED_TO_DISCUSSION)
        } else Future.successful()
      }
    }
  }

  def notifyEmailParticipant(emailParticipantThread: NonUserThread, thread: MessageThread): Future[Unit] = if (!emailParticipantThread.muted) {
    require(emailParticipantThread.participant.kind == NonUserKinds.email, s"NonUserThread ${emailParticipantThread.id.get} does not represent an email participant.")
    require(emailParticipantThread.threadId == thread.id.get, "MessageThread and NonUserThread do not match.")
    val category = if (emailParticipantThread.notifiedCount > 0) NotificationCategory.NonUser.DISCUSSION_UPDATES else NotificationCategory.NonUser.DISCUSSION_STARTED
    val htmlBodyMaker = (protoEmail: ProtoEmail) => if (emailParticipantThread.notifiedCount > 0) protoEmail.digestHtml.body else protoEmail.initialHtml.body
    safeProcessEmail(thread, emailParticipantThread, htmlBodyMaker, category)
  } else Future.successful()

  private def safeProcessEmail(messageThread: MessageThread, nonUserThread: NonUserThread, htmlBodyMaker: ProtoEmail => String, category: NotificationCategory): Future[Unit] = {
    // Update records even if sending the email fails (avoid infinite failure loops)
    // todo(martin) persist failures to database
    db.readWrite{ implicit session => nonUserThreadRepo.setLastNotifiedAndIncCount(nonUserThread.id.get, clock.now()) }
    val protoEmailFut = for {
      unsubUrl <- shoebox.getUnsubscribeUrlForEmail(nonUserThread.participant.identifier);
      protoEmail <- assembleEmail(messageThread, nonUserThread.lastNotifiedAt, None, Some(unsubUrl), Some("https://www.kifi.com/extmsg/email/mute?publicId=" + nonUserThread.accessToken.token))
    } yield protoEmail
    protoEmailFut.onComplete { res =>
      res match {
        case Success(protoEmail) => {
          val magicAddress = EmailAddresses.discussion(nonUserThread.accessToken.token)
          shoebox.sendMail(ElectronicMail (
            from = magicAddress,
            fromName = Some(protoEmail.starterName + " (via Kifi)"),
            to = Seq[EmailAddressHolder](GenericEmailAddress(nonUserThread.participant.identifier)),
            subject = protoEmail.pageTitle,
            htmlBody = htmlBodyMaker(protoEmail),
            category = category,
            extraHeaders = Some(Map(PostOffice.Headers.REPLY_TO -> magicAddress.address))
          ))
        }
        case Failure(t) => log.error(s"Failed to notify NonUserThread ${nonUserThread.id} via email: $t")
      }
    }
    protoEmailFut map (_ => ())
  }

  def getEmailPreview(msgExtId: ExternalId[Message]): Future[Html] = {
    val (msg, thread) = db.readOnly{ implicit session =>
      val msg = messageRepo.get(msgExtId)
      val thread = threadRepo.get(msg.thread)
      (msg, thread)
    }
    val protoEmailFuture = assembleEmail(thread, None, Some(msg.createdAt), None, None)
    if (msg.auxData.isDefined) {
      if (msg.auxData.get.value(0)==JsString("start_with_emails")) {
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

  def parseMessage(msg: String): Seq[MessageSegment] = {
    try {
      val re = """\[((?:(?:[^\]]*)\\\])*(?:[^\]]*)+)\](\(x-kifi-sel:((?:(?:[^\)]*)\\\))*(?:[^\)]*)+)\))""".r
      val tokens : Seq[String] = re.replaceAllIn(msg.replace("\t", " "), m => "\t" + m.matched.replace(")","\\)") + "\t").split('\t').map(_.trim).filter(_.length>0)
      tokens.map{ token =>
        re.findFirstMatchIn(token).map { m =>
          val segments = m.group(3).split('|')
          val kind = segments.head
          val payload = URLDecoder.decode(segments.last, "UTF-8")
          val text = m.group(1)
          kind match {
            case "i" => ImageLookHereSegment(text, payload)
            case "r" => TextLookHereSegment(text, payload)
            case _ => throw new Exception("Unknown look-here type: " + kind)
          }
        } getOrElse {
          TextSegment(token)
        }
      }
    } catch {
      case t: Throwable => {
        throw new Exception(s"Exception during parsing of message $msg. Exception was $t")
      }
    }
  }
}
