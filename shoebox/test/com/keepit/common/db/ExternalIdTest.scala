package com.keepit.common.db

import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner.JUnitRunner
import play.api.Play.current
import play.api.libs.json.Json
import play.api.test._
import play.api.test.Helpers._
import scala.collection.mutable.{Map => MutableMap}

@RunWith(classOf[JUnitRunner])
class ExternalIdTest extends SpecificationWithJUnit {

  "ExternalId" should {
    "be created" in {
      val id = ExternalId()
      id.id === id.uuid
    }
  }
}
