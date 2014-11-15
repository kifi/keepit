package com.keepit.commanders

import org.specs2.mutable.Specification

class PublicPageMetaTagsTest extends Specification {
  "PublicPageMetaTags" should {
    "create description" in {
      PublicPageMetaTags.generateMetaTagsDescription(None, "Joe J", "Cows") ===
        "Joe J's Cows Kifi Library. Kifi -- the smartest way to collect, discover, and share knowledge"
      PublicPageMetaTags.generateMetaTagsDescription(Some("Eat Chicken!"), "Joe J", "Cows") ===
        "Joe J's Cows Kifi Library: Eat Chicken!. Kifi -- Connecting people with knowledge"
      PublicPageMetaTags.generateMetaTagsDescription(None, "John Jacob Jingleheimer Schmidt", "That's My Name too") ===
        "John Jacob Jingleheimer Schmidt's That's My Name too Kifi Library. Kifi -- Connecting people with knowledge"
      PublicPageMetaTags.generateMetaTagsDescription(Some(
        """
          |John Jacob Jingleheimer Schmidt,
          |His name is my name too.
          |Whenever we go out,
          |The people always shout,
          |"John Jacob Jingleheimer Schmidt!"
          |Da da da da da da da da
        """.stripMargin.trim), "John Jacob Jingleheimer Schmidt", "That's My Name too") ===
        """
          |John Jacob Jingleheimer Schmidt,
          |His name is my name too.
          |Whenever we go out,
          |The people always shout,
          |"John Jacob Jingleheimer Schmidt!"
          |Da da da da da da da da
        """.stripMargin.trim
    }
  }
}
