package com.keepit.common.db

import java.util.UUID
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
    
    "be created with uuid" in {
      val id = ExternalId(UUID.randomUUID.toString)
      id.id === id.uuid
    }
    
    "be created with uuid and path" in {
      val uuid = UUID.randomUUID.toString
      val id = ExternalId("foo/bar/" + uuid)
      uuid === id.uuid
    }
    
    "fail with no uuid" in {
      try {
        val id = ExternalId("asdf")
        failure("id should not have been created: " + id)
      } catch {
        case e => success
      }
    }
  }
}
