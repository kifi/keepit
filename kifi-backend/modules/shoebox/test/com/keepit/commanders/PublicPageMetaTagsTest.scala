package com.keepit.commanders

import org.specs2.mutable.Specification

class PublicPageMetaTagsTest extends Specification {
  "PublicPageMetaTags" should {
    "create description" in {
      PublicPageMetaTags.generateMetaTagsDescription(None, "Joe J", "Cows", Some("this is a very good desc of the page, its a bit long but not too much.")) ===
        "Joe J's Cows Library. this is a very good desc of the page, its a bit long but not too much."
      PublicPageMetaTags.generateMetaTagsDescription(None, "Joe J", "Cows", None) ===
        "Joe J's Cows Library. Kifi -- the smartest way to collect, discover, and share knowledge"
      PublicPageMetaTags.generateMetaTagsDescription(Some("Eat Chicken!"), "Joe Joegineie", "Cows", None) ===
        "Joe Joegineie's Cows Library: Eat Chicken!. Kifi -- the smartest way to collect, discover, and share knowledge"
      PublicPageMetaTags.generateMetaTagsDescription(None, "John Jacob Jingleheimer Schmidt", "That's My Name too", None) ===
        "John Jacob Jingleheimer Schmidt's That's My Name too Library. Kifi -- the smartest way to collect, discover, and share knowledge"
      PublicPageMetaTags.generateMetaTagsDescription(Some(
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
