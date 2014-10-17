package com.keepit.commanders

object DefaultKeeps {
  val orderedTags: Seq[String] = Seq(
    "Recipe",
    "Shopping Wishlist",
    "Travel",
    "Read Later",
    "Funny",
    "Example Keep",
    "kifi Support"
  )

  val orderedKeepsWithTags: Seq[(KeepInfo, Seq[String])] = {
    val Seq(recipe, shopping, travel, later, funny, example, support) = orderedTags
    Seq(
      // Example keeps
      (KeepInfo(title = None, url = "http://joythebaker.com/2013/12/curry-hummus-with-currants-and-olive-oil/", isPrivate = true), Seq(example, recipe)),
      (KeepInfo(title = None, url = "http://www.amazon.com/Hitchhikers-Guide-Galaxy-25th-Anniversary/dp/1400052920/", isPrivate = true), Seq(example, shopping)),
      (KeepInfo(title = None, url = "https://www.airbnb.com/locations/san-francisco/mission-district", isPrivate = true), Seq(example, travel)),
      (KeepInfo(title = None, url = "http://twistedsifter.com/2013/01/50-life-hacks-to-simplify-your-world/", isPrivate = true), Seq(example, later)),
      (KeepInfo(title = None, url = "http://www.youtube.com/watch?v=_OBlgSz8sSM", isPrivate = true), Seq(example, funny)),

      // Support Keeps
      (KeepInfo(title = Some("kifi • Install kifi on Firefox and Chrome"), url = "https://www.kifi.com/install", isPrivate = true), Seq(support)),
      (KeepInfo(title = Some("kifi • How to Use kifi"), url = "http://support.kifi.com/customer/portal/articles/1397866-introduction-to-kifi-", isPrivate = true), Seq(support)),
      (KeepInfo(title = Some("kifi • Contact Us"), url = "http://support.kifi.com/customer/portal/emails/new", isPrivate = true), Seq(support)),
      (KeepInfo(title = Some("kifi • Find friends your friends on kifi"), url = "https://www.kifi.com/friends/invite", isPrivate = true), Seq(support))
    )
  }
}
