package com.keepit.common.domain

object ExtensionUnfriendlyDomains {
  // top-level domains of sites where users most frequently move the extension
  def containsMatch(fullDomain: String) = all.exists(topLevelDomain => fullDomain.contains(topLevelDomain))
  val all = Set("saavn.com", "successfactors.com", "pinterest.com", "mit.edu", "jsbin.com", "linkedin.com", "youtube.com",
    "plex.tv", "getfeedback.com", "dropbox.com", "mega.co.nz", "trello.com", "copy.com", "scribd.com", "etsy.com", "netflix.com",
    "blogger.com", "circleci.com", "businessinsider.com", "creativebloq.com", "vimeo.com", "stumbleupon.com", "showtimeanytime.com",
    "twitter.com", "twitch.tv", "icloud.com", "pluralsight.com", "zendesk.com", "huffingtonpost.com", "google.com", "ac.els-cdn.com",
    "moxtra.com", "periscope.tv", "asana.com", "c9.io", "uberconference.com", "prezi.com", "pivotaltracker.com", "theguardian.com",
    "msn.com", "googleusercontent.com", "microsoft.com", "jsfiddle.net", "hubspot.com", "baidu.com", "logentries.com", "crunchbase.com",
    "wikipedia.org", "mashable.com", "latimes.com", "weibo.com", "udemy.com", "amazonaws.com", "bitbucket.org", "soundcloud.com",
    "paypal.com", "behance.net", "dev.ezkeep.com", "tumblr.com", "dropboxusercontent.com", "vk.com", "themeforest.net", "slack-files.com",
    "live.com", "lynda.com", "codecademy.com", "grooveshark.com", "nba.com", "bing.com", "coursera.org", "picmonkey.com", "skillshare.com",
    "ycombinator.com", "ebay.com", "localhost", "163.com", "wunderlist.com", "mindmeister.com", "theverge.com", "messenger.com", "wiley.com",
    "quora.com", "golang.org", "nytimes.com", "piktochart.com", "here.com", "techcrunch.com", "intercom.io", "hootsuite.com", "evernote.com", "instagram.com",
    "washingtonpost.com", "bbc.co.uk", "themarker.com", "glassdoor.com", "khanacademy.org", "invisionapp.com", "gotomeeting.com", "codewars.com", "stackoverflow.com",
    "ryanair.com", "forbes.com", "tympanus.net", "archive.org", "tripadvisor.com", "acfun.tv", "spotify.com", "cnn.com", "wistia.net",
    "time.com", "reddit.com", "taobao.com", "mandrillapp.com", "dribbble.com", "meistertask.com", "weebly.com", "atlassian.net",
    "kickstarter.com", "medium.com", "pushbullet.com", "lifehacker.com", "whatsapp.com", "appear.in", "github.com", "deezer.com",
    "arxiv.org", "kifi.com", "irs.gov", "edx.org", "airbnb.com", "rdio.com", "expedia.com", "wordpress.com", "codepen.io", "feedly.com",
    "qq.com", "quip.com", "player.vimeo.com", "wikiwand.com", "intuit.com", "surveygizmo.com", "slideshare.net", "espn.go.com",
    "lucidchart.com", "producthunt.com", "codeschool.com", "42go.com", "amazon.com", "wired.com", "zillow.com", "duckduckgo.com",
    "godaddy.com", "agilone.com", "koding.com", "canva.com", "sunrise.am", "calm.com", "wix.com", "mailchimp.com", "flickr.com",
    "yahoo.com", "pandora.com", "ynet.co.il", "scoop.it", "office365.com", "streamnation.com", "shutterfly.com", "127.0.0.1",
    "getpocket.com", "sway.com", "mightytext.net", "concursolutions.com", "okcupid.com", "typeform.com", "google.it", "ted.com", "facebook.com")
}
