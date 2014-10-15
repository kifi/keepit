package com.keepit.shoebox.model

import java.util.UUID

import com.keepit.shoebox.model.ids.UserSessionExternalId
import org.specs2.mutable.Specification

class IdsTest extends Specification {

  "UserSessionExternalId" should {
    "UserSessionExternalId creation" in {
      UserSessionExternalId(UUID.randomUUID.toString) //just creating, should be good
      UserSessionExternalId("bad_id") must throwA[Exception]
    }
  }

}
