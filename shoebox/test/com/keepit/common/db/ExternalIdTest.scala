package com.keepit.common.db

import java.util.UUID
import org.specs2.mutable._
import play.api.Play.current
import play.api.libs.json.Json
import play.api.test._
import play.api.test.Helpers._
import scala.collection.mutable.{Map => MutableMap}

class ExternalIdTest extends Specification {

  "ExternalId" should {
    "be created" in {
      val id = ExternalId()
      id.id.length() === "f1e227e4-4b1b-4bfd-91e2-85acf464a148".length()
    }

    "be created with uuid" in {
      val uuid = UUID.randomUUID.toString
      val id = ExternalId(uuid)
      id.id === uuid
    }

    "fail with no uuid" in {
      try {
        val id = ExternalId("asdf")
        failure("id should not have been created: " + id)
      } catch {
        case e: Throwable => success
      }
    }
  }
}
