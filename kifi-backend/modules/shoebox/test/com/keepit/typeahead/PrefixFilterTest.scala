package com.keepit.typeahead

import org.specs2.mutable._
import play.api.test._
import play.api.test.Helpers._
import com.keepit.common.db.Id

class PrefixFilterTest extends Specification {
  case class Person()

  implicit def intToPersonId(id: Int) = Id[Person](id.toLong)

  "PrefixFilter" should {
    "filter candidates" in {
      val builder = new PrefixFilterBuilder[Person]

      builder.add(1, "Alan Turing")
      builder.add(2, "Alan Kay")
      builder.add(3, "John McCarthy")
      builder.add(4, "Paul McCartney")
      builder.add(5, "John Lennon")
      builder.add(6, "Woody Allen")
      builder.add(7, "Allen Weiner")

      val filter = builder.build

      filter.filterBy(Array("a")) === Seq[Id[Person]](1, 2, 6, 7)
      filter.filterBy(Array("al")) === Seq[Id[Person]](1, 2, 6, 7)
      filter.filterBy(Array("all")) === Seq[Id[Person]](6, 7)
      filter.filterBy(Array("j")) === Seq[Id[Person]](3, 5)
      filter.filterBy(Array("m")) === Seq[Id[Person]](3, 4)
      filter.filterBy(Array("mccart")) === Seq[Id[Person]](3, 4)
      filter.filterBy(Array("a", "w")) === Seq[Id[Person]](6, 7)
      filter.filterBy(Array("w", "a")) === Seq[Id[Person]](6, 7)
      filter.filterBy(Array("a", "k")) === Seq[Id[Person]](2)
    }

    "behave like a set (add is idempotent)" in {
      val builder = new PrefixFilterBuilder[Person]

      builder.add(1, "Alan Turing")
      builder.add(2, "Alan Kay")
      builder.add(3, "John McCarthy")
      builder.add(4, "Paul McCartney")
      builder.add(5, "John Lennon")
      builder.add(6, "Woody Allen")

      val filter = builder.build
      filter.length === 6

      val newBuilder = PrefixFilterBuilder(filter)
      newBuilder.length === 6

      // Existing element from original filter
      newBuilder.add(6, "Woody Allen")
      newBuilder.length === 6

      // New element
      newBuilder.add(7, "Allen Weiner")
      newBuilder.length === 7

      // Existing element
      newBuilder.add(7, "Allen Weiner")
      newBuilder.length === 7

      // Existing id, different name
      newBuilder.add(7, "Allen S. Weiner ")
      newBuilder.length === 8

      // Existing name, different id
      newBuilder.add(8, "Allen S. Weiner ")
      newBuilder.length === 9
    }
  }
}