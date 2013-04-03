package com.keepit.common.mail

import scala.concurrent.duration._

import com.google.inject.{ImplementedBy, Inject}
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.analytics.{EventFamilies, Events, PersistEventPlugin}
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.net.URI
import com.keepit.common.plugin.SchedulingPlugin
import com.keepit.controllers.core.BookmarkInterner
import com.keepit.model.{EmailAddress, EmailAddressRepo, User, UserRepo}

import akka.actor.{Props, ActorSystem}
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

private class MailToKeepActor(
    settings: MailToKeepServerSettings,
    bookmarkInterner: BookmarkInterner,
    persistEventPlugin: PersistEventPlugin,
    postOffice: PostOffice,
    messageParser: MailToKeepMessageParser
  ) extends FortyTwoActor with Logging {

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
          val senderAddr = messageParser.getSenderAddr(message)
          (messageParser.getUser(message), messageParser.getUris(message)) match {
            case (None, _) =>
              sendEmail(
                to = senderAddr,
                subject = "Could not identify user",
                htmlBody = s"<p>Kifi could not find a user for $senderAddr.</p>"
              )
            case (Some(user), Seq()) =>
              sendEmail(
                to = senderAddr,
                subject = s"Your message '${message.getSubject}' contained no URLs",
                htmlBody =
                    s"<p>Hi ${user.firstName},</p>" +
                    "<p>We couldn't find any URLs in your message. Try making sure your URL format is valid.</p>"
              )
            case (Some(user), uris) =>
              for (uri <- uris) {
                val bookmark = bookmarkInterner.internBookmarks(Json.obj(
                  "url" -> uri.toString,
                  "isPrivate" -> true
                ), user, Seq(), "EMAIL").head
                log.info(s"created bookmark from email with id ${bookmark.id.get}")
                val event = Events.serverEvent(EventFamilies.GENERIC_SERVER, "email_keep", Json.obj(
                  "user_id" -> user.id.get.id,
                  "bookmark_id" -> bookmark.id.get.id
                ))
                persistEventPlugin.persist(event)
                sendEmail(
                  to = senderAddr,
                  subject = s"Successfully kept $uri",
                  htmlBody =
                      s"<p>Hi ${user.firstName},</p>" +
                      s"<p>Congratulations! We successfully kept $uri for you.</p>" +
                      "<p>Sincerely,<br>The kifi elves</p>"
                )
              }
          }
          message.setFlags(new Flags(Flags.Flag.DELETED), true)
        }
        inbox.close(true)
      } finally {
        store.close()
      }
  }

  private def sendEmail(to: String, subject: String, htmlBody: String) {
    postOffice.sendMail(ElectronicMail(
      from = EmailAddresses.NOTIFICATIONS,
      fromName = Some("Kifi Elves"),
      to = new EmailAddressHolder { val address = to },
      subject = subject,
      htmlBody = htmlBody,
      category = PostOffice.Categories.EMAIL_KEEP
    ))
  }
}

class MailToKeepMessageParser @Inject() (
    db: Database,
    emailAddressRepo: EmailAddressRepo,
    userRepo: UserRepo
  ) {

  private val Url = """\bhttps?://[-a-zA-Z0-9+&@#/%?=~_|!:,.;]*[-a-zA-Z0-9+&@#/%=~_|]\b""".r

  def getSenderAddr(m: Message): String = {
    m.getReplyTo.headOption.orElse(m.getFrom.headOption).map(getAddr).head
  }

  def getUris(m: Message): Seq[URI] = {
    Url.findAllIn(getContent(m) + " " + m.getSubject).map(URI.parse(_).toOption).flatten.toList.distinct
  }

  def getContent(m: Message): String = {
    m.getContent match {
      case mm: MimeMultipart =>
        (0 until mm.getCount).map(mm.getBodyPart).foldLeft(None: Option[String]) { (v, part) =>
          if (!Option(part.getDisposition).getOrElse("").equalsIgnoreCase("ATTACHMENT"))
            v orElse getText(part)
          else v
        }.getOrElse("")
      case c => c.toString
    }
  }

  // see http://www.oracle.com/technetwork/java/javamail/faq/index.html#mainbody
  // This makes no attempts to deal with malformed emails.
  private def getText(p: Part): Option[String] = {
    if (p.isMimeType("text/*")) {
      Option(p.getContent.asInstanceOf[String])
    } else if (p.isMimeType("multipart/alternative")) {
      val mp = p.getContent.asInstanceOf[Multipart]
      (0 until mp.getCount).map(mp.getBodyPart).foldLeft(None: Option[String]) { (text, bp) =>
        if (bp.isMimeType("text/html"))
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
  system: ActorSystem,
  settings: MailToKeepServerSettings,
  bookmarkInterner: BookmarkInterner,
  persistEventPlugin: PersistEventPlugin,
  postOffice: PostOffice,
  messageParser: MailToKeepMessageParser
) extends MailToKeepPlugin with Logging {

  override def enabled: Boolean = true

  val actor = system.actorOf(Props {
    new MailToKeepActor(settings, bookmarkInterner, persistEventPlugin, postOffice, messageParser)
  })

  def fetchNewKeeps() {
    actor ! FetchNewKeeps
  }
  override def onStart() {
    log.info("Starting MailToKeepPluginImpl")
    scheduleTask(system, 10 seconds, 1 minute, actor, FetchNewKeeps)
  }
}

class FakeMailToKeepPlugin extends MailToKeepPlugin with Logging {
  def fetchNewKeeps() {
    log.info("Fake fetching new keeps")
  }
}
