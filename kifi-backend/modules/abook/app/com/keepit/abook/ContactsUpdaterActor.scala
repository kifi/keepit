package com.keepit.abook

import com.google.inject.{Provider, ImplementedBy, Inject, Singleton}
import com.keepit.common.akka.{FortyTwoActor, UnsupportedActorMessage}
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.model._
import com.keepit.common.db.Id
import scala.collection.mutable
import com.keepit.abook.store.ABookRawInfoStore
import play.api.libs.json.{JsValue, Json}
import com.keepit.common.logging.Logging
import com.keepit.common.db.slick.Database
import play.api.{Play, Plugin}
import scala.ref.WeakReference
import akka.actor.{Props, ActorSystem}
import play.api.Play.current
import com.keepit.common.actor.ActorInstance
import akka.routing.{SmallestMailboxRouter, RoundRobinRouter}
import scala.concurrent._
import com.mysql.jdbc.exceptions.jdbc4.MySQLIntegrityConstraintViolationException
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import java.sql.SQLException
import play.api.libs.iteratee.{Step, Iteratee}
import com.keepit.common.performance._
import scala.util.{Try, Failure, Success}


trait ContactsUpdaterPlugin extends Plugin {
  def asyncProcessContacts(userId:Id[User], origin:ABookOriginType, aBookInfo:ABookInfo, s3Key:String, rawJsonRef:WeakReference[JsValue]): Unit
}

@Singleton
class ContactsUpdaterActorPlugin @Inject() (
  actorInstance:ActorInstance[ContactsUpdaterActor],
  sysProvider:Provider[ActorSystem],
  updaterActorProvider:Provider[ContactsUpdaterActor],
  nrOfInstances:Int
) extends ContactsUpdaterPlugin with Logging {

  lazy val system = sysProvider.get
  lazy val actor = system.actorOf(Props(updaterActorProvider.get).withRouter(SmallestMailboxRouter(nrOfInstances)))

  def asyncProcessContacts(userId: Id[User], origin:ABookOriginType, aBookInfo: ABookInfo, s3Key: String, rawJsonRef: WeakReference[JsValue]): Unit = {
    actor ! ProcessABookUpload(userId, origin, aBookInfo, s3Key, rawJsonRef)
  }

}

case class ProcessABookUpload(userId:Id[User], origin:ABookOriginType, abookRepoEntry: ABookInfo, s3Key:String, rawJsonRef:WeakReference[JsValue])

class ContactsUpdaterActor @Inject() (
  airbrake:AirbrakeNotifier,
  contactsUpdater:ContactsUpdater
) extends FortyTwoActor(airbrake) with Logging {

  def receive() = {
    case ProcessABookUpload(userId, origin, abookInfo, s3Key, rawJsonRef) => {
      contactsUpdater.processABookUpload(userId, origin, abookInfo, s3Key, rawJsonRef)
    }
    case m => throw new UnsupportedActorMessage(m)
  }

}

class ContactsUpdater @Inject() (
  db:Database,
  s3:ABookRawInfoStore,
  abookInfoRepo:ABookInfoRepo,
  contactRepo:ContactRepo,
  econtactRepo:EContactRepo,
  airbrake:AirbrakeNotifier) extends Logging {

  val batchSize = sys.props.getOrElse("abook.upload.batch.size", "200").toInt

  implicit class RichOptString(o:Option[String]) {
    def trimOpt = o collect { case s:String if (s!= null && !s.trim.isEmpty) => s }
  }

  private def mkName(name:Option[String], firstName:Option[String], lastName:Option[String], email:String) = name orElse (
    (firstName, lastName) match {
      case (Some(f), Some(l)) => Some(s"$f $l")
      case (Some(f), None) => Some(f)
      case (None, Some(l)) => Some(l)
      case (None, None) => None
    }
    )

  private def existingEmailSet(userId: Id[User], origin: ABookOriginType, abookInfo: ABookInfo) = timing(s"existingEmailSet($userId,$origin,$abookInfo)") {
    val existingContacts = db.readOnly(attempts = 2) { implicit s =>
        econtactRepo.getByUserId(userId) // optimistic; gc; h2-iter issue
    }
    val existingEmailSet = new mutable.TreeSet[String] // todo: use parsed email
    for (e <- existingContacts) {
      val parseResult = EmailParser.parseAll(EmailParser.email, e.email) // handle 'dirty' data
      if (parseResult.successful) {
        existingEmailSet += parseResult.get.toString
      } else {
        log.warn(s"[upload($userId, $origin, ${abookInfo.id})] cannot parse Existing email ${e.email} for contact ${e}") // move along
      }
    }
    log.info(s"[upload($userId, $origin, ${abookInfo.id})] existing contacts(sz=${existingEmailSet.size}): ${existingEmailSet.mkString(",")}")
    existingEmailSet
  }

  def processABookUpload(userId:Id[User], origin:ABookOriginType, abookInfo:ABookInfo, s3Key:String, rawJsonRef:WeakReference[JsValue]) = timing(s"upload($userId,$origin,$abookInfo,$s3Key)") {
    log.info(s"[upload($userId, $origin, $abookInfo, $s3Key): Begin processing ...")
    val ts = System.currentTimeMillis
    var abookEntry = abookInfo
    var processed = 0
    var batchNum = 0
    try {
      val abookRawInfoOpt = rawJsonRef.get flatMap { js => js.asOpt[ABookRawInfo] } orElse { s3.get(s3Key) }
      abookRawInfoOpt match {
        case None =>
          db.readWrite(attempts = 2) { implicit s =>
            abookInfoRepo.save(abookEntry.withState(ABookInfoStates.UPLOAD_FAILURE))
          }
          airbrake.notify(s"[upload($userId, $origin, ${abookInfo.id}] failure -- cannot retrieve/process json input")
        case Some(abookRawInfo) => {
          val existingEmails = existingEmailSet(userId, origin, abookInfo)

          log.info(s"[upload($userId, $origin, ${abookInfo.id}] abookRawInfo=$abookRawInfo existingEmails=$existingEmails")
          val cBuilder = mutable.ArrayBuilder.make[Contact]
          abookRawInfo.contacts.value.grouped(batchSize).foreach { g =>
            val econtactsToAddBuilder = mutable.ArrayBuilder.make[EContact]
            batchNum += 1
            g.foreach { contact =>
              val fName = (contact \ "firstName").asOpt[String] trimOpt
              val lName = (contact \ "lastName").asOpt[String] trimOpt
              val emails = (contact \ "emails").validate[Seq[String]].fold(
                valid = ( res => res),
                invalid = ( e => {
                  log.warn(s"[upload($userId,$origin)] Cannot parse $e")
                  Seq.empty[String]
                })
              )
              emails.foreach { email =>
                val parseResult = EmailParser.parseAll(EmailParser.email, email)
                if (parseResult.successful) {
                  val parsedEmail:Email = parseResult.get
                  if (parsedEmail.host.domain.length <= 1) {
                    log.warn(s"[upload] $email domain=${parsedEmail.host.domain} not supported; discarded")
                  } else if (existingEmails.contains(parsedEmail.toString)) {
                    log.info(s"[upload($userId, $origin, ${abookInfo.id}] DUP $email; discarded")
                  } else {
                    val nameOpt = mkName((contact \ "name").asOpt[String] trimOpt, fName, lName, email)
                    val e = EContact(userId = userId, email = parsedEmail.toString, name = nameOpt, firstName = fName, lastName = lName)
                    existingEmails += parsedEmail.toString
                    econtactsToAddBuilder += e
                  }
                } else {
                  log.warn(s"[upload($userId, $origin)] cannot parse $email; discarded") // todo: revisit
                }
              }

              for (email <- emails.headOption) {
                val nameOpt = mkName((contact \ "name").asOpt[String] trimOpt, fName, lName, email)
                val c = Contact.newInstance(userId, abookInfo.id.get, email, emails.tail, origin, nameOpt, fName, lName, None)
                log.info(s"[upload($userId,$origin)] $c")
                cBuilder += c
              }
            }
            val econtactsToAdd = econtactsToAddBuilder.result

            processed += g.length
            abookEntry = db.readWrite(attempts = 2) { implicit s =>
              try {
                econtactRepo.insertAll(userId, econtactsToAdd.toSeq)
                log.info(s"[insertAll($userId)] added batch#${batchNum}(sz=${econtactsToAdd.length}) to econtacts: ${econtactsToAdd.map(_.email).mkString}")
              } catch {
                case ex:MySQLIntegrityConstraintViolationException => {
                  log.error(s"[insertAll($userId, processed=$processed)] Caught exception while processing batch(len=${econtactsToAdd.length}): ${econtactsToAdd.mkString(",")}")
                  log.error(s"[insertAll($userId)] ex: $ex; cause: ${ex.getCause}; stack trace: ${ex.getStackTraceString}")
                  // moving along
                }
                case be:java.sql.BatchUpdateException => {
                  log.error(s"[insertAll($userId, processed=$processed)] Caught exception while processing batch(len=${econtactsToAdd.length}): ${econtactsToAdd.mkString(",")}")
                  log.error(s"[insertAll($userId)] ex: $be; cause: ${be.getCause}; stack trace: ${be.getStackTraceString}")
                  // moving along
                }
                case t:Throwable => {
                  log.error(s"[insertAll($userId)] Unhandled ex: $t; cause: ${t.getCause}; stack trace: ${t.getStackTraceString}")
                  throw t // this will fail
                }
              }
              abookInfoRepo.save(abookEntry.withNumProcessed(Some(processed))) // status update
            }
          }
          abookEntry = db.readWrite(attempts = 2) { implicit s => // don't wait for contact table
            val saved = abookInfoRepo.save(abookEntry.withState(ABookInfoStates.ACTIVE))
            log.info(s"[upload($userId,${saved.id})] abook is ACTIVE! $saved")
            saved
          }

          val contacts = cBuilder.result
          if (!contacts.isEmpty) {
            asyncUpdateContactsRepo(userId, abookInfo, origin, contacts)
          }
          log.info(s"[upload($userId,$origin)] time-lapsed: ${System.currentTimeMillis - ts}")
        }
      }
    } catch {
      case ex:SQLException => {
        log.warn(s"[ContactsUpdater.upload($userId, $abookInfo)] Caught SQLException $ex; code=${ex.getErrorCode}; cause: ${ex.getCause}; stack trace: ${ex.getStackTraceString}")
      }
      case t:Throwable => {
        log.warn(s"[ContactsUpdater.upload($userId, $abookInfo)] Caught unhandled exception $t; cause: ${t.getCause}; stack trace: ${t.getStackTraceString}")
      }
    } finally {
      if (abookEntry.state != ABookInfoStates.ACTIVE) {
        db.readWrite(attempts = 2) { implicit s =>  // could still fail
          abookInfoRepo.save(abookEntry.withState(ABookInfoStates.UPLOAD_FAILURE))
        }
        airbrake.notify(s"[upload($userId, $abookInfo)] failed")
      }
    }
  }

  def asyncUpdateContactsRepo(userId: Id[User], abookInfo: ABookInfo, origin: ABookOriginType, contacts: Array[Contact]) {
    future {
      Try {
        db.readWrite(attempts = 2) { implicit session =>
          contactRepo.deleteAndInsertAll(userId, abookInfo.id.get, origin, contacts)
        }
      }
    } onComplete { tr =>
      tr match {
        case Failure(t) => airbrake.notify(s"[upload($userId,$origin)] (minor) failed to update contacts table") // email?
        case Success(n) => log.info(s"[upload($userId,$origin)] contacts table updated")
      }
    }
  }

}

