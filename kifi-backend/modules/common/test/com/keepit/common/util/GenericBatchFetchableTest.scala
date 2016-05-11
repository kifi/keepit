package com.keepit.common.util

import com.keepit.common.db.Id
import org.specs2.mutable.Specification
import play.api.libs.functional.syntax._
import GenericBatchFetchable._

class GenericBatchFetchableTest extends Specification {
  case class FooInt(id: Id[FooInt], value: Int)
  val intRepo: Map[Id[FooInt], FooInt] = Seq(
    FooInt(Id(1), 1),
    FooInt(Id(2), 2),
    FooInt(Id(17), 17)
  ).map(x => x.id -> x).toMap
  object IntFetcher extends BatchFetcher[Id[FooInt], Int] {
    def fetch(keys: Set[Id[FooInt]]): Map[Id[FooInt], Int] = keys.flatMap(k => intRepo.get(k).map(k -> _.value)).toMap
  }
  object IntKind extends BatchFetchKind {
    type K = Id[FooInt]
    type V = Int
    def fetcher = IntFetcher
  }

  case class FooString(id: Id[FooString], value: String)
  val strRepo: Map[Id[FooString], FooString] = Seq(
    FooString(Id(1), "one"),
    FooString(Id(2), "two"),
    FooString(Id(17), "seventeen")
  ).map(x => x.id -> x).toMap
  object StrFetcher extends BatchFetcher[Id[FooString], String] {
    def fetch(keys: Set[Id[FooString]]): Map[Id[FooString], String] = keys.flatMap(k => strRepo.get(k).map(k -> _.value)).toMap
  }
  object StrKind extends BatchFetchKind {
    type K = Id[FooString]
    type V = String
    def fetcher = StrFetcher
  }

  "GenericBatchFetchable" should {
    "make Derek's dream come true" in {
      val result = (IntKind.get(Id(1)) and IntKind.get(Id(17)) and StrKind.get(Id(2))).tupled.map {
        case (Some(int1), Some(int17), Some(str2)) => s"Cool, look at ($int1, $int17, $str2)"
        case _ => "Sad day, something you requested wasn't there..."
      }.run

      result === "Cool, look at (1, 17, two)"
    }
  }
}

