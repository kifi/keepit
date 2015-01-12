package com.keepit.commanders

import com.keepit.common.time.FakeClock
import org.specs2.mutable.Specification
import play.api.libs.json.Json

class RawBookmarkRepresentationTest extends Specification {
  "BookmarkInterner" should {

    "toRawBookmark from top level" in {
      val raw = new RawBookmarkFactory(null, new FakeClock()).toRawBookmarks(Json.parse("""{"bookmarks":[{"title":"Presto | Distributed SQL Query Engine for Big Data","url":"http://prestodb.io/","isPrivate":false}],"source":"HOVER_KEEP"}"""))
      raw.size === 1
      raw(0).url === "http://prestodb.io/"
    }
    "toRawBookmark from array" in {
      val raw = new RawBookmarkFactory(null, new FakeClock()).toRawBookmarks(Json.parse("""[{"title":"Presto | Distributed SQL Query Engine for Big Data","url":"http://prestodb.io/","isPrivate":false}]"""))
      raw.size === 1
      raw(0).url === "http://prestodb.io/"
    }
    "toRawBookmark from single object" in {
      val raw = new RawBookmarkFactory(null, new FakeClock()).toRawBookmarks(Json.parse("""{"title":"Presto | Distributed SQL Query Engine for Big Data","url":"http://prestodb.io/","isPrivate":false}"""))
      raw.size === 1
      raw(0).url === "http://prestodb.io/"
    }
    "getBookmarkJsonObjects" in {
      val raw = new RawBookmarkFactory(null, new FakeClock()).getBookmarkJsonObjects(Json.parse("""{"bookmarks":[{"title":"Presto | Distributed SQL Query Engine for Big Data","url":"http://prestodb.io/","isPrivate":false}],"source":"HOVER_KEEP"}"""))
      raw.size === 1
      raw(0) === Json.parse("""{"title":"Presto | Distributed SQL Query Engine for Big Data","url":"http://prestodb.io/","isPrivate":false}""")
    }
  }
}
