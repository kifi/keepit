package com.keepit.common.routes

import org.specs2.mutable.Specification

class ServiceRouteTest extends Specification {

  ServiceRoute(GET, "/search", Param("q", "scala"), Param("maxHits", 10)).url === "/search?q=scala&maxHits=10"
  ServiceRoute(GET, "/search", Param("q", "scala"), Param("maxHits", Some(10))).url === "/search?q=scala&maxHits=10"
  ServiceRoute(GET, "/search", Param("q", "scala"), Param("maxHits", None)).url === "/search?q=scala"
  ServiceRoute(GET, "/search", Param("q", ""), Param("maxHits", None)).url === "/search?q="
  ServiceRoute(GET, "/search", Param("q", None), Param("maxHits", None)).url === "/search"
}
