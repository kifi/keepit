package com.keepit.common.net

import com.google.inject.ImplementedBy
import play.api.libs.ws.WSRequestHolder

@ImplementedBy(classOf[PlayWS])
trait WebService {
  def url(url: String): WSRequestHolder
}

class PlayWS extends WebService {
  import play.api.Play.current
  import play.api.libs.ws.WS

  def url(url: String): WSRequestHolder = WS.url(url)(current)
}
