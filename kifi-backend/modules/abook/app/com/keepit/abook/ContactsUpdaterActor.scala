package com.keepit.abook

import com.google.inject.{Provider, ImplementedBy, Inject, Singleton}
import com.keepit.common.akka.{SafeFuture, FortyTwoActor, UnsupportedActorMessage}
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.model._
import com.keepit.common.db.Id
import scala.collection.mutable
import com.keepit.abook.store.ABookRawInfoStore
import play.api.libs.json.{JsError, JsSuccess, JsValue, Json}
import com.keepit.common.logging.{LogPrefix, Logging}
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
import com.keepit.abook.typeahead.EContactABookTypeahead
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.common.mail.{EmailAddress}


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

import Logging._
class ContactsUpdater @Inject() (
  db:Database,
  s3:ABookRawInfoStore,
  econtactABookTypeahead:EContactABookTypeahead,
  abookInfoRepo:ABookInfoRepo,
  contactRepo:ContactRepo,
  econtactRepo:EContactRepo,
  airbrake:AirbrakeNotifier,
  abookUploadConf:ABookUploadConf,
  shoeboxClient: ShoeboxServiceClient) extends Logging {

  implicit class RichOptString(o:Option[String]) {
    def trimOpt = o collect { case s:String if (s!= null && !s.trim.isEmpty) => s }
  }

  private def mkName(name:Option[String], firstName:Option[String], lastName:Option[String]) = name orElse (
    (firstName, lastName) match {
      case (Some(f), Some(l)) => Some(s"$f $l")
      case (Some(f), None) => Some(f)
      case (None, Some(l)) => Some(l)
      case (None, None) => None
    }
    )

  private def existingEmailSet(userId: Id[User], origin: ABookOriginType, abookInfo: ABookInfo): mutable.Set[EmailAddress] = timing(s"existingEmailSet($userId,$origin,$abookInfo)") {
    val existingContacts = db.readOnly(attempts = 2) { implicit s =>
        econtactRepo.getByUserId(userId) // optimistic; gc; h2-iter issue
    }
    val existingEmailSet = new mutable.HashSet[EmailAddress]
    for (e <- existingContacts) {
      if (EmailAddress.isValid(e.email.address)) {
        existingEmailSet += e.email
      } else {
        log.warn(s"[upload($userId, $origin, ${abookInfo.id})] invalid email ${e.email} for contact ${e}") // move along
      }
    }
    log.info(s"[upload($userId, $origin, ${abookInfo.id})] existing contacts(sz=${existingEmailSet.size}): ${existingEmailSet.mkString(",")}")
    existingEmailSet
  }

  private def recordContactUserIds(contactAddresses: Seq[EmailAddress]): Unit = {
    shoeboxClient.getVerifiedAddressOwners(contactAddresses).foreach { case owners =>
      if (owners.nonEmpty) db.readWrite { implicit session =>
        owners.foreach { case (address, contactUserId) =>
          econtactRepo.recordVerifiedEmail(address, contactUserId)
        }
      }
    }
  }

  def processABookUpload(userId:Id[User], origin:ABookOriginType, abookInfo:ABookInfo, s3Key:String, rawJsonRef:WeakReference[JsValue]) = timing(s"upload($userId,$origin,$abookInfo,$s3Key)") {
    implicit val prefix = LogPrefix(s"processABookUpload($userId,$origin,${abookInfo.id}})")
    log.infoP(s"abookInfo=$abookInfo s3Key=$s3Key: Begin processing ...")
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
          airbrake.notify(s"[$prefix failure -- cannot retrieve/process json input")
        case Some(abookRawInfo) => {
          val existingEmails = existingEmailSet(userId, origin, abookInfo)

          log.infoP(s"abookRawInfo=$abookRawInfo existingEmails=$existingEmails")
          val cBuilder = mutable.ArrayBuilder.make[Contact]
          abookRawInfo.contacts.value.grouped(abookUploadConf.batchSize).foreach { g =>
            val econtactsToAddBuilder = mutable.ArrayBuilder.make[EContact]
            batchNum += 1
            g.foreach { contact =>
              val fName = (contact \ "firstName").asOpt[String] trimOpt
              val lName = (contact \ "lastName").asOpt[String] trimOpt
              val emails = (contact \ "emails").asOpt[Seq[JsValue]].getOrElse(Seq.empty[JsValue]).foldLeft(Seq[EmailAddress]()) { case (validEmails, nextValue) =>
                nextValue.validate[EmailAddress] match {
                  case JsSuccess(validEmail, _) => validEmails :+ validEmail
                  case JsError(errors) =>
                    log.warn(s"[upload($userId,$origin)] Json parsing errors: $errors")
                    validEmails
                }
              }

              emails.foreach { email =>
                if (existingEmails.contains(email)) {
                  log.infoP(s"DUP $email; discarded")
                } else {
                  val nameOpt = mkName((contact \ "name").asOpt[String] trimOpt, fName, lName)
                  val e = EContact(userId = userId, email = email, name = nameOpt, firstName = fName, lastName = lName)
                  existingEmails += email
                  econtactsToAddBuilder += e
                }
              }

              for (email <- emails.headOption) {
                val nameOpt = mkName((contact \ "name").asOpt[String] trimOpt, fName, lName)
                val c = Contact.newInstance(userId, abookInfo.id.get, email, emails.tail, origin, nameOpt, fName, lName, None)
                log.debugP(s"$c")
                cBuilder += c
              }
            }
            val econtactsToAdd = econtactsToAddBuilder.result

            processed += g.length
            abookEntry = db.readWrite(attempts = 2) { implicit s =>
              try {
                econtactRepo.insertAll(userId, econtactsToAdd.toSeq)
                s.onTransactionSuccess { recordContactUserIds(econtactsToAdd.map(_.email)) }
                log.infoP(s"added batch#${batchNum}(sz=${econtactsToAdd.length}) to econtacts: ${econtactsToAdd.map(_.email).mkString}")
              } catch {
                case ex:MySQLIntegrityConstraintViolationException => {
                  log.errorP(s"Caught exception while processing batch(len=${econtactsToAdd.length}): ${econtactsToAdd.mkString(",")}")
                  log.errorP(s"ex: $ex; cause: ${ex.getCause}; stack trace: ${ex.getStackTraceString}")
                  // moving along
                }
                case ex:java.sql.BatchUpdateException => {
                  log.errorP(s"Caught exception while processing batch(len=${econtactsToAdd.length}): ${econtactsToAdd.mkString(",")}")
                  log.errorP(s"ex: $ex; cause: ${ex.getCause}; stack trace: ${ex.getStackTraceString}")
                  // moving along
                }
                case t:Throwable => {
                  log.errorP(s"Unhandled ex: $t; cause: ${t.getCause}; stack trace: ${t.getStackTraceString}")
                  throw t // this will fail
                }
              }
              abookInfoRepo.save(abookEntry.withNumProcessed(Some(processed))) // status update
            }
          }
          abookEntry = db.readWrite(attempts = 2) { implicit s => // don't wait for contact table
            val saved = abookInfoRepo.save(abookEntry.withState(ABookInfoStates.ACTIVE))
            log.infoP(s"abook is ACTIVE! saved=$saved")
            saved
          }

          // batch inserts (i.e. insertAll) do not work well with current model; upper layer handle cache invalidation for now
          SafeFuture {
            val econtacts = db.readOnly { implicit ro =>
              econtactRepo.getByUserId(userId) // get all of 'em
            }
            econtactRepo.bulkInvalidateCache(userId, econtacts)
          }
          econtactABookTypeahead.refresh(userId) // async

          val contacts = cBuilder.result
          if (!contacts.isEmpty) {
            asyncUpdateContactsRepo(userId, abookInfo, origin, contacts)
          }
        }
      }
    } catch {
      case ex:SQLException => {
        log.warnP(s"Caught SQLException $ex; code=${ex.getErrorCode}; cause: ${ex.getCause}; stack trace: ${ex.getStackTraceString}")
      }
      case t:Throwable => {
        log.warnP(s"Caught unhandled exception $t; cause: ${t.getCause}; stack trace: ${t.getStackTraceString}")
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
    SafeFuture {
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

