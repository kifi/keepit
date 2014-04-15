package com.keepit.graph

import com.keepit.inject.{CommonServiceModule, ConfigurationModule}


case class ReplaceMeLéo(foo: Int, bar: String)

object ReplaceMeLéo {
  import play.api.libs.json.Json
  implicit val format = Json.format[ReplaceMeLéo]
}

abstract class GraphModule() extends ConfigurationModule with CommonServiceModule {

}
