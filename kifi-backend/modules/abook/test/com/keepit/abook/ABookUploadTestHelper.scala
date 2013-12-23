package com.keepit.abook

import com.keepit.common.db.Id
import com.keepit.model.User
import play.api.libs.json.Json

trait ABookUploadTestHelper {
  val u42 = Id[User](42)
  val c42 = Json.arr(
    Json.obj(
      "name" -> "foo bar",
      "firstName" -> "foo",
      "lastName" -> "bar",
      "emails" -> Seq("foo@42go.com", "bar@42go.com")),
    Json.obj(
      "name" -> "forty two",
      "firstName" -> "forty",
      "lastName" -> "two",
      "emails" -> Seq("fortytwo@42go.com", "Foo@42go.com ", "BAR@42go.com  ")),
    Json.obj(
      "name" -> "ray",
      "firstName" -> "ray",
      "lastName" -> "ng",
      "emails" -> Seq("ray@42go.com", " rAy@42GO.COM "))
  )

  val c53 = Json.arr(
    Json.obj(
      "name" -> "fifty three",
      "firstName" -> "fifty",
      "lastName" -> "three",
      "emails" -> Seq("fiftythree@53go.com"))
  )

  val iosUploadJson = Json.obj(
    "origin" -> "ios",
    "contacts" -> c42
  )
}

