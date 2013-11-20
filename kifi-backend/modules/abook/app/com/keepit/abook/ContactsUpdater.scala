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
import akka.routing.RoundRobinRouter
import scala.concurrent._
import com.mysql.jdbc.exceptions.jdbc4.MySQLIntegrityConstraintViolationException
import play.api.libs.concurrent.Execution.Implicits.defaultContext


@ImplementedBy(classOf[ContactsUpdaterPluginImpl])
trait ContactsUpdaterPlugin extends Plugin {
  def asyncProcessContacts(userId:Id[User], origin:ABookOriginType, aBookInfo:ABookInfo, s3Key:String, rawJsonRef:WeakReference[JsValue])
}

@Singleton
class ContactsUpdaterPluginImpl @Inject() (actorInstance:ActorInstance[ContactsUpdater], sysProvider:Provider[ActorSystem], updaterProvider:Provider[ContactsUpdater]) extends ContactsUpdaterPlugin with Logging {

  lazy val system = sysProvider.get
  lazy val actor = {
    if (Play.maybeApplication.isDefined && (!Play.isTest))
      system.actorOf(Props(updaterProvider.get).withRouter(RoundRobinRouter(Runtime.getRuntime.availableProcessors)))
    else
      actorInstance.ref
  }

  def asyncProcessContacts(userId: Id[User], origin:ABookOriginType, aBookInfo: ABookInfo, s3Key: String, rawJsonRef: WeakReference[JsValue]): Unit = {
    actor ! ProcessABookUpload(userId, origin, aBookInfo, s3Key, rawJsonRef)
  }

}

case class ProcessABookUpload(userId:Id[User], origin:ABookOriginType, abookRepoEntry: ABookInfo, s3Key:String, rawJsonRef:WeakReference[JsValue])

class ContactsUpdater @Inject() (
  db: Database,
  s3:ABookRawInfoStore,
  abookInfoRepo:ABookInfoRepo,
  contactRepo:ContactRepo,
  econtactRepo:EContactRepo,
  airbrake:AirbrakeNotifier
) extends FortyTwoActor(airbrake) with Logging {

  val batchSize = sys.props.getOrElse("abook.upload.batch.size", "100").toInt

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

  def receive() = {
    case ProcessABookUpload(userId, origin, abookInfo, s3Key, rawJsonRef) => {
      log.info(s"[upload($userId, $origin, $abookInfo, $s3Key): Begin processing ...")
      var abookEntry = abookInfo
      val ts = System.currentTimeMillis
      val rawInfoJson = rawJsonRef.get getOrElse {
          val rawInfo = s3.get(s3Key).getOrElse(throw new IllegalStateException(s"[upload] s3Key=$s3Key is not set")) // notify user?
          Json.toJson(rawInfo)
      }
      val abookRawInfoF = future { Json.fromJson[ABookRawInfo](rawInfoJson).getOrElse(throw new IllegalArgumentException(s"[upload] Cannot parse $rawInfoJson")) }
      val existingContacts = db.readOnly { implicit s =>
        econtactRepo.getByUserId(userId) // optimistic; gc; h2-iter issue
      }
      val set = new mutable.TreeSet[String] // todo: use parsed email
      existingContacts foreach { e =>
        set += e.email
      }
      log.info(s"[upload($userId, $origin, ${abookInfo.id})] existing contacts(sz=${set.size}): ${set.mkString}")

      val insertOnDupUpdate = sys.props.getOrElse("abook.db.insertOnDupUpdate", "false").toBoolean // todo: removeme
      abookRawInfoF map { abookRawInfo =>
        val cBuilder = mutable.ArrayBuilder.make[Contact]
        var processed = 0
        var batchNum = 0
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
                log.info(s"[upload] successfully parsed $email; result=${parsedEmail.toDbgString}")
                if (set.contains(parsedEmail.toString)) {
                  log.info(s"[upload($userId, $origin, ${abookInfo.id}] DUP $email; discarded")
                } else {
                  val nameOpt = mkName((contact \ "name").asOpt[String] trimOpt, fName, lName, email)
                  val e = EContact(userId = userId, email = parsedEmail.toString, name = nameOpt, firstName = fName, lastName = lName)
                  if (insertOnDupUpdate) { // mysql too slow -- can try tuning but likely to be removed
                    db.readWrite { implicit s =>
                      try {
                        econtactRepo.insertOnDupUpdate(userId, e)
                      } catch {
                        case ex:MySQLIntegrityConstraintViolationException => {
                          log.warn(s"[insertOnDupUpd($userId,$e)] ex: $ex; ${ex.getCause}") // ignore
                        }
                        case t:Throwable => throw t
                      }
                    }
                  } else { // todo: extra check for 'obvious typos'
                    set += parsedEmail.toString
                    econtactsToAddBuilder += e
                  }
                }
              } else {
                log.warn(s"[upload($userId, $origin)] cannot parse $email. discarded") // todo: revisit
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
          abookEntry = db.readWrite { implicit s =>
            if (!insertOnDupUpdate) {
              try {
                econtactRepo.insertAll(userId, econtactsToAdd.toSeq)
                log.info(s"[insertAll($userId)] added batch#${batchNum}(sz=${econtactsToAdd.length}) to econtacts: ${econtactsToAdd.map(_.email).mkString}")
              } catch {
                case ex:MySQLIntegrityConstraintViolationException => {
                  log.warn(s"[insertAll($userId)] ex: $ex; ${ex.getCause}") // ignore
                }
                case t:Throwable => throw t
              }
            }
            abookInfoRepo.save(abookEntry.withNumProcessed(Some(processed))) // status update
          }
        }
        abookEntry = db.readWrite { implicit s => // don't wait for contact table
          val saved = abookInfoRepo.save(abookEntry.withState(ABookInfoStates.ACTIVE))
          log.info(s"[upload($userId,${saved.id})] abook is ACTIVE! $saved")
          saved
        }

        val contacts = cBuilder.result
        if (!contacts.isEmpty) {
          db.readWrite { implicit session =>
            contactRepo.deleteAndInsertAll(userId, abookInfo.id.get, origin, contacts)
          }
        }
        log.info(s"[upload($userId,$origin)] time-lapsed: ${System.currentTimeMillis - ts}")
      }
    }
    case m => throw new UnsupportedActorMessage(m)
  }

}
