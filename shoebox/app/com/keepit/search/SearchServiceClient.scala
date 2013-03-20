package com.keepit.search

import com.keepit.common.controller.{ServiceClient, ServiceType}
import com.keepit.common.db.Id
import com.keepit.common.net.HttpClient
import com.keepit.model.{NormalizedURI, User}

import play.api.libs.json.Json
import com.keepit.controllers.search.ResultClicked
import com.keepit.controllers.search.routes

trait SearchServiceClient extends ServiceClient {
  final val serviceType = ServiceType.SEARCH

  def logResultClicked(userId: Id[User], query: String, uriId: Id[NormalizedURI], isUserKeep: Boolean)
}
class SearchServiceClientImpl(override val host: String, override val port: Int, override val httpClient: HttpClient)
    extends SearchServiceClient {

  import com.keepit.controllers.search.ResultClickedJson._

  def logResultClicked(userId: Id[User], query: String, uriId: Id[NormalizedURI], isUserKeep: Boolean) {
    val json = Json.toJson(ResultClicked(userId, query, uriId, isUserKeep))
    call(routes.SearchEventController.logResultClicked(), json)
  }

}
