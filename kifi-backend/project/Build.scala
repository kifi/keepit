import com.typesafe.sbteclipse.core.EclipsePlugin.EclipseKeys
import sbt.Keys._
import sbt._
import play.Play.autoImport._
import PlayKeys._
import play.twirl.sbt.Import._


object ApplicationBuild extends Build {

  override def settings = super.settings ++ Seq(EclipseKeys.skipParents in ThisBuild := false)
  val appName = "kifi-backend"

  val appVersion = Version.appVersion

  Version.writeVersionToFile()

  lazy val searchDependencies = Seq(
    "edu.stanford.nlp.models" % "stanford-corenlp-models" % "1.3.5"
      from "http://scalasbt.artifactoryonline.com/scalasbt/repo/edu/stanford/nlp/stanford-corenlp/1.3.5/stanford-corenlp-1.3.5-models.jar",
    "edu.stanford.nlp" % "stanford-corenlp" % "1.3.5",
    "org.apache.lucene" % "lucene-core" % "4.7.0",
    "org.apache.lucene" % "lucene-analyzers-common" % "4.7.0",
    "org.apache.lucene" % "lucene-analyzers-kuromoji" % "4.7.0",
    "org.apache.lucene" % "lucene-suggest" % "4.7.0"
  )

  lazy val elizaDependencies = Seq(
    ws
  )

  lazy val sqldbDependencies = Seq(
    "mysql" % "mysql-connector-java" % "5.1.25",
    "com.typesafe.slick" %% "slick" % "2.1.0" exclude("play", "*")
  )

  lazy val shoeboxDependencies = Seq(
    "javax.mail" % "mail" % "1.4.5",
    "com.typesafe.slick" %% "slick-testkit" % "2.1.0" exclude("play", "*"),
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
    "org.apache.httpcomponents" % "httpclient" % "4.3.1",
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
  lazy val common = Project("common", file("modules/common")).enablePlugins(play.PlayScala).settings(
    commonSettings: _*
  ).settings(
    javaOptions in Test += "-Dconfig.resource=application-dev.conf"
  ).dependsOn(macros)

  lazy val sqldb = Project("sqldb", file("modules/sqldb")).enablePlugins(play.PlayScala).settings(
    commonSettings: _*
  ).settings(
    libraryDependencies ++= sqldbDependencies
  ).dependsOn(common % "test->test;compile->compile")

  lazy val shoebox = Project("shoebox", file("modules/shoebox")).enablePlugins(play.PlayScala).settings(
    commonSettings: _*
  ).settings(
    libraryDependencies ++= shoeboxDependencies,
    Frontend.angularDirectory <<= (baseDirectory in Compile) { _ / "angular" },
    //unmanagedResourceDirectories in Assets += baseDirectory.value / "angular",
    javaOptions in Test += "-Dconfig.resource=application-shoebox.conf"
  ).settings(
    Frontend.gulpCommands: _*
  ).dependsOn(common % "test->test;compile->compile", sqldb % "test->test;compile->compile")

  lazy val search = Project("search", file("modules/search")).enablePlugins(play.PlayScala).settings(
    commonSettings: _*
  ).settings(
    libraryDependencies ++= searchDependencies,
    javaOptions in Test += "-Dconfig.resource=application-search.conf"
  ).dependsOn(common % "test->test;compile->compile")

  lazy val eliza = Project("eliza", file("modules/eliza")).enablePlugins(play.PlayScala).settings(
    commonSettings: _*
  ).settings(
    libraryDependencies ++= elizaDependencies,
    javaOptions in Test += "-Dconfig.resource=application-eliza.conf",
    routesImport ++= Seq(
      "com.keepit.eliza._",
      "com.keepit.eliza.model._"
    )
  ).dependsOn(common % "test->test;compile->compile", sqldb % "test->test;compile->compile")

  lazy val heimdal = Project("heimdal", file("modules/heimdal")).enablePlugins(play.PlayScala).settings(
    commonSettings: _*
  ).settings(
    libraryDependencies ++= heimdalDependencies,
    javaOptions in Test += "-Dconfig.resource=application-heimdal.conf"
  ).dependsOn(common % "test->test;compile->compile", sqldb % "test->test;compile->compile")

  lazy val abook = Project("abook", file("modules/abook")).enablePlugins(play.PlayScala).settings(
    commonSettings: _*
  ).settings(
    libraryDependencies ++= abookDependencies,
    javaOptions in Test += "-Dconfig.resource=application-abook.conf"
  ).dependsOn(common % "test->test;compile->compile", sqldb % "test->test;compile->compile")

  lazy val scraper = Project("scraper", file("modules/scraper")).enablePlugins(play.PlayScala).settings(
    commonSettings: _*
  ).settings(
    libraryDependencies ++= scraperDependencies,
    javaOptions in Test += "-Dconfig.resource=application-scraper.conf"
  ).dependsOn(common % "test->test;compile->compile")

  lazy val cortex = Project("cortex", file("modules/cortex")).enablePlugins(play.PlayScala).settings(
    commonSettings: _*
  ).settings(
    libraryDependencies ++= cortexDependencies,
    javaOptions in Test += "-Dconfig.resource=application-cortex.conf"
  ).dependsOn(common % "test->test;compile->compile", sqldb % "test->test;compile->compile")

  lazy val graph = Project("graph", file("modules/graph")).enablePlugins(play.PlayScala).settings(
    commonSettings: _*
  ).settings(
    libraryDependencies ++= graphDependencies,
    javaOptions in Test += "-Dconfig.resource=application-graph.conf"
  ).dependsOn(common % "test->test;compile->compile")

  lazy val curator = Project("curator", file("modules/curator")).enablePlugins(play.PlayScala).settings(
    commonSettings: _*
  ).settings(
    libraryDependencies ++= curatorDependencies,
    javaOptions in Test += "-Dconfig.resource=application-curator.conf"
  ).dependsOn(common % "test->test;compile->compile", sqldb % "test->test;compile->compile")

  lazy val kifiBackend = Project(appName, file(".")).enablePlugins(play.PlayScala).settings(commonSettings: _*).settings(
    version := "0.42",
    aggregate in update := false,
    Frontend.angularDirectory <<= (baseDirectory in Compile) { _ / "modules/shoebox/angular" }
  ).settings(
    Frontend.gulpCommands: _*
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
