import com.google.inject.Inject
import com.keepit.commanders._
import com.keepit.common.controller.{ ShoeboxServiceController, WebsiteController, ActionAuthenticator }
import com.keepit.common.db.ExternalId
import com.keepit.common.db.slick.Database
import com.keepit.common.time.Clock
import com.keepit.model._
import play.api.libs.json.Json

class LibraryController @Inject() (
  db: Database,
  libraryRepo: LibraryRepo,
  libraryCommander: LibraryCommander,
  actionAuthenticator: ActionAuthenticator,
  clock: Clock)
    extends WebsiteController(actionAuthenticator) with ShoeboxServiceController {

  def addLibrary() = JsonAction.authenticatedParseJson { request =>
    val addReq = request.body.as[LibraryAddRequest]
    val libRequest = LibraryAddRequest(name = addReq.name, visibility = addReq.visibility,
      description = addReq.description, slug = addReq.slug, collaborators = addReq.collaborators, followers = addReq.followers)

    libraryCommander.addLibrary(libRequest, request.userId) match {
      case Left(LibraryFail(message)) => BadRequest(Json.obj("error" -> message))
      case Right(newLibrary) => Ok(Json.toJson(newLibrary))
    }
  }
}
