package com.keepit.common.store

import scala.concurrent.duration._
import com.google.inject.Inject
import com.keepit.common.actor.ActorInstance
import com.keepit.common.akka.{FortyTwoActor, UnsupportedActorMessage}
import com.keepit.common.db.ExternalId
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.{Healthcheck, HealthcheckError, HealthcheckPlugin}
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.net.{NonOKResponseException, ClientResponse, HttpClient}
import com.keepit.common.plugin._
import com.keepit.model.{UserStates, UserRepo, User}
import akka.actor.ActorSystem
import play.api.Plugin
import play.api.http.Status.OK

sealed abstract class ImageDataIntegrityMessage
case object VerifyAllPictures extends ImageDataIntegrityMessage

private[store] class ImageDataIntegrityActor @Inject() (
    db: Database,
    userRepo: UserRepo,
    store: S3ImageStore,
    client: HttpClient,
    healthcheckPlugin: HealthcheckPlugin,
    airbrake: AirbrakeNotifier
  ) extends FortyTwoActor(airbrake) with Logging {

  val TWO_MINUTES = 2 * 60 * 1000
  private val httpClient: HttpClient = client.withTimeout(TWO_MINUTES)

  def receive = {
    case VerifyAllPictures =>
      if (store.config.isLocal) {
        log.info("Not verifying pictures since we are not storing images in S3")
      } else {
        log.info("Verifying pictures for all users")
        for (user <- db.readOnly { implicit s =>
          userRepo.allExcluding(UserStates.BLOCKED, UserStates.INACTIVE)
        }) yield {
          for (((url, response), cloudfrontInfo) <- findPictures(user.externalId)) {
            if (response.status != OK) {
              log.warn(s"S3 request for avatar at $url returned ${response.status}")
              healthcheckPlugin.addError(HealthcheckError(
                callType = Healthcheck.INTERNAL,
                errorMessage = Some(
                  s"S3 avatar for ${user.firstName} ${user.lastName} at $url returned ${response.status}")
              ))
            }
            for ((url, response) <- cloudfrontInfo) {
              if (response.status != OK) {
                log.warn(s"Cloudfront request for avatar at $url returned ${response.status}")
                healthcheckPlugin.addError(HealthcheckError(
                  callType = Healthcheck.INTERNAL,
                  errorMessage = Some(
                    s"Cloudfront avatar for ${user.firstName} ${user.lastName} at $url returned ${response.status}")
                ))
              }
            }
          }
        }
      }
    case m => throw new UnsupportedActorMessage(m)
  }

  private type ImageResponseInfo = ((String, ClientResponse), Option[(String, ClientResponse)])
  private def findPictures(id: ExternalId[User]): Seq[ImageResponseInfo] = {
    val urls: Seq[(String, String)] = S3UserPictureConfig.ImageSizes map { size =>
      (s"http://s3.amazonaws.com/${store.config.bucketName}/users/$id/pics/$size/0.jpg",
          store.avatarUrlByExternalId(size, id, Some("http")))
    }
     for ((s3url, cfUrl) <- urls) yield {
      get(s3url) match {
        case resp if resp.status == OK => (s3url -> resp, Some(cfUrl -> get(cfUrl)))
        case resp => (s3url -> resp, None)
      }
    }
  }
  private def get(url: String): ClientResponse = try {
    httpClient.get(url, httpClient.ignoreFailure)
  } catch {
    case NonOKResponseException(_, response, _) => response
  }
}

trait ImageDataIntegrityPlugin extends Plugin {
  def verifyAll()
}

class ImageDataIntegrityPluginImpl @Inject()(
    system: ActorSystem,
    actor: ActorInstance[ImageDataIntegrityActor],
    val schedulingProperties: SchedulingProperties //only on leader
  ) extends SchedulingPlugin with ImageDataIntegrityPlugin {
  def verifyAll() {
    actor.ref ! VerifyAllPictures
  }

  override def onStart() {
    scheduleTask(system, 2 minutes, 1 hour, actor.ref, VerifyAllPictures)
    super.onStart()
  }
}
