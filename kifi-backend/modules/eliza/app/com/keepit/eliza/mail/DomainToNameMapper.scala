package com.keepit.eliza.mail

object DomainToNameMapper {

  def getName(domain: String): Option[String] = {
    domainToName.get(domain)
  }

  /**
   * Protip: this map can be generated from a TSV file by tools/domain_map_parser.py
   */
  private val domainToName = Map(
    "360.cn" -> "360 Safeguard",
    "360buy.com" -> "360buy",
    "9gag.com" -> "9GAG",
    "abc7news.com" -> "ABC 7News",
    "about.com" -> "About",
    "adf.ly" -> "AdFly",
    "adobe.com" -> "Adobe",
    "airbnb.com" -> "Airbnb",
    "alibaba.com" -> "Alibaba Group",
    "alipay.com" -> "Alipay",
    "allthingsd.com" -> "All Things Digital",
    "amazon.com" -> "Amazon",
    "amazon.co.uk" -> "Amazon",
    "amazon.de" -> "Amazon",
    "amazon.co.jp" -> "Amazon",
    "aol.com" -> "Aol",
    "apple.com" -> "Apple",
    "apptamin.com" -> "Aptamin",
    "arstechnica.com" -> "Ars Technia",
    "ask.com" -> "Ask",
    "avg.com" -> "AVG Technologies",
    "babylon.com" -> "Babylon",
    "baidu.com" -> "Baidu",
    "bankofamerica.com" -> "Bank of America",
    "news.bbc.co.uk" -> "BBC",
    "bbc.co.uk" -> "BBC",
    "behance.net" -> "Behance",
    "bing.com" -> "Bing",
    "blogger.com" -> "Blogger",
    "blogspot.com" -> "Blogspot",
    "blogspot.in" -> "Blogspot",
    "businessinsider.com" -> "Business Insider",
    "buzzfeed.com" -> "BuzzFeed",
    "calcalist.co.il" -> "Calcalist",
    "careerbuilder.com" -> "CareerBuilder",
    "chase.com" -> "Chase",
    "cnet.com" -> "CNET",
    "news.com" -> "CNET",
    "cnn.com" -> "CNN",
    "cnn.com/" -> "CNN",
    "codecademy.com" -> "Codecademy",
    "codedicks.com" -> "codedicks",
    "colourlovers.com" -> "COLOURlovers",
    "conduit.com" -> "Conduit",
    "team42.atlassian.net" -> "Confluence",
    "coursera.org" -> "Coursera",
    "craigslist.org" -> "craigslist",
    "crunchbase.com" -> "CrunchBase",
    "dailymail.co.uk" -> "Daily Mail",
    "dailymotion.com" -> "Dailymotion",
    "dell.com" -> "Dell",
    "deviantart.com" -> "deviantART",
    "digg.com" -> "Digg",
    "disney.go.com" -> "Disney",
    "dribbble.com" -> "Dribbble",
    "dropbox.com" -> "Dropbox",
    "ebay.co.uk" -> "eBay",
    "ebay.com" -> "Ebay",
    "ebay.de" -> "eBay",
    "engadget.com" -> "Engadget",
    "espn.go.com" -> "ESPN",
    "etsy.com" -> "Etsy",
    "evernote.com" -> "Evernote",
    "expedia.com" -> "Expedia",
    "facebook.com" -> "Facebook",
    "fc2.com" -> "FC2",
    "flickr.com" -> "Flickr",
    "flipkart.com" -> "Flipkart",
    "fontsquirrel.com" -> "Font Squirrel",
    "forbes.com" -> "Forbes",
    "42go.com" -> "FortyTwo",
    "fourseasons.com" -> "FourSeasons",
    "foxnews.com" -> "Fox News",
    "funnyordie.com" -> "Funny or Die",
    "github.com" -> "GitHub",
    "gizmodo.com" -> "Gizmodo",
    "globes.co.il" -> "Globes",
    "mail.google.com" -> "Gmail",
    "go.com" -> "Go",
    "google.co.jp" -> "Googl",
    "google.pl" -> "Google",
    "google.it" -> "Google",
    "google.com.br" -> "Google",
    "google.fr" -> "Google",
    "googleusercontent.com" -> "Google",
    "google.com.tr" -> "Google",
    "google.co.in" -> "Google",
    "google.co.id" -> "Google",
    "google.de" -> "Google",
    "google.es" -> "Google",
    "google.ru" -> "Google",
    "google.co.uk" -> "Google",
    "google.ca" -> "Google",
    "google.com" -> "Google",
    "calendar.google.com" -> "Google Calendar",
    "drive.google.com" -> "Google Drive",
    "maps.google.com" -> "Google Maps",
    "news.google.com" -> "Google News",
    "google.com.au" -> "Google Poland",
    "translate.google.com" -> "Google Translate",
    "google.com.mx" -> "Goolge",
    "grooveshark.com" -> "GrooveShark",
    "haaretz.co.il" -> "Haaretz",
    "hao123.com" -> "Hao123",
    "hulu.com" -> "Hulu",
    "icloud.com" -> "iCloud",
    "iconmonstr.com" -> "iconmonstr",
    "ifeng.com" -> "Ifeng News",
    "ikea.com" -> "Ikea",
    "imdb.com" -> "IMDb",
    "imgur.com" -> "Imgur",
    "indeed.com" -> "Indeed",
    "indiatimes.com" -> "Indiatimes",
    "instagram.com" -> "Instagram",
    "itunes.apple.com" -> "iTunes",
    "joythebaker.com" -> "Joy the Baker",
    "jsfiddle.net" -> "JSFiddle",
    "kayak.com" -> "Kayak",
    "khanacademy.org" -> "Khan Academy",
    "kickstarter.com" -> "Kickstarter",
    "kifi.com/" -> "Kifi",
    "blog.kifi.com" -> "Kifi Blog",
    "support.kifi.com" -> "Kifi Support",
    "lifehacker.com" -> "Lifehacker",
    "linkedin.com" -> "LinkedIn",
    "livedoor.com" -> "Livedoor",
    "livejasmin.com" -> "LiveJasmin",
    "livejournal.com" -> "LiveJournal",
    "latimes.com" -> "Los Angeles Times",
    "mail.ru" -> "Mail.Ru",
    "makerbizz.net" -> "MakerBiz",
    "mapquest.com" -> "MapQuest",
    "mashable.com" -> "Mashable",
    "medium.com" -> "Medium",
    "microsoft.com" -> "Microsoft",
    "go.microsoft.com" -> "Microsoft",
    "mixpanel.com" -> "Mixpanel",
    "monster.com" -> "Monster",
    "mozilla.com" -> "Mozilla",
    "msn.com" -> "MSN",
    "mywebsearch.com" -> "MyWebSearch",
    "163.com" -> "NetEase",
    "netflix.com" -> "Netflix",
    "movies.netflix.com" -> "Netflix",
    "npr.org" -> "NPR",
    "odnoklassniki.ru" -> "Odnoklassniki",
    "orbitz.com" -> "Orbitz",
    "outbrain.com" -> "Outbrain",
    "usepanda.com" -> "Panda",
    "pando.com" -> "PandoDaily",
    "pandora.com" -> "Pandora",
    "patterntap.com" -> "PatternTap",
    "paypal.com" -> "PayPal",
    "photobucket.com" -> "Photobucket",
    "pinterest.com" -> "Pinterest",
    "pixlr.com" -> "Pixlr",
    "plnkr.co" -> "Plunker",
    "getpocket.com" -> "Pocket",
    "pornhub.com" -> "Pornhub",
    "priceline.com" -> "Priceline",
    "getprismatic.com" -> "Prismatic",
    "producthunt.co" -> "Product Hunt",
    "pttrns.com" -> "Pttrns",
    "quora.com" -> "Quora",
    "rakuten.co.jp" -> "Rakuten",
    "reddit.com" -> "reddit",
    "redtube.com" -> "RedTube",
    "safeway.com" -> "Safeway",
    "screencast.com" -> "Screencast",
    "scribd.com" -> "Scribd",
    "silo.co" -> "Silo",
    "simplyrecipes.com" -> "Simply Recipes",
    "sina.com.cn" -> "Sina Corp",
    "weibo.com" -> "Sina Weibo",
    "slashdot.org" -> "Slashdot",
    "slickdeals.net" -> "Slickdeals",
    "slideshare.net" -> "SlideShare",
    "smashingmagazine.com" -> "Smashing Magazine",
    "sogou.com" -> "Sogou",
    "sohu.com" -> "Sohu",
    "soso.com" -> "soso",
    "soundcloud.com" -> "SoundCloud",
    "play.spotify.com" -> "Spotify",
    "stackoverflow.com" -> "Stack Overflow",
    "stilldrinking.org" -> "Still Drinking",
    "stripe.com" -> "Stripe",
    "stumbleupon.com" -> "StumbleUpon",
    "subtlepatterns.com" -> "Subtle Patterns",
    "taobao.com" -> "Taobao",
    "techcrunch.com" -> "TechCrunch",
    "ted.com" -> "TED",
    "qq.com" -> "Tencent QQ",
    "theatlantic.com" -> "The Atlantic",
    "thedailyshow.cc.com" -> "The Daily Show",
    "telegraph.co.uk" -> "The Daily Telegraph",
    "ellentv.com" -> "The Ellen DeGeneres Show",
    "theguardian.com" -> "The Guardian",
    "huffingtonpost.com" -> "The Huffington Post",
    "nytimes.com" -> "The New York Times",
    "thenounproject.com" -> "The Noun Project",
    "thepiratebay.org" -> "The Pirate Bay",
    "thepiratebay.se" -> "The Pirate Bay",
    "vegassolo.com" -> "The Vegas Solo",
    "theverge.com" -> "The Verge",
    "wsj.com" -> "The Wall Street Journal",
    "themarker.com" -> "TheMarker",
    "tmall.com" -> "Tmall",
    "torrentz.eu" -> "Torrentz",
    "travelocity.com" -> "Travelocity",
    "trello.com" -> "Trello",
    "tripadvisor.com" -> "TripAdvisor",
    "tumblr.com" -> "Tumblr",
    "twistedsifter.com" -> "TwistedSifter",
    "twitter.com" -> "Twitter",
    "twitter.com/" -> "Twitter",
    "ubiome.com" -> "uBiome",
    "ubuntu.com" -> "Unbuntu",
    "uol.com.br" -> "Universo Online",
    "unsplash.com" -> "Unsplash",
    "usatoday.com" -> "USA Today",
    "venturebeat.com" -> "VentureBeat",
    "vimeo.com" -> "Vimeo",
    "vk.com" -> "VKontakte",
    "w3schools.com" -> "W3Schools",
    "wellsfargo.com" -> "Wells Fargo",
    "wikia.com" -> "Wikia",
    "wikipedia.org" -> "Wikipedia",
    "live.com" -> "Windows Live",
    "wired.com" -> "Wired",
    "wolframalpha.com" -> "Wolfram Alpha",
    "wordpress.com" -> "WordPress",
    "xhamster.com" -> "xHamster",
    "xkcd.com" -> "xkcd",
    "xvideos.com" -> "XVideos",
    "news.ycombinator.com" -> "Y Combinator",
    "yahoo.com" -> "Yahoo!",
    "yahoo.co.jp" -> "Yahoo!",
    "yandex.ru" -> "Yandex",
    "yelp.com" -> "Yelp",
    "ynet.co.il" -> "Ynet",
    "youku.com" -> "Youku",
    "youtube.com" -> "YouTube",
    "zillow.com" -> "Zillow"
  )
}
