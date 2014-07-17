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
    val addRequest = request.body.as[LibraryAddRequest]

    libraryCommander.addLibrary(addRequest, request.userId) match {
      case Left(LibraryFail(message)) => BadRequest(Json.obj("error" -> message))
      case Right(newLibrary) => Ok(Json.toJson(newLibrary))
    }
  }
}
