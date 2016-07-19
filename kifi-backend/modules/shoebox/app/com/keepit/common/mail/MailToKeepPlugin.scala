package com.keepit.common.mail

import scala.concurrent.duration._

import com.google.inject.{ ImplementedBy, Inject }
import com.keepit.common.actor.ActorInstance
import com.keepit.common.akka.{ FortyTwoActor, UnsupportedActorMessage }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.net.URI
import com.keepit.common.plugin.{ SchedulerPlugin, SchedulingProperties }
import com.keepit.commanders.{ LibraryInfoCommander, LibraryCommander, RawBookmarkRepresentation, KeepInterner }
import com.keepit.model._
import com.keepit.common.time._
import com.keepit.common.service.FortyTwoServices

import javax.mail.Message.RecipientType
import javax.mail._
import javax.mail.search._
import play.api.Plugin
import com.keepit.heimdal.HeimdalContext

private case object FetchNewKeeps

case class MailToKeepServerSettings(
  username: String,
  password: String,
  server: String,
  protocol: String,
  emailLabel: Option[String] = None)

private sealed abstract class KeepType(val name: String, val emailPrefix: String) {
  override def toString = name
}

private object KeepType {
  object Public extends KeepType("public", "keep")
  object Private extends KeepType("private", "private")
  val allTypes: Seq[KeepType] = Seq(Public, Private)
}

class MailToKeepActor @Inject() (
    airbrake: AirbrakeNotifier,
    settings: MailToKeepServerSettings,
    bookmarkInterner: KeepInterner,
    postOffice: LocalPostOffice,
    messageParser: MailToKeepMessageParser,
    db: Database,
    libraryInfoCommander: LibraryInfoCommander,
    implicit private val clock: Clock,
    implicit private val fortyTwoServices: FortyTwoServices) extends FortyTwoActor(airbrake) with Logging {

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
          val prefixes = message.getAllRecipients.map(messageParser.getAddr).flatMap {
            KeepEmail.findFirstMatchIn(_).map(_.group(1).toLowerCase.trim)
          }
          for (keepType <- KeepType.allTypes.filter(prefixes contains _.emailPrefix)) {
            val senderAddress = messageParser.getSenderAddress(message)
            (messageParser.getUser(senderAddress), messageParser.getUris(message)) match {
              case (None, _) =>
                sendReply(
                  message = message,
                  htmlBody = s"""
                    |Hi There, <br><br>
                    |We are unable to securely keep this page for you because it was sent from an unverified email address ($senderAddress) or it is not associated with a Kifi account. <br>
                    |Let us help you get set up so this doesn’t happen again. <br><br>
                    |<u>Get verified</u><br>
                    |If you are a registered Kifi user, log in and visit <a href="https://www.kifi.com/profile">your profile</a>. Click to "Manage your email addresses". If the email address isn’t listed, add it and we’ll send you an email to verify it. If it is listed, you can resend a verification email. <br><br>
                    |<u>Not a Kifi user?</u><br>
                    |If you are not a Kifi user someone may have accidentally added your email,  if you think this is the case please contact us. If you tried to keep something, but haven’t registered for Kifi yet, you can <a href="https://www.kifi.com">sign up for free</a>. <br><br>
                    |Thanks! <br>
                    |The Kifi Team
                  """.stripMargin
                )
              case (Some(user), Seq()) =>
                sendReply(
                  message = message,
                  htmlBody =
                    s"<p>Hi ${user.firstName},</p>" +
                      "<p>We couldn't find any URLs in your message. Try making sure your URL format is valid.</p>"
                )
              case (Some(user), uris) =>
                val library = {
                  val (main, secret) = db.readWrite { implicit session =>
                    libraryInfoCommander.getMainAndSecretLibrariesForUser(user.id.get)
                  }
                  if (keepType == KeepType.Private) {
                    secret
                  } else {
                    main
                  }
                }
                val bms = uris.map(uri => RawBookmarkRepresentation(url = uri.toString(), keptAt = Some(clock.now)))
                implicit val context = HeimdalContext.empty
                val (bookmarks, _) = bookmarkInterner.internRawBookmarks(bms, user.id.get, library, KeepSource.Email)
                val bmText = bookmarks.map { bm =>
                  log.info(s"created bookmark from email with id ${bm.id.get}")
                  s"<p>${bm.url}</p>"
                }.mkString("\n")

                sendReply(
                  message = message,
                  htmlBody =
                    s"<p>Hi ${user.firstName},</p>" +
                      s"<p>Congratulations! We added ${bookmarks.length} ${keepType.name} keeps:</p>" +
                      bmText +
                      "<p>Sincerely,<br>The Kifi team</p>"
                )
            }
          }
          message.setFlags(new Flags(Flags.Flag.DELETED), true)
        }
        inbox.close(true)
      } finally {
        store.close()
      }
    case m => throw new UnsupportedActorMessage(m)
  }

  private def sendReply(message: javax.mail.Message, htmlBody: String) {
    db.readWrite { implicit s =>
      val newMessage = message.reply(false)
      postOffice.sendMail(ElectronicMail(
        from = SystemEmailAddress.NOTIFICATIONS,
        fromName = Some("Kifi Team"),
        to = List(EmailAddress(messageParser.getAddr(newMessage.getRecipients(RecipientType.TO).head))),
        subject = Option(newMessage.getSubject).getOrElse(""),
        htmlBody = htmlBody,
        inReplyTo = newMessage.getHeader("In-Reply-To").headOption.map(ElectronicMailMessageId.fromEmailHeader),
        category = NotificationCategory.User.EMAIL_KEEP
      ))
    }
  }
}

class MailToKeepMessageParser @Inject() (
    db: Database,
    emailAddressRepo: UserEmailAddressRepo,
    userRepo: UserRepo) extends GenericMailParser with Logging {

  private val Url = """(?i)(?<![@.])\b(https?://)?(([a-z0-9\-]+\.)+[a-z]{2,3}(/\S*)?)\b""".r

  def getSenderAddress(m: Message): EmailAddress = { EmailAddress(getAddr(m.getFrom.head)) }

  def getUris(m: Message): Seq[URI] = {
    Url.findAllMatchIn(m.getSubject + " " + getText(m).getOrElse("")).map { m =>
      URI.parse(Option(m.group(1)).getOrElse("http://") + m.group(2)).toOption
    }.flatten.toList.distinct
  }

  def getUser(senderAddress: EmailAddress): Option[User] = {
    db.readOnlyMaster { implicit s =>
      emailAddressRepo.getOwner(senderAddress).map { userId =>
        userRepo.get(userId)
      }
    }
  }
}

trait MailToKeepPlugin extends Plugin {
  def fetchNewKeeps()
}

class MailToKeepPluginImpl @Inject() (
    actor: ActorInstance[MailToKeepActor],
    val scheduling: SchedulingProperties //only on leader
    ) extends MailToKeepPlugin with SchedulerPlugin {

  override def enabled: Boolean = true

  def fetchNewKeeps() {
    actor.ref ! FetchNewKeeps
  }
  override def onStart() { //keep me alive!
    scheduleTaskOnOneMachine(actor.system, 80 seconds, 1 hours, actor.ref, FetchNewKeeps, FetchNewKeeps.getClass.getSimpleName)
  }
}

class FakeMailToKeepPlugin @Inject() extends MailToKeepPlugin with Logging {
  def fetchNewKeeps() {
    log.info("Fake fetching new keeps")
  }
}
