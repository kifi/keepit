package com.keepit.common.net

import com.keepit.test._
import com.keepit.inject._
import play.api.Play.current
import play.api.libs.json.JsValue
import play.api.test.Helpers._
import akka.actor.ActorRef
import akka.testkit.ImplicitSender
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.mutable.SpecificationWithJUnit
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class URINormalizerTest extends Specification {

  "URINormalizer" should {
    
    "upcase scheme and host" in {
      URINormalizer.normalize("HTTP://KeepItFindIt.com") === "http://keepitfindit.com"
    }
    
    "remove port 80" in {
      URINormalizer.normalize("HTTP://KeepItFindIt.com:8080") === "http://keepitfindit.com:8080"
      URINormalizer.normalize("HTTP://KeepItFindIt.com:80") === "http://keepitfindit.com"
    }
    
    "remove fragment" in {
      URINormalizer.normalize("http://keepitfindit.com/index.html#abc") === "http://keepitfindit.com/index.html"
      URINormalizer.normalize("http://keepitfindit.com/page?xyz=123#abc") === "http://keepitfindit.com/page?xyz=123"
    }
    
    "normalize path" in {
      URINormalizer.normalize("http://keepitfindit.com/") === "http://keepitfindit.com"
      URINormalizer.normalize("http://keepitfindit.com/?") === "http://keepitfindit.com"
      URINormalizer.normalize("http://keepitfindit.com/?&") === "http://keepitfindit.com"
      URINormalizer.normalize("http://keepitfindit.com/a?") === "http://keepitfindit.com/a"
      URINormalizer.normalize("http://keepitfindit.com/a?&") === "http://keepitfindit.com/a"
      URINormalizer.normalize("http://www.example.com/%7Eusername/") === "http://www.example.com/~username/"
      URINormalizer.normalize("http://www.example.com//A//B/index.html") === "http://www.example.com/A/B/index.html"
      URINormalizer.normalize("http://ACME.com/./foo") === "http://acme.com/foo"
      URINormalizer.normalize("http://ACME.com/foo%26bar") === "http://acme.com/foo&bar"
      URINormalizer.normalize("http://www.example.com/../../a.html") === "http://www.example.com/a.html"
      URINormalizer.normalize("http://www.example.com/../a/b/../c/./d.html") === "http://www.example.com/a/c/d.html"
      URINormalizer.normalize("http://foo.bar.com?baz=1") === "http://foo.bar.com/?baz=1"
      URINormalizer.normalize("http://somedomain.com/uploads/1/0/2/5/10259653/6199347.jpg?1325154037") ===
        "http://somedomain.com/uploads/1/0/2/5/10259653/6199347.jpg?1325154037"
    }
    
    "normalize query" in {
      URINormalizer.normalize("http://keepitfindit.com/?a&") === "http://keepitfindit.com/?a"
      URINormalizer.normalize("http://keepitfindit.com/?a&b") === "http://keepitfindit.com/?a&b"
      URINormalizer.normalize("http://keepitfindit.com/?b&a") === "http://keepitfindit.com/?a&b"
      URINormalizer.normalize("http://keepitfindit.com/p?a&b&") === "http://keepitfindit.com/p?a&b"
      URINormalizer.normalize("http://keepitfindit.com/p?b=2&a=") === "http://keepitfindit.com/p?a=&b=2"
      URINormalizer.normalize("http://keepitfindit.com/p?b=2&a=1&") === "http://keepitfindit.com/p?a=1&b=2"
      URINormalizer.normalize("http://keepitfindit.com/p?b=2&c&a") === "http://keepitfindit.com/p?a&b=2&c"
      URINormalizer.normalize("http://keepitfindit.com/p?a=1&c=3&b=2&a=0") === "http://keepitfindit.com/p?a=0&b=2&c=3"
      URINormalizer.normalize("http://keepitfindit.com/p?a=1=1") === "http://keepitfindit.com/p?a=1%3D1"
      val escapedEqual = java.net.URLEncoder.encode("=", "UTF-8")
      URINormalizer.normalize("http://keepitfindit.com/p?a=1"+escapedEqual+"1") === "http://keepitfindit.com/p?a=1%3D1"
      URINormalizer.normalize("http://keepitfindit.com?foo=1") === "http://keepitfindit.com/?foo=1"
      URINormalizer.normalize("http://keepitfindit.com?&foo=1") === "http://keepitfindit.com/?foo=1"
      
      URINormalizer.normalize("http://www.example.com/?q=a+b") === "http://www.example.com/?q=a+b"
      URINormalizer.normalize("http://www.example.com/display?category=foo/bar+baz") === "http://www.example.com/display?category=foo%2Fbar+baz"
      URINormalizer.normalize("http://www.example.com/display?category=foo%2Fbar%20baz") === "http://www.example.com/display?category=foo%2Fbar+baz"
      URINormalizer.normalize("http://www.example.com/index.html?q=a b") === "http://www.example.com/index.html?q=a+b"
      
      URINormalizer.normalize("http://www.example.com/search?width=100%&height=100%") === "http://www.example.com/search?height=100%25&width=100%25"
      URINormalizer.normalize("http://www.example.com/search?zoom=50%x50%") === "http://www.example.com/search?zoom=50%25x50%25"
    }
      
    "remove session parameters and tracking parameters" in {
      URINormalizer.normalize("http://keepitfindit.com/p?a=1&jsessionid=1234") === "http://keepitfindit.com/p?a=1"
      URINormalizer.normalize("http://keepitfindit.com/p?jsessionid=1234&a=1") === "http://keepitfindit.com/p?a=1"
      URINormalizer.normalize("http://keepitfindit.com/p?jsessionid=1234&utm_source=5678") === "http://keepitfindit.com/p"
    }
  }
}
