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
    implicit val libraryFormat = ExternalId.format[Library]
    val (name, visibility, description, slug, collabs, follows) = {
      val json = request.body
      val name = (json \ "name").as[String]
      val visibility = (json \ "visibility").as[String]
      val descript = (json \ "description").asOpt[String]
      val slug = (json \ "slug").as[String]
      val collabs = (json \ "collaborators").as[Seq[ExternalId[User]]]
      val follows = (json \ "followers").as[Seq[ExternalId[User]]]

      (name, visibility, descript, slug, collabs, follows)
    }

    val libRequest = LibraryAddRequest(name = name, visibility = visibility,
      description = description, slug = slug, collaborators = collabs, followers = follows)

    libraryCommander.addLibrary(libRequest, request.userId) match {
      case Left(LibraryFail(message)) => BadRequest(Json.obj("error" -> message))
      case Right(newLibrary) => Ok(Json.toJson(newLibrary))
    }
  }
}
