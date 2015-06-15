package com.keepit.controllers.ext

import com.keepit.commanders._
import com.keepit.common.controller.{ ShoeboxServiceController, UserActions, UserActionsHelper }
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.model.{ ExperimentType, Library }

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.Json

import com.google.inject.Inject

class ExtUserController @Inject() (
  val userActionsHelper: UserActionsHelper,
  typeAheadCommander: TypeaheadCommander,
  userPersonaCommander: UserPersonaCommander,
  implicit val config: PublicIdConfiguration)
    extends UserActions with ShoeboxServiceController {

  def searchForContacts(query: Option[String], limit: Option[Int]) = UserAction.async { request =>
    typeAheadCommander.searchForContacts(request.userId, query.getOrElse(""), limit) map { res =>
      val res1 = res.collect {
        case u: UserContactResult => Json.toJson(u)
        case e: EmailContactResult => Json.toJson(e)
        case a: AliasContactResult if request.experiments.contains(ExperimentType.ADMIN) => Json.toJson(a)
      }
      Ok(Json.toJson(res1))
    }
  }

  def getGuideInfo() = UserAction { request =>
    val (personaKeep, libOpt) = userPersonaCommander.getPersonaKeepAndLibrary(request.userId)
    Ok(Json.obj(
      "keep" -> personaKeep,
      "library" -> libOpt.map { lib =>
        Json.obj(
          "id" -> Library.publicId(lib.id.get),
          "name" -> lib.name,
          "path" -> Library.formatLibraryPath(request.user.username, lib.slug),
          "color" -> lib.color)
      }
    ))
  }
}
