package com.keepit.eliza.commanders

import com.google.inject.Inject

import scala.concurrent.Future
import scala.util.matching.Regex.Match

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.templates.Html

import java.net.URLDecoder

import org.joda.time.DateTime

import com.keepit.common.db.{Id, ExternalId}
import com.keepit.eliza.model.{
  MessageThread,
  NonUserThreadRepo,
  UserThreadRepo,
  ThreadEmailInfo,
  ExtendedThreadItem,
  MessageSender,
  Message,
  MessageRepo,
  MessageThreadRepo
}
import com.keepit.social.NonUserKinds
import com.keepit.model.{URISummaryRequest, URISummary, User, ImageType}
import com.keepit.common.concurrent.FutureHelpers
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.common.store.ImageSize
import com.keepit.common.db.slick.Database
import com.keepit.common.mail.{
  ElectronicMail,
  ElectronicMailCategory,
  EmailAddresses,
  GenericEmailAddress,
  EmailAddressHolder,
  PostOffice
}
import com.keepit.common.time._
import com.keepit.common.net.URI


class ElizaEmailCommander @Inject() (
    shoebox: ShoeboxServiceClient,
    db: Database,
    nonUserThreadRepo: NonUserThreadRepo,
    userThreadRepo: UserThreadRepo,
    messageFetchingCommander: MessageFetchingCommander,
    messageRepo: MessageRepo,
    threadRepo: MessageThreadRepo
  ) {

  case class CleanedMessage(cleanText: String, lookHereTexts: Seq[String], lookHereImageUrls: Seq[String])

  case class ProtoEmail(html: Html, starterName: String, pageTitle: String)

  private def parseMessage(msg: String): CleanedMessage = {
    val re = """\[((?:\\\]|[^\]])*)\](\(x-kifi-sel:((?:\\\)|[^)])*)\))""".r
    var textLookHeres = Vector[String]()
    var imageLookHereUrls = Vector[String]()
    re.findAllMatchIn(msg).toList.foreach { m =>
      val segments = m.group(3).split('|')
      val kind = segments.head
      val payload = URLDecoder.decode(segments.last, "UTF-8")
      kind match {
        case "i" => imageLookHereUrls = imageLookHereUrls :+ payload
        case "r" => textLookHeres = textLookHeres :+ payload
        case _ => throw new Exception("Unknown look-here type: " + kind)
      }
    }
    CleanedMessage(re.replaceAllIn(msg, (m: Match) => m.group(1)), textLookHeres, imageLookHereUrls)
  }


  def assembleEmail(thread: MessageThread, fromTime: Option[DateTime], toTime: Option[DateTime], unsubUrl: Option[String], muteUrl: Option[String]): Future[ProtoEmail] = {

    val allUserIds : Set[Id[User]] = thread.participants.map(_.allUsers).getOrElse(Set.empty)

    val allUsersFuture : Future[Map[Id[User], User]] = shoebox.getUsers(allUserIds.toSeq).map( s => s.map(u => u.id.get -> u).toMap)

    val allUserImageUrlsFuture : Future[Map[Id[User], String]] = FutureHelpers.map(allUserIds.map( u => u -> shoebox.getUserImageUrl(u, 73)).toMap)

    val uriSummaryFuture : Future[URISummary] = shoebox.getUriSummary(URISummaryRequest(
      url = thread.nUrl.get,
      imageType = ImageType.ANY,
      minSize = ImageSize(183, 96),
      withDescription = true,
      waiting = true,
      silent = false))

    for (allUsers <- allUsersFuture; allUserImageUrls <- allUserImageUrlsFuture; uriSummary <- uriSummaryFuture) yield {

      val (nuts, starterUserId) = db.readOnly { implicit session =>
        (
          nonUserThreadRepo.getByMessageThreadId(thread.id.get),
          userThreadRepo.getThreadStarter(thread.id.get)
        )
      }

      val starterUser = allUsers(starterUserId)

      val participants = allUsers.values.map{ u => u.fullName } ++ nuts.map{ nut => nut.participant.fullName }

      val pageName = thread.nUrl.flatMap( url => URI.parse(url).toOption.flatMap( uri => uri.host.map(_.name)) ).get

      val messages = messageFetchingCommander.getThreadMessages(thread)

      val threadInfo = ThreadEmailInfo(
        pageUrl = thread.nUrl.get,
        pageName = pageName,
        pageTitle = uriSummary.title.getOrElse(thread.nUrl.get),
        heroImageUrl = uriSummary.imageUrl,
        pageDescription = uriSummary.description,
        participants = participants.toSeq,
        conversationStarter = starterUser.firstName + " " + starterUser.lastName,
        unsubUrl = unsubUrl.getOrElse("#"),
        muteUrl = muteUrl.getOrElse("#")
      )

      var relevantMessages = messages
      fromTime.map{ dt =>
        relevantMessages = relevantMessages.filter{ m =>
          m.createdAt.isAfter(dt.minusMillis(100))
        }
      }
      toTime.map{ dt =>
        relevantMessages = relevantMessages.filter{ m =>
          m.createdAt.isBefore(dt.plusMillis(100))
        }
      }

      val threadItems = relevantMessages.filterNot(_.from.isSystem).map{ message =>
        val CleanedMessage(text, lookHereTexts, lookHereImageUrls) = parseMessage(message.messageText)
        message.from match {
          case MessageSender.User(id) => ExtendedThreadItem(allUsers(id).shortName, allUsers(id).fullName, Some(allUserImageUrls(id)), text, lookHereTexts, lookHereImageUrls)
          case MessageSender.NonUser(nup) => {
            ExtendedThreadItem(nup.shortName, nup.fullName, None, text, lookHereTexts, Seq.empty)
          }
          case _ => throw new Exception("Impossible")
        }
      }.reverse

      ProtoEmail(
        views.html.nonUserDigestEmail(threadInfo, threadItems),
        starterUser.firstName + " " + starterUser.lastName,
        uriSummary.title.getOrElse(pageName)
      )
    }
  }



  def notifyEmailUsers(thread: MessageThread): Unit = if (thread.participants.exists(!_.allNonUsers.isEmpty)) {
    val nuts = db.readOnly { implicit session =>
      nonUserThreadRepo.getByMessageThreadId(thread.id.get)
    }

    nuts.filterNot(_.muted).foreach{ nut =>
      require(nut.participant.kind == NonUserKinds.email)

      for (
        unsubUrl <- shoebox.getUnsubscribeUrlForEmail(nut.participant.identifier);
        protoEmail <- assembleEmail(thread, nut.lastNotifiedAt, None, Some(unsubUrl), Some("https://www.kifi.com/extmsg/email/mute?publicId=" + nut.accessToken.token))
      ) {

        val magicAddress = EmailAddresses.discussion(nut.accessToken.token)
        shoebox.sendMail(ElectronicMail (
          from = magicAddress,
          fromName = Some(protoEmail.starterName + " (via Kifi)"),
          to = Seq[EmailAddressHolder](GenericEmailAddress(nut.participant.identifier)),
          subject = "Kifi Discussion on " + protoEmail.pageTitle,
          htmlBody = protoEmail.html.body,
          category = ElectronicMailCategory("external_message_test"),
          extraHeaders = Some(Map(PostOffice.Headers.REPLY_TO -> magicAddress.address))
        ))
        db.readWrite{ implicit session => nonUserThreadRepo.setLastNotifiedAndIncCount(nut.id.get, currentDateTime) }
      }
    }
  }


  def getEmailPreview(msgExtId: ExternalId[Message]): Future[Html] = {
    val (msg, thread) = db.readOnly{ implicit session =>
      val msg = messageRepo.get(msgExtId)
      val thread = threadRepo.get(msg.thread)
      (msg, thread)
    }
    assembleEmail(thread, None, Some(msg.createdAt), None, None).map(_.html)
  }




}
