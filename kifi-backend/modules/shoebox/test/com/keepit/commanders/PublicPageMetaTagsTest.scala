package com.keepit.commanders

import org.specs2.mutable.Specification

class PublicPageMetaTagsTest extends Specification {
  "PublicPageMetaTags" should {
    "create description for libs" in {
      PublicPageMetaTags.generateLibraryMetaTagDescription(None, "Joe J", "Cows", Some("this is a very good desc of the page, its a bit long but not too much.")) ===
        "Joe J's Cows Library. this is a very good desc of the page, its a bit long but not too much."
      PublicPageMetaTags.generateLibraryMetaTagDescription(None, "Joe J", "Cows", None) ===
        "Joe J's Cows Library. Kifi -- the smartest way to collect, discover, and share knowledge"
      PublicPageMetaTags.generateLibraryMetaTagDescription(Some("Eat Chicken!"), "Joe J", "Cows", None) ===
        "Joe J's Cows Library: Eat Chicken!. Kifi -- the smartest way to collect, discover, and share knowledge"
      PublicPageMetaTags.generateLibraryMetaTagDescription(None, "John Jacob Jingleheimer Schmidt", "That's My Name too", None) ===
        "John Jacob Jingleheimer Schmidt's That's My Name too Library. Kifi -- Connecting people with knowledge"
      PublicPageMetaTags.generateLibraryMetaTagDescription(Some(
        """
          |John Jacob Jingleheimer Schmidt,
          |His name is my name too.
          |Whenever we go out,
          |The people always shout,
          |"John Jacob Jingleheimer Schmidt!"
          |Da da da da da da da da
        """.stripMargin.trim), "John Jacob Jingleheimer Schmidt", "That's My Name too", None) ===
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
