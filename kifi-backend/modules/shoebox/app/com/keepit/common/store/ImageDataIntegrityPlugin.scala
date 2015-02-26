package com.keepit.common.store

import scala.concurrent.duration._
import com.google.inject.Inject
import com.keepit.common.actor.ActorInstance
import com.keepit.common.akka.{ FortyTwoActor, UnsupportedActorMessage }
import com.keepit.common.db.ExternalId
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.{ AirbrakeNotifier, AirbrakeError }
import com.keepit.common.logging.Logging
import com.keepit.common.net._
import com.keepit.common.plugin._
import com.keepit.model.{ UserPictureRepo, UserStates, UserRepo, User }
import akka.actor.ActorSystem
import play.api.Plugin
import play.api.http.Status.{ OK, NOT_FOUND }
import com.keepit.common.net.NonOKResponseException
import com.keepit.common.net.DirectUrl
import scala.Some

sealed abstract class ImageDataIntegrityMessage
case object VerifyAllPictures extends ImageDataIntegrityMessage

private[store] class ImageDataIntegrityActor @Inject() (
    db: Database,
    userRepo: UserRepo,
    store: S3ImageStore,
    client: HttpClient,
    userPictureRepo: UserPictureRepo,
    airbrake: AirbrakeNotifier) extends FortyTwoActor(airbrake) with Logging {

  val TWO_MINUTES = 2 * 60 * 1000
  private val httpClient: HttpClient = client.withTimeout(CallTimeouts(responseTimeout = Some(TWO_MINUTES)))

  def receive = {
    case VerifyAllPictures =>
      if (store.config.isLocal) {
        log.info("Not verifying pictures since we are not storing images in S3")
      } else {
        log.info("Verifying pictures for all users")
        for (
          user <- db.readOnlyMaster { implicit s =>
            userRepo.allExcluding(UserStates.BLOCKED, UserStates.INACTIVE)
          }
        ) yield {
          for (((url, response), cloudfrontInfo) <- findPictures(user)) {
            if (response.status == NOT_FOUND) {
              log.warn(s"S3 request for avatar at $url returned ${response.status}")
              airbrake.notify(s"S3 avatar for ${user.firstName} ${user.lastName} at $url returned ${response.status}")
            }
            for ((url, response) <- cloudfrontInfo) {
              if (response.status == NOT_FOUND) {
                log.warn(s"Cloudfront request for avatar at $url returned ${response.status}")
                airbrake.notify(s"Cloudfront avatar for ${user.firstName} ${user.lastName} at $url returned ${response.status}")
              }
            }
          }
        }
      }
    case m => throw new UnsupportedActorMessage(m)
  }

  private type ImageResponseInfo = ((String, ClientResponse), Option[(String, ClientResponse)])
  private def findPictures(user: User): Seq[ImageResponseInfo] = {
    val urls: Seq[(String, String)] = {
      S3UserPictureConfig.ImageSizes.map { size =>
        val pics = db.readOnlyMaster { implicit session =>
          userPictureRepo.getByUser(user.id.get)
        }
        pics.map { pic =>
          (s"http://s3.amazonaws.com/${store.config.bucketName}/users/${user.externalId}/pics/$size/${pic.name}.jpg",
            store.avatarUrlByExternalId(Some(size), user.externalId, pic.name, Some("http")))
        }
      }.flatten
    }

    for ((s3url, cfUrl) <- urls) yield {
      get(s3url) match {
        case resp if resp.status == OK => (s3url -> resp, Some(cfUrl -> get(cfUrl)))
        case resp => (s3url -> resp, None)
      }
    }
  }
  private def get(url: String): ClientResponse = try {
    httpClient.get(DirectUrl(url), httpClient.ignoreFailure)
  } catch {
    case NonOKResponseException(_, response, _) => response
  }
}

trait ImageDataIntegrityPlugin extends Plugin {
  def verifyAll()
}

class ImageDataIntegrityPluginImpl @Inject() (
    system: ActorSystem,
    actor: ActorInstance[ImageDataIntegrityActor],
    val scheduling: SchedulingProperties //only on leader
    ) extends SchedulerPlugin with ImageDataIntegrityPlugin {
  def verifyAll() {
    actor.ref ! VerifyAllPictures
  }

  override def onStart() {
    scheduleTaskOnOneMachine(system, 6 hour, 1 day, actor.ref, VerifyAllPictures, VerifyAllPictures.getClass.getSimpleName)
    super.onStart()
  }
}
