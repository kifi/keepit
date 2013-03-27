package com.keepit.search.index

import com.keepit.test.EmptyApplication
import com.keepit.search.Lang
import org.specs2.mutable._
import play.api.Play.current
import play.api.libs.json.Json
import play.api.test._
import play.api.test.Helpers._
import com.keepit.search.graph.UserToUserEdgeSet
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.util.Version
import org.apache.lucene.search.Query
import scala.util.Random

class IdMapperTest extends Specification {
  val rnd = new Random

  "ReverseArrayMapper" should {
    "map ids back to indexes" in {
      var seen = Set.empty[Long]
      val data = new Array[Long](1000)
      val nodata = new Array[Long](100)
      var i = 0

      while (i < data.length) {
        val v = rnd.nextLong
        if (!seen.contains(v)) {
          seen = seen + v
          data(i) = v
          i += 1
        }
      }
      i = 0
      while (i < nodata.length) {
        val v = rnd.nextLong
        if (!seen.contains(v)) {
          seen = seen + v
          nodata(i) = v
          i += 1
        }
      }

      Seq(0.7, 0.9, 1.0, 2.0, 8.0).forall { f =>
        val revMapper = ReverseArrayMapper(data, f)
        i = 0
        while (i < data.length) {
          revMapper(data(i)) === i
          i += 1
        }
        i = 0
        while (i < nodata.length) {
          revMapper(nodata(i)) === -1
          i += 1
        }
        true
      } === true
    }

    "map ids (with deletes) back to indexes " in {
      var seen = Set.empty[Long]
      val data = new Array[Long](1000)
      val nodata = new Array[Long](100)
      var i = 0

      while (i < data.length) {
        val v = rnd.nextLong
        data(i) = if (v % 100 == 0 || seen.contains(v)) -1 else v
        i += 1
      }
      i = 0
      while (i < nodata.length) {
        val v = rnd.nextLong
        if (!seen.contains(v)) {
          seen = seen + v
          nodata(i) = v
          i += 1
        }
      }

      val revMapper = ReverseArrayMapper(data, 1.0)
      i = 0
      while (i < data.length) {
        val v = data(i)
        revMapper(v) === (if (v == -1) -1 else i)
        i += 1
      }
      i = 0
      while (i < nodata.length) {
        revMapper(nodata(i)) === -1
        i += 1
      }
    }
  }
}
