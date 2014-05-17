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

abstract class MessageSegment(val kind: String) //for use in templates since you can't match on type (it seems)
case class TextLookHereSegment(msgText: String, pageText: String) extends MessageSegment("tlh")
case class ImageLookHereSegment(msgText: String, imgUrl: String) extends MessageSegment("ilh")
case class TextSegment(txt: String) extends MessageSegment("txt")

class ElizaEmailCommander @Inject() (
    shoebox: ShoeboxServiceClient,
    db: Database,
    nonUserThreadRepo: NonUserThreadRepo,
    userThreadRepo: UserThreadRepo,
    messageFetchingCommander: MessageFetchingCommander,
    messageRepo: MessageRepo,
    threadRepo: MessageThreadRepo,
    clock: Clock
  ) {

  case class ProtoEmail(digestHtml: Html, initialHtml: Html, addedHtml: Html, starterName: String, pageTitle: String)


  private def parseMessage(msg: String): Seq[MessageSegment] = {
    val re = """\[((?:\\\]|[^\]])*)\](\(x-kifi-sel:((?:\\\)|[^)])*)\))""".r
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
  }

  def getSummarySmall(thread: MessageThread) = {
    new SafeFuture(shoebox.getUriSummary(URISummaryRequest(
      url = thread.nUrl.get,
      imageType = ImageType.ANY,
      minSize = ImageSize(183, 96),
      withDescription = true,
      waiting = true,
      silent = false)))
  }

  def getSummaryBig(thread: MessageThread) = {
    new SafeFuture(shoebox.getUriSummary(URISummaryRequest(
      url = thread.nUrl.get,
      imageType = ImageType.IMAGE,
      minSize = ImageSize(620, 200),
      withDescription = false,
      waiting = true,
      silent = false)))
  }

  def getThreadEmailInfo(
    thread: MessageThread,
    uriSummary: URISummary,
    allUsers: Map[Id[User], User],
    allUserImageUrls: Map[Id[User], String],
    unsubUrl: Option[String] = None,
    muteUrl: Option[String] = None): ThreadEmailInfo = {

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
      heroImageUrl = uriSummary.imageUrl,
      pageDescription = uriSummary.description.map(_.take(190) + "..."),
      participants = participants.toSeq,
      conversationStarter = starterUser.firstName + " " + starterUser.lastName,
      unsubUrl = unsubUrl,
      muteUrl = muteUrl
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
      val messageSegments = parseMessage(message.messageText)
      message.from match {
        case MessageSender.User(id) => ExtendedThreadItem(allUsers(id).shortName, allUsers(id).fullName, Some(allUserImageUrls(id)), messageSegments)
        case MessageSender.NonUser(nup) => {
          ExtendedThreadItem(nup.shortName, nup.fullName, None, messageSegments)
        }
        case _ => throw new Exception("Impossible")
      }
    }.reverse
  }

  private def assembleEmail(thread: MessageThread, fromTime: Option[DateTime], toTime: Option[DateTime], unsubUrl: Option[String], muteUrl: Option[String]): Future[ProtoEmail] = {

    val allUserIds: Set[Id[User]] = thread.participants.map(_.allUsers).getOrElse(Set.empty)
    val allUsersFuture: Future[Map[Id[User], User]] = new SafeFuture(shoebox.getUsers(allUserIds.toSeq).map(s => s.map(u => u.id.get -> u).toMap))
    val allUserImageUrlsFuture: Future[Map[Id[User], String]] = allUsersFuture.map { allUsers =>
      allUsers.mapValues { u =>
        "//djty7jcqog9qu.cloudfront.net/users/" + u.externalId + "/pics/100/" + u.pictureName.getOrElse("0") + ".jpg"
      }
    } //new SafeFuture(FutureHelpers.map(allUserIds.map(u => u -> shoebox.getUserImageUrl(u, 73)).toMap))
    val uriSummaryBigFuture = getSummaryBig(thread)

    for {
      allUsers <- allUsersFuture
      allUserImageUrls <- allUserImageUrlsFuture
      uriSummaryBig <- uriSummaryBigFuture
      uriSummarySmall <- getSummarySmall(thread) // Intentionally sequential execution
    } yield {
      val threadInfoSmall = getThreadEmailInfo(thread, uriSummarySmall, allUsers, allUserImageUrls, unsubUrl, muteUrl)
      val threadInfoBig = threadInfoSmall.copy(heroImageUrl = uriSummaryBig.imageUrl.orElse(uriSummarySmall.imageUrl))
      val threadItems = getExtendedThreadItems(thread, allUsers, allUserImageUrls, fromTime, toTime)

      ProtoEmail(
        views.html.nonUserDigestEmail(threadInfoSmall, threadItems),
        if (uriSummaryBig.imageUrl.isDefined) views.html.nonUserInitialEmail(threadInfoBig, threadItems)
        else views.html.nonUserDigestEmail(threadInfoSmall, threadItems),
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
      fut.flatMap { _ => if (!nut.muted) {
        for (
          unsubUrl <- shoebox.getUnsubscribeUrlForEmail(nut.participant.identifier);
          protoEmail <- assembleEmail(thread, nut.lastNotifiedAt, None, Some(unsubUrl), Some("https://www.kifi.com/extmsg/email/mute?publicId=" + nut.accessToken.token))
        ) yield {
          val magicAddress = EmailAddresses.discussion(nut.accessToken.token)
          shoebox.sendMail(ElectronicMail (
            from = magicAddress,
            fromName = Some(protoEmail.starterName + " (via Kifi)"),
            to = Seq[EmailAddressHolder](GenericEmailAddress(nut.participant.identifier)),
            subject = protoEmail.pageTitle,
            htmlBody = protoEmail.addedHtml.body,
            category = NotificationCategory.NonUser.ADDED_TO_DISCUSSION,
            extraHeaders = Some(Map(PostOffice.Headers.REPLY_TO -> magicAddress.address))
          ))
          db.readWrite{ implicit session => nonUserThreadRepo.setLastNotifiedAndIncCount(nut.id.get, clock.now()) }
        }} else Future.successful()
      }
    }
  }

  def notifyEmailParticipant(emailParticipantThread: NonUserThread, thread: MessageThread): Future[Unit] = if (!emailParticipantThread.muted) {
    require(emailParticipantThread.participant.kind == NonUserKinds.email, s"NonUserThread ${emailParticipantThread.id.get} does not represent an email participant.")
    require(emailParticipantThread.threadId == thread.id.get, "MessageThread and NonUserThread do not match.")
    for (
      unsubUrl <- shoebox.getUnsubscribeUrlForEmail(emailParticipantThread.participant.identifier);
      protoEmail <- assembleEmail(thread, None, None, Some(unsubUrl), Some("https://www.kifi.com/extmsg/email/mute?publicId=" + emailParticipantThread.accessToken.token))
    ) yield {

      val magicAddress = EmailAddresses.discussion(emailParticipantThread.accessToken.token)
      shoebox.sendMail(ElectronicMail (
        from = magicAddress,
        fromName = Some(protoEmail.starterName + " (via Kifi)"),
        to = Seq[EmailAddressHolder](GenericEmailAddress(emailParticipantThread.participant.identifier)),
        subject = protoEmail.pageTitle,
        htmlBody = if (emailParticipantThread.notifiedCount > 0) protoEmail.digestHtml.body else protoEmail.initialHtml.body,
        category = if (emailParticipantThread.notifiedCount > 0) NotificationCategory.NonUser.DISCUSSION_UPDATES else NotificationCategory.NonUser.DISCUSSION_STARTED,
        extraHeaders = Some(Map(PostOffice.Headers.REPLY_TO -> magicAddress.address))
      ))
      db.readWrite{ implicit session => nonUserThreadRepo.setLastNotifiedAndIncCount(emailParticipantThread.id.get, clock.now()) }
    }
  } else Future.successful()

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
