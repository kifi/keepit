import com.typesafe.sbteclipse.core.EclipsePlugin.EclipseKeys
import play.Project._
import sbt.Keys._
import sbt._

object ApplicationBuild extends Build {

  override def settings = super.settings ++ Seq(EclipseKeys.skipParents in ThisBuild := false)
  val appName = "kifi-backend"

  val appVersion = Version.appVersion

  Version.writeVersionToFile()

  val commonDependencies = Seq(
    jdbc, // todo(andrew): move to sqldb when we discover a way to get Play to support multiple play.plugins files.
    cache,
    "com.typesafe.play.plugins" %% "play-statsd" % "2.2.0" exclude("play", "*"),
    "com.typesafe" %% "play-plugins-mailer" % "2.2.0" exclude("play", "*"),
    "securesocial" %% "securesocial" % "master-20130808" exclude("play", "*"),
    "org.clapper" %% "grizzled-slf4j" % "1.0.1",
    "com.typesafe.akka" %% "akka-testkit" % "2.2.3"  exclude("play", "*"),
    "org.apache.commons" % "commons-compress" % "1.4.1",
    "org.apache.commons" % "commons-math3" % "3.1.1",
    "commons-io" % "commons-io" % "2.4",
    "org.apache.zookeeper" % "zookeeper" % "3.4.5" exclude("org.slf4j", "slf4j-log4j12"),
    "commons-codec" % "commons-codec" % "1.6",
    "com.cybozu.labs" % "langdetect" % "1.1-20120112", // todo(andrew): remove from common. make shared module between search and scraper.
    "org.mindrot" % "jbcrypt" % "0.3m",
    "com.amazonaws" % "aws-java-sdk" % "1.6.12",
    "com.kifi" % "franz_2.10" % "0.3.5",
    "net.sf.uadetector" % "uadetector-resources" % "2013.11",
    "com.google.inject" % "guice" % "3.0",
    "com.google.inject.extensions" % "guice-multibindings" % "3.0",
    "net.codingwell" %% "scala-guice" % "3.0.2",
    "org.imgscalr" % "imgscalr-lib" % "4.2",
    "us.theatr" %% "akka-quartz" % "0.2.0_42.1" exclude("c3p0", "c3p0"),
    "org.jsoup" % "jsoup" % "1.7.1",
    "org.bouncycastle" % "bcprov-jdk15on" % "1.50",
    "org.msgpack" %% "msgpack-scala" % "0.6.8",
    "com.kifi" %% "json-annotation" % "0.1",
    "com.mchange" % "c3p0" % "0.9.5-pre8" // todo(andrew): remove from common when C3P0 plugin is in sqldb
  ) map (_.excludeAll(
    ExclusionRule(organization = "javax.jms"),
    ExclusionRule(organization = "com.sun.jdmk"),
    ExclusionRule(organization = "com.sun.jmx"),
    ExclusionRule(organization = "org.jboss.netty"),
    ExclusionRule("org.scala-stm", "scala-stm_2.10.0")
  ))

  lazy val searchDependencies = Seq(
    "edu.stanford.nlp.models" % "stanford-corenlp-models" % "1.3.5"
      from "http://scalasbt.artifactoryonline.com/scalasbt/repo/edu/stanford/nlp/stanford-corenlp/1.3.5/stanford-corenlp-1.3.5-models.jar",
    "edu.stanford.nlp" % "stanford-corenlp" % "1.3.5",
    "org.apache.lucene" % "lucene-core" % "4.7.0",
    "org.apache.lucene" % "lucene-analyzers-common" % "4.7.0",
    "org.apache.lucene" % "lucene-analyzers-kuromoji" % "4.7.0",
    "org.apache.lucene" % "lucene-suggest" % "4.7.0"
  )

  lazy val sqldbDependencies = Seq(
    "mysql" % "mysql-connector-java" % "5.1.25",
    "com.typesafe.slick" %% "slick" % "2.0.0" exclude("play", "*")
  )

  lazy val shoeboxDependencies = Seq(
    "javax.mail" % "mail" % "1.4.5",
    "com.typesafe.slick" %% "slick-testkit" % "2.0.0" exclude("play", "*"),
    "org.apache.poi" % "poi" % "3.8",
    "com.googlecode.mp4parser" % "isoparser" % "1.0-RC-1",
    "org.feijoas" %% "mango" % "0.10"
  )

  lazy val heimdalDependencies = Seq(
    "org.reactivemongo" %% "reactivemongo" % "0.10.0",
    "org.reactivemongo" %% "play2-reactivemongo" % "0.10.2",
    "com.maxmind.geoip2" % "geoip2" % "0.5.0",
    "com.mixpanel" % "mixpanel-java" % "1.2.1"
  ) map (_.excludeAll(
    ExclusionRule(organization = "org.slf4j"),
    ExclusionRule(organization = "ch.qos.logback")
  ))

  lazy val abookDependencies = Seq()

  lazy val scraperDependencies = Seq(
    "org.apache.lucene" % "lucene-analyzers-common" % "4.7.0",
    "org.apache.httpcomponents" % "httpclient" % "4.3.2",
    "org.apache.tika" % "tika-parsers" % "1.5"
  )

  lazy val cortexDependencies = Seq(
    // got this from http://grepcode.com/
    "edu.stanford.nlp.models" % "stanford-corenlp-models" % "3.2.0" from
      "http://repo1.maven.org/maven2/edu/stanford/nlp/stanford-corenlp/3.2.0/stanford-corenlp-3.2.0-models.jar",
    "edu.stanford.nlp" % "stanford-corenlp" % "3.2.0"
  )

  lazy val graphDependencies = Seq()

  lazy val curatorDependencies = Seq()

  lazy val commonSettings =
    Global.settings ++
    PlayGlobal.settings ++
    Seq(Logs.emojiLogs) ++
    net.virtualvoid.sbt.graph.Plugin.graphSettings

  lazy val macros = Project(id = s"macros", base = file("modules/macros")).settings(
    Global.macroParadiseSettings: _*
  ).settings(
    libraryDependencies += "org.scala-lang" % "scala-reflect" % "2.10.0",
    libraryDependencies ++= (
      if (scalaVersion.value.startsWith("2.10")) Seq("org.scalamacros" %% "quasiquotes" % "2.0.1")
      else Nil
    )
  )

  lazy val common = play.Project("common", appVersion, commonDependencies, path = file("modules/common")).settings(
    commonSettings: _*
  ).settings(
    javaOptions in Test += "-Dconfig.resource=application-dev.conf"
  ).dependsOn(macros)

  lazy val sqldb = play.Project("sqldb", appVersion, sqldbDependencies, path = file("modules/sqldb")).settings(
    commonSettings: _*
  ).dependsOn(common % "test->test;compile->compile")

  lazy val shoebox = play.Project("shoebox", appVersion, shoeboxDependencies, path = file("modules/shoebox")).settings(
    commonSettings: _*
  ).settings(
    Frontend.angularDirectory <<= (baseDirectory in Compile) { _ / "angular" },
    playAssetsDirectories <+= (baseDirectory in Compile)(_ / "angular"),
    javaOptions in Test += "-Dconfig.resource=application-shoebox.conf"
  ).settings(
    Frontend.gulpCommands: _*
  ).dependsOn(common % "test->test;compile->compile", sqldb % "test->test;compile->compile")

  lazy val search = play.Project("search", appVersion, searchDependencies, path = file("modules/search")).settings(
    commonSettings: _*
  ).settings(
    javaOptions in Test += "-Dconfig.resource=application-search.conf"
  ).dependsOn(common % "test->test;compile->compile")

  lazy val eliza = play.Project("eliza", appVersion, Nil, path = file("modules/eliza")).settings(
    commonSettings: _*
  ).settings(
    javaOptions in Test += "-Dconfig.resource=application-eliza.conf",
    routesImport ++= Seq(
      "com.keepit.eliza._",
      "com.keepit.eliza.model._"
    )
  ).dependsOn(common % "test->test;compile->compile", sqldb % "test->test;compile->compile")

  lazy val heimdal = play.Project("heimdal", appVersion, heimdalDependencies, path=file("modules/heimdal")).settings(
    commonSettings: _*
  ).settings(
    javaOptions in Test += "-Dconfig.resource=application-heimdal.conf"
  ).dependsOn(common % "test->test;compile->compile", sqldb % "test->test;compile->compile")

  lazy val abook = play.Project("abook", appVersion, abookDependencies, path=file("modules/abook")).settings(
    commonSettings: _*
  ).settings(
    javaOptions in Test += "-Dconfig.resource=application-abook.conf"
  ).dependsOn(common % "test->test;compile->compile", sqldb % "test->test;compile->compile")

  lazy val scraper = play.Project("scraper", appVersion, scraperDependencies, path=file("modules/scraper")).settings(
    commonSettings: _*
  ).settings(
    javaOptions in Test += "-Dconfig.resource=application-scraper.conf"
  ).dependsOn(common % "test->test;compile->compile")

  lazy val cortex = play.Project("cortex", appVersion, cortexDependencies, path=file("modules/cortex")).settings(
    commonSettings: _*
  ).settings(
    javaOptions in Test += "-Dconfig.resource=application-cortex.conf"
  ).dependsOn(common % "test->test;compile->compile", sqldb % "test->test;compile->compile")

  lazy val graph = play.Project("graph", appVersion, graphDependencies, path=file("modules/graph")).settings(
    commonSettings: _*
  ).settings(
    javaOptions in Test += "-Dconfig.resource=application-graph.conf"
  ).dependsOn(common % "test->test;compile->compile")

  lazy val curator = play.Project("curator", appVersion, curatorDependencies, path=file("modules/curator")).settings(
    commonSettings: _*
  ).settings(
    javaOptions in Test += "-Dconfig.resource=application-curator.conf"
  ).dependsOn(common % "test->test;compile->compile", sqldb % "test->test;compile->compile")

  lazy val kifiBackend = play.Project(appName, "0.42").settings(commonSettings: _*).settings(
    aggregate in update := false,
    Frontend.angularDirectory <<= (baseDirectory in Compile) { _ / "modules/shoebox/angular" }
  ).dependsOn(
    common % "test->test;compile->compile",
    shoebox % "test->test;compile->compile",
    search % "test->test;compile->compile",
    eliza % "test->test;compile->compile",
    heimdal % "test->test;compile->compile",
    abook % "test->test;compile->compile",
    scraper % "test->test;compile->compile",
    cortex % "test->test;compile->compile",
    graph % "test->test;compile->compile",
    curator % "test->test;compile->compile"
  ).aggregate(common, shoebox, search, eliza, heimdal, abook, scraper, sqldb, cortex, graph, curator)

  lazy val distProject = Project(id = "dist", base = file("./.dist")).settings(
      aggregate in update := false
  ).aggregate(search, shoebox, eliza, heimdal, abook, scraper, cortex, graph, curator)

  override def rootProject = Some(kifiBackend)
}
