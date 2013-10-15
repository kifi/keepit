package com.keepit.abook

import com.google.inject.{ImplementedBy, Inject}
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.model._
import com.keepit.common.db.Id
import scala.collection.mutable
import java.io.File
import com.keepit.abook.store.ABookRawInfoStore
import play.api.libs.json.{JsArray, JsValue, Json}
import play.api.libs.json.Json._
import ABookRawInfo._
import com.keepit.common.logging.Logging
import com.keepit.common.db.slick.Database
import play.api.Plugin
import scala.ref.WeakReference
import com.keepit.common.plugin.{SchedulingProperties, SchedulingPlugin}
import com.keepit.common.actor.ActorInstance


@ImplementedBy(classOf[ContactsUpdaterPluginImpl])
trait ContactsUpdaterPlugin extends Plugin {
  def processContacts(userId:Id[User], origin:ABookOriginType, abookRepoEntry:ABookInfo, s3Key:String, rawJsonRef:WeakReference[JsValue])
}

class ContactsUpdaterPluginImpl @Inject() (
  actor:ActorInstance[ContactsUpdater]
) extends ContactsUpdaterPlugin with Logging {

  def processContacts(userId: Id[User], origin: ABookOriginType, abookRepoEntry: ABookInfo, s3Key: String, rawJsonRef: WeakReference[JsValue]): Unit = {
    actor.ref ! ProcessABookUpload(userId, origin, abookRepoEntry, s3Key, rawJsonRef)
  }

}

case class ProcessABookUpload(userId:Id[User], origin:ABookOriginType, abookRepoEntry: ABookInfo, s3Key:String, rawJsonRef:WeakReference[JsValue])

class ContactsUpdater @Inject() (
  db: Database,
  s3:ABookRawInfoStore,
  contactInfoRepo:ContactInfoRepo,
  airbrake:AirbrakeNotifier
) extends FortyTwoActor(airbrake) with Logging {
  def receive() = {
    case ProcessABookUpload(userId, origin, abookRepoEntry, s3Key, rawJsonRef) => {
      log.info(s"[upload($userId, $origin, $abookRepoEntry, $s3Key): Begin processing ...")
      origin match {
        case ABookOrigins.IOS => {
          val ts = System.currentTimeMillis
          val rawInfoJson = rawJsonRef.get match {
            case Some(js) => js
            case None => {
              val rawInfo = s3.get(s3Key).getOrElse(throw new IllegalStateException)
              Json.toJson(rawInfo)
            }
          }
          val abookRawInfo = Json.fromJson[ABookRawInfo](rawInfoJson).getOrElse(throw new IllegalArgumentException) // TODO:REVISIT exception in actor
          val contactInfoBuilder = mutable.ArrayBuilder.make[ContactInfo]
          abookRawInfo.contacts.value.foreach {
            contact =>
              val firstName = (contact \ "firstName").asOpt[String]
              val lastName = (contact \ "lastName").asOpt[String]
              val name = (contact \ "name").asOpt[String].getOrElse((firstName.getOrElse("") + " " + lastName.getOrElse("")).trim)
              val emails = (contact \ "emails").as[Seq[String]]
              emails.foreach {
                email =>
                  val cInfo = ContactInfo(
                    userId = userId,
                    origin = ABookOrigins.IOS,
                    abookId = abookRepoEntry.id.get,
                    name = Some(name),
                    firstName = firstName,
                    lastName = lastName,
                    email = email)
                  log.info(s"[upload($userId,$origin)] contact=$cInfo")
                  contactInfoBuilder += cInfo
              }
          }
          val contactInfos = contactInfoBuilder.result
          log.info(s"[upload($userId,$origin) #contacts=${contactInfos.length} contacts=${contactInfos.mkString(File.separator)}")
          if (!contactInfos.isEmpty) {
            // TODO: optimize/batch
            db.readWrite {
              implicit session =>
                contactInfos.foreach {
                  contactInfoRepo.save(_)
                }
            }
          }
          log.info(s"[upload($userId,$origin)] time-lapsed: ${System.currentTimeMillis - ts}")
        }
        case _ => // not (yet) handled
      }
    }
  }

}
