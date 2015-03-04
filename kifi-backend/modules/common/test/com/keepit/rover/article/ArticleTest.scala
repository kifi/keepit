package com.keepit.rover.article

import org.specs2.mutable.Specification

class ArticleTest extends Specification {
  "Article" should {
    "instantiate consistent Article types" in {
      ArticleKind.all must not be empty
    }
  }
}
