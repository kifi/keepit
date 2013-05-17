package com.keepit.controllers.shoebox

import com.keepit.common.controller.ShoeboxServiceController
import com.google.inject.Inject
import com.keepit.model.UserRepo
import com.keepit.model.BookmarkRepo
import com.keepit.model.NormalizedURIRepo
import com.keepit.common.db.slick.Database
import com.keepit.model.UserConnectionRepo
import com.keepit.common.logging.Logging
import com.keepit.common.db.Id
import com.keepit.model.NormalizedURI
import com.keepit.serializer.NormalizedURISerializer
import play.api.mvc.Action
import play.api.libs.json.JsValue
import play.api.libs.json.JsArray

class ShoeboxController @Inject() (
  db: Database,
  userConnectionRepo: UserConnectionRepo,
  userRepo: UserRepo,
  bookmarkRepo: BookmarkRepo,
  normUriRepo: NormalizedURIRepo)
  extends ShoeboxServiceController with Logging {
  
  def sendMail = Action(parse.json) { request =>
    
    Ok("true")
  }

  def getNormalizedURI(id: Long) = Action {
    val uri = db.readOnly { implicit s =>
      normUriRepo.get(Id[NormalizedURI](id))
    }
    Ok(NormalizedURISerializer.normalizedURISerializer.writes(uri))
  }

  def getNormalizedURIs = Action(parse.json) { request =>
     val json = request.body
     val ids = json.as[JsArray].value.map( id => id.as[Long] )
     val uris = db.readOnly { implicit s =>
       ids.map{ id =>
         val uri = normUriRepo.get(Id[NormalizedURI](id))
         NormalizedURISerializer.normalizedURISerializer.writes(uri)
       }
     }
     Ok(JsArray(uris))
  }

}
