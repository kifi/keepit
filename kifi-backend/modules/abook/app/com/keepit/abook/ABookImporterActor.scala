package com.keepit.abook

import com.google.inject.{ Provider, Inject, Singleton }
import com.keepit.common.akka.{ FortyTwoActor, UnsupportedActorMessage }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.model._
import com.keepit.common.db.Id
import com.keepit.abook.store.ABookRawInfoStore
import play.api.libs.json.{ JsError, JsSuccess, JsValue }
import com.keepit.common.logging.{ LogPrefix, Logging }
import com.keepit.common.db.slick.Database
import play.api.Plugin
import scala.ref.WeakReference
import akka.actor.{ Props, ActorSystem }
import com.keepit.common.actor.ActorInstance
import akka.routing.SmallestMailboxPool
import com.mysql.jdbc.exceptions.jdbc4.MySQLIntegrityConstraintViolationException
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import java.sql.SQLException
import com.keepit.common.performance._
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.common.mail.{ BasicContact, EmailAddress }
import com.keepit.abook.commanders.ContactInterner

trait ABookImporterPlugin extends Plugin {
  def asyncProcessContacts(userId: Id[User], origin: ABookOriginType, aBookInfo: ABookInfo, s3Key: String, rawJsonRef: Option[WeakReference[JsValue]]): Unit
}

@Singleton
class ABookImporterActorPlugin @Inject() (
    actorInstance: ActorInstance[ABookImporterActor],
    sysProvider: Provider[ActorSystem],
    updaterActorProvider: Provider[ABookImporterActor],
    nrOfInstances: Int) extends ABookImporterPlugin with Logging {

  lazy val system = sysProvider.get
  lazy val actor = system.actorOf(Props(updaterActorProvider.get).withRouter(SmallestMailboxPool(nrOfInstances)))

  def asyncProcessContacts(userId: Id[User], origin: ABookOriginType, aBookInfo: ABookInfo, s3Key: String, rawJsonRef: Option[WeakReference[JsValue]]): Unit = {
    actor ! ProcessABookUpload(userId, origin, aBookInfo, s3Key, rawJsonRef)
  }

}

case class ProcessABookUpload(userId: Id[User], origin: ABookOriginType, abookRepoEntry: ABookInfo, s3Key: String, rawJsonRef: Option[WeakReference[JsValue]])

class ABookImporterActor @Inject() (
    airbrake: AirbrakeNotifier,
    aBookImporter: ABookImporter) extends FortyTwoActor(airbrake) with Logging {

  def receive() = {
    case ProcessABookUpload(userId, origin, abookInfo, s3Key, rawJsonRef) => {
      aBookImporter.processABookUpload(userId, origin, abookInfo, s3Key, rawJsonRef)
    }
    case m => throw new UnsupportedActorMessage(m)
  }

}

import Logging._
class ABookImporter @Inject() (
    db: Database,
    s3: ABookRawInfoStore,
    abookInfoRepo: ABookInfoRepo,
    contactInterner: ContactInterner,
    airbrake: AirbrakeNotifier,
    abookUploadConf: ABookUploadConf,
    shoeboxClient: ShoeboxServiceClient) extends Logging {

  implicit class RichOptString(o: Option[String]) {
    def trimOpt = o collect { case s: String if (s != null && !s.trim.isEmpty) => s }
  }

  def processABookUpload(userId: Id[User], origin: ABookOriginType, abookInfo: ABookInfo, s3Key: String, rawJsonRef: Option[WeakReference[JsValue]]) = timing(s"upload($userId,$origin,$abookInfo,$s3Key)") {
    implicit val prefix = LogPrefix(s"processABookUpload($userId,$origin,${abookInfo.id}})")
    log.infoP(s"abookInfo=$abookInfo s3Key=$s3Key: Begin processing ...")
    var abookEntry = abookInfo
    var processed = 0
    var batchNum = 0
    try {
      val abookRawInfoOpt = rawJsonRef.flatMap(_.get) flatMap { js => js.asOpt[ABookRawInfo] } orElse { s3.get(s3Key) }
      abookRawInfoOpt match {
        case None =>
          db.readWrite(attempts = 2) { implicit s =>
            abookInfoRepo.save(abookEntry.withState(ABookInfoStates.UPLOAD_FAILURE))
          }
          airbrake.notify(s"[$prefix failure -- cannot retrieve/process json input")
        case Some(abookRawInfo) => {

          abookRawInfo.contacts.value.grouped(abookUploadConf.batchSize).foreach { batch =>
            batchNum += 1
            val basicContacts = readBasicContacts(userId, origin, batch)
            processed += batch.length
            abookEntry = db.readWrite(attempts = 2) { implicit s =>
              try {
                contactInterner.internContacts(userId, abookInfo.id.get, basicContacts)
              } catch {
                case ex: MySQLIntegrityConstraintViolationException => {
                  log.errorP(s"Caught exception while processing batch(len=${basicContacts.length}): ${basicContacts.mkString(",")}")
                  log.errorP(s"ex: $ex; cause: ${ex.getCause}; stack trace: ${ex.getStackTrace.mkString("", "\n", "\n")}")
                  // moving along
                }
                case ex: java.sql.BatchUpdateException => {
                  log.errorP(s"Caught exception while processing batch(len=${basicContacts.length}): ${basicContacts.mkString(",")}")
                  log.errorP(s"ex: $ex; cause: ${ex.getCause}; stack trace: ${ex.getStackTrace.mkString("", "\n", "\n")}")
                  // moving along
                }
                case t: Throwable => {
                  log.errorP(s"Unhandled ex: $t; cause: ${t.getCause}; stack trace: ${t.getStackTrace.mkString("", "\n", "\n")}")
                  throw t // this will fail
                }
              }
              abookInfoRepo.save(abookEntry.withNumProcessed(Some(processed))) // status update
            }
          }
          abookEntry = db.readWrite(attempts = 2) { implicit s =>
            val saved = abookInfoRepo.save(abookEntry.withState(ABookInfoStates.ACTIVE))
            log.infoP(s"abook is ACTIVE! saved=$saved")
            saved
          }
        }
      }
    } catch {
      case ex: SQLException => {
        log.warnP(s"Caught SQLException $ex; code=${ex.getErrorCode}; cause: ${ex.getCause}; stack trace: ${ex.getStackTrace.mkString("", "\n", "\n")}")
      }
      case t: Throwable => {
        log.warnP(s"Caught unhandled exception $t; cause: ${t.getCause}; stack trace: ${t.getStackTrace.mkString("", "\n", "\n")}")
      }
    } finally {
      if (abookEntry.state != ABookInfoStates.ACTIVE) {
        db.readWrite(attempts = 2) { implicit s => // could still fail
          abookInfoRepo.save(abookEntry.withState(ABookInfoStates.UPLOAD_FAILURE))
        }
        airbrake.notify(s"[upload($userId, $abookInfo)] failed")
      }
    }
  }

  private def readBasicContacts(userId: Id[User], origin: ABookOriginType, batch: Seq[JsValue]): Seq[BasicContact] = batch.flatMap { contact =>
    val firstName = (contact \ "firstName").asOpt[String] trimOpt
    val lastName = (contact \ "lastName").asOpt[String] trimOpt
    val name = (contact \ "name").asOpt[String] trimOpt
    val validEmails = (contact \ "emails").asOpt[Seq[JsValue]].getOrElse(Seq.empty[JsValue]).foldLeft(Seq[EmailAddress]()) {
      case (validEmails, nextValue) =>
        nextValue.validate[EmailAddress] match {
          case JsSuccess(validEmail, _) => validEmails :+ validEmail
          case JsError(errors) =>
            log.warn(s"[upload($userId,$origin)] Json parsing errors: $errors")
            validEmails
        }
    }
    validEmails.map { email => BasicContact(email, name = name, firstName = firstName, lastName = lastName) }
  }
}

