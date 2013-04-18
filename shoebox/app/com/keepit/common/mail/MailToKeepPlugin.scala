package com.keepit.common.mail

import scala.concurrent.duration._

import org.jsoup.Jsoup

import com.google.inject.{ImplementedBy, Inject}
import com.keepit.common.actor.ActorFactory
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.analytics.{EventFamilies, Events, PersistEventPlugin}
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.HealthcheckPlugin
import com.keepit.common.logging.Logging
import com.keepit.common.net.URI
import com.keepit.common.plugin.SchedulingPlugin
import com.keepit.controllers.core.BookmarkInterner
import com.keepit.model.{EmailAddressRepo, User, UserRepo}
import com.keepit.common.time._
import com.keepit.common.service.FortyTwoServices

import javax.mail.Message.RecipientType
import javax.mail._
import javax.mail.internet.{InternetAddress, MimeMultipart}
import javax.mail.search._
import play.api.libs.json.Json

private case object FetchNewKeeps

case class MailToKeepServerSettings(
  username: String,
  password: String,
  server: String,
  protocol: String,
  emailLabel: Option[String] = None
)

private sealed abstract class KeepType(val name: String, val emailPrefix: String) {
  override def toString = name
}

private object KeepType {
  object Public extends KeepType("public", "keep")
  object Private extends KeepType("private", "private")
  val allTypes: Seq[KeepType] = Seq(Public, Private)
}

class MailToKeepActor @Inject() (
    healthcheckPlugin: HealthcheckPlugin,
    settings: MailToKeepServerSettings,
    bookmarkInterner: BookmarkInterner,
    persistEventPlugin: PersistEventPlugin,
    postOffice: PostOffice,
    messageParser: MailToKeepMessageParser,
    implicit private val clock: Clock,
    implicit private val fortyTwoServices: FortyTwoServices
  ) extends FortyTwoActor(healthcheckPlugin) with Logging {

  // add +$emailLabel to the end if provided
  // this is so in dev mode we can append your username so as not to conflict with prod emails
  private val KeepEmail = raw"""^(\w+)${settings.emailLabel.map("""\+""" + _).getOrElse("")}@[\w\.]+$$""".r

  private object KeepSearchTerm extends SearchTerm {
    def `match`(m: Message) =
      m.getAllRecipients.map(messageParser.getAddr).exists(KeepEmail.findFirstIn(_).isDefined)
  }

  private lazy val mailSession: Session = {
    val props = System.getProperties()
    props.setProperty("mail.store.protocol", settings.protocol)
    Session.getInstance(props, null)
  }

  def receive = {
    case FetchNewKeeps =>
      val store = mailSession.getStore
      try {
        log.info("Looking for keeps by email")
        store.connect(settings.server, settings.username, settings.password)
        val inbox = store.getFolder("Inbox")
        inbox.open(Folder.READ_WRITE)
        val messages = inbox.search(KeepSearchTerm)
        for (message <- messages) {
          val prefixes = message.getAllRecipients.map(messageParser.getAddr).map {
            KeepEmail.findFirstMatchIn(_).map(_.group(1).toLowerCase.trim)
          }.flatten
          for (keepType <- KeepType.allTypes.filter(prefixes contains _.emailPrefix)) {
            val senderAddr = messageParser.getSenderAddr(message)
            (messageParser.getUser(message), messageParser.getUris(message)) match {
              case (None, _) =>
                sendReply(
                  message = message,
                  htmlBody = s"<p>Kifi could not find a user for $senderAddr.</p>"
                )
              case (Some(user), Seq()) =>
                sendReply(
                  message = message,
                  htmlBody =
                      s"<p>Hi ${user.firstName},</p>" +
                      "<p>We couldn't find any URLs in your message. Try making sure your URL format is valid.</p>"
                )
              case (Some(user), uris) =>
                for (uri <- uris) {
                  val bookmark = bookmarkInterner.internBookmarks(Json.obj(
                    "url" -> uri.toString,
                    "isPrivate" -> (keepType == KeepType.Private)
                  ), user, Seq(), "EMAIL").head
                  log.info(s"created bookmark from email with id ${bookmark.id.get}")
                  val event = Events.serverEvent(EventFamilies.GENERIC_SERVER, "email_keep", Json.obj(
                    "user_id" -> user.id.get.id,
                    "bookmark_id" -> bookmark.id.get.id
                  ))
                  persistEventPlugin.persist(event)
                  sendReply(
                    message = message,
                    htmlBody =
                        s"<p>Hi ${user.firstName},</p>" +
                        s"<p>Congratulations! We added a $keepType keep for $uri.</p>" +
                        "<p>Sincerely,<br>The kifi elves</p>"
                  )
                }
            }
          }
          message.setFlags(new Flags(Flags.Flag.DELETED), true)
        }
        inbox.close(true)
      } finally {
        store.close()
      }
  }

  private def sendReply(message: javax.mail.Message, htmlBody: String) {
    val newMessage = message.reply(false)
    postOffice.sendMail(ElectronicMail(
      from = EmailAddresses.NOTIFICATIONS,
      fromName = Some("Kifi Elves"),
      to = new EmailAddressHolder {
        val address = messageParser.getAddr(newMessage.getRecipients(RecipientType.TO).head)
      },
      subject = newMessage.getSubject,
      htmlBody = htmlBody,
      inReplyTo = newMessage.getHeader("In-Reply-To").headOption.map(ElectronicMailMessageId.fromEmailHeader),
      category = PostOffice.Categories.EMAIL_KEEP
    ))
  }
}

class MailToKeepMessageParser @Inject() (
    db: Database,
    emailAddressRepo: EmailAddressRepo,
    userRepo: UserRepo
  ) {

  private val Url = """(?i)(?<![@.])\b(https?://)?(([a-z0-9\-]+\.)+[a-z]{2,3}(/\S*)?)\b""".r

  def getSenderAddr(m: Message): String = {
    m.getReplyTo.headOption.orElse(m.getFrom.headOption).map(getAddr).head
  }

  def getUris(m: Message): Seq[URI] = {
    Url.findAllMatchIn(m.getSubject + " " + getText(m).getOrElse("")).map { m =>
      URI.parse(Option(m.group(1)).getOrElse("http://") + m.group(2)).toOption
    }.flatten.toList.distinct
  }

  // see http://www.oracle.com/technetwork/java/javamail/faq/index.html#mainbody
  // This makes no attempts to deal with malformed emails.
  def getText(p: Part): Option[String] = {
    if (p.isMimeType("text/*")) {
      Option(p.getContent.asInstanceOf[String]).map {
        case html if p.isMimeType("text/html") => Jsoup.parse(html).text()
        case text => text
      }
    } else if (p.isMimeType("multipart/alternative")) {
      val mp = p.getContent.asInstanceOf[Multipart]
      (0 until mp.getCount).map(mp.getBodyPart).foldLeft(None: Option[String]) { (text, bp) =>
        if (bp.isMimeType("text/plain"))
          getText(bp) orElse text
        else
          text orElse getText(bp)
      }
    } else if (p.isMimeType("multipart/*")) {
      val mp = p.getContent.asInstanceOf[Multipart]
      (0 until mp.getCount).map(mp.getBodyPart).foldLeft(None: Option[String]) { _ orElse getText(_) }
    } else {
      None
    }
  }

  def getUser(message: Message): Option[User] = {
    db.readOnly { implicit s =>
      message.getFrom.map(getAddr)
        .map(emailAddressRepo.getByAddressOpt(_).map(_.userId)).headOption.flatten.map(userRepo.get)
    }
  }

  def getAddr(address: javax.mail.Address): String =
    address.asInstanceOf[InternetAddress].getAddress
}

@ImplementedBy(classOf[MailToKeepPluginImpl])
trait MailToKeepPlugin extends SchedulingPlugin {
  def fetchNewKeeps()
}

class MailToKeepPluginImpl @Inject()(
  actorFactory: ActorFactory[MailToKeepActor]
) extends MailToKeepPlugin with Logging {

  override def enabled: Boolean = true

  private lazy val actor = actorFactory.get()

  def fetchNewKeeps() {
    actor ! FetchNewKeeps
  }
  override def onStart() {
    log.info("Starting MailToKeepPluginImpl")
    scheduleTask(actorFactory.system, 10 seconds, 1 minute, actor, FetchNewKeeps)
  }
}

class FakeMailToKeepPlugin extends MailToKeepPlugin with Logging {
  def fetchNewKeeps() {
    log.info("Fake fetching new keeps")
  }
}
