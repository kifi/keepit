package com.keepit.test.specs2

import org.specs2.mutable.SpecificationLike
import org.specs2.specification.{ Step, Fragments }

trait BeforeAllAfterAll { this: SpecificationLike =>
  override def map(fragments: => Fragments) = Step(beforeAll) ^ fragments ^ Step(afterAll)
  def beforeAll(): Unit = {}
  def afterAll(): Unit = {}
}
