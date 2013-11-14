package com.keepit.abook

import com.google.inject.{Provider, ImplementedBy, Inject}
import com.keepit.common.akka.{FortyTwoActor, UnsupportedActorMessage}
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.model._
import com.keepit.common.db.Id
import scala.collection.mutable
import com.keepit.abook.store.ABookRawInfoStore
import play.api.libs.json.{JsValue, Json}
import com.keepit.common.logging.Logging
import com.keepit.common.db.slick.Database
import play.api.Plugin
import scala.ref.WeakReference
import com.keepit.common.actor.ActorInstance
import scala.util.{Failure, Success}
import akka.actor.{Props, ActorSystem}


@ImplementedBy(classOf[ContactsUpdaterPluginImpl])
trait ContactsUpdaterPlugin extends Plugin {
  def asyncProcessContacts(userId:Id[User], origin:ABookOriginType, aBookInfo:ABookInfo, s3Key:String, rawJsonRef:WeakReference[JsValue])
}

class ContactsUpdaterPluginImpl @Inject() (sysProvider:Provider[ActorSystem], updaterProvider:Provider[ContactsUpdater]) extends ContactsUpdaterPlugin with Logging {

  lazy val system = sysProvider.get
  def actor = system.actorOf(Props { updaterProvider.get })

  def asyncProcessContacts(userId: Id[User], origin:ABookOriginType, aBookInfo: ABookInfo, s3Key: String, rawJsonRef: WeakReference[JsValue]): Unit = {
    actor ! ProcessABookUpload(userId, origin, aBookInfo, s3Key, rawJsonRef)
  }

}

case class ProcessABookUpload(userId:Id[User], origin:ABookOriginType, abookRepoEntry: ABookInfo, s3Key:String, rawJsonRef:WeakReference[JsValue])

class ContactsUpdater @Inject() (
  db: Database,
  s3:ABookRawInfoStore,
  abookInfoRepo:ABookInfoRepo,
  contactInfoRepo:ContactInfoRepo, // not used -- may remove later
  contactRepo:ContactRepo,
  econtactRepo:EContactRepo,
  airbrake:AirbrakeNotifier
) extends FortyTwoActor(airbrake) with Logging {

  private def sOpt(o:Option[String]) = o match {
    case Some(x) => if (x.isEmpty) None else Some(x)
    case None => None
  }

  private def mkName(name:Option[String], firstName:Option[String], lastName:Option[String], email:String) = sOpt(name).getOrElse(
    firstName match {
      case Some(fName) => lastName match {
        case Some(lName) => s"$fName $lName"
        case None => fName
      }
      case None => lastName match {
        case Some(lName) => lName
        case None => (email.split('@'))(0) // may need a parser
      }
    }
  )

  def receive() = {
    case ProcessABookUpload(userId, origin, abookInfo, s3Key, rawJsonRef) => {
      log.info(s"[upload($userId, $origin, $abookInfo, $s3Key): Begin processing ...")
      val ts = System.currentTimeMillis
      val rawInfoJson = rawJsonRef.get getOrElse {
          val rawInfo = s3.get(s3Key).getOrElse(throw new IllegalStateException(s"[upload] s3Key=$s3Key is not set")) // notify user?
          Json.toJson(rawInfo)
      }
      val abookRawInfo = Json.fromJson[ABookRawInfo](rawInfoJson).getOrElse(throw new IllegalArgumentException(s"[upload] Cannot parse $rawInfoJson")) // notify user?
      val cBuilder = mutable.ArrayBuilder.make[Contact]
      abookRawInfo.contacts.value.foreach {
        contact =>
          val fName = sOpt((contact \ "firstName").asOpt[String])
          val lName = sOpt((contact \ "lastName").asOpt[String])
          val emails = (contact \ "emails").validate[Seq[String]].fold(
            valid = ( res => res),
            invalid = ( e => {
              log.warn(s"[upload($userId,$origin)] Cannot parse $e")
              Seq.empty[String]
            })
          )
          emails.foreach { email =>
            val name = mkName(sOpt((contact \ "name").asOpt[String]), fName, lName, email)
            val parseResult = EmailParser.parseAll(EmailParser.email, email)
            if (parseResult.successful) {
              val parsedEmail:Email = parseResult.get
              log.info(s"[upload] successfully parsed $email; result=${parsedEmail.toDbgString}")
              db.readWrite { implicit s =>
                val e = EContact(userId = userId, email = parsedEmail.toString, name = name, firstName = fName, lastName = lName)
                econtactRepo.insertOnDupUpdate(userId, e)
              }
            } else {
              log.warn(s"[upload($userId, $origin)] cannot parse $email. discarded") // todo: revisit
            }
          }
          for (email <- emails.headOption) {
            val name = mkName(sOpt((contact \ "name").asOpt[String]), fName, lName, email)
            val c = Contact.newInstance(userId, abookInfo.id.get, email, emails.tail, origin, Some(name), fName, lName, None)
            log.info(s"[upload($userId,$origin)] $c")
            cBuilder += c
          }
      }
      val contacts = cBuilder.result
      if (!contacts.isEmpty) {
        db.readWrite { implicit session =>
          contactRepo.deleteAndInsertAll(userId, abookInfo.id.get, origin, contacts)
        }
      }
      db.readWrite { implicit s =>
        abookInfoRepo.save(abookInfo.withState(ABookInfoStates.ACTIVE))
      }
      log.info(s"[upload($userId,$origin)] time-lapsed: ${System.currentTimeMillis - ts}")
    }
    case m => throw new UnsupportedActorMessage(m)
  }

}
